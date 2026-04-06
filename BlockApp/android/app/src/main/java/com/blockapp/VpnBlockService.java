package com.blockapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local VPN service that intercepts DNS and implements:
 *   - block / close  → NXDOMAIN
 *   - redirect       → fake A record (10.0.0.3) + embedded HTTP 302 server
 *
 * Only routes 10.0.0.2/32 (virtual DNS) and 10.0.0.3/32 (redirect server)
 * through the TUN interface — all other traffic is unaffected.
 */
public class VpnBlockService extends VpnService {

    private static final String TAG        = "VpnBlockService";
    private static final String CHANNEL_ID = "vpn_block_channel";
    private static final int    NOTIF_ID   = 1;

    private static final String VPN_DNS_ADDRESS = "10.0.0.2";
    private static final String UPSTREAM_DNS    = "8.8.8.8";

    private static final AtomicBoolean GLOBAL_RUNNING = new AtomicBoolean(false);
    public static boolean isRunning() { return GLOBAL_RUNNING.get(); }

    private ParcelFileDescriptor vpnInterface;
    private Thread               packetThread;
    private final AtomicBoolean  running = GLOBAL_RUNNING;

    // Socket UDP réutilisé pour tous les forwards DNS (évite l'allocation par requête)
    private final AtomicReference<DatagramSocket> upstreamSocket = new AtomicReference<>();

    // ── Blocked domains (simple list of lower-case domain strings) ───────────

    private final List<String> blockedDomains = new ArrayList<>();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundNotification();
        startVpn();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    // ── VPN setup ─────────────────────────────────────────────────────────────

    private void startVpn() {
        if (running.get()) return;
        running.set(true);
        packetThread = new Thread(() -> {
            fetchBlockedDomains();
            openTunInterface();
            processPackets();
        }, "vpn-packet-thread");
        packetThread.start();
    }

    private void stopVpn() {
        running.set(false);
        if (packetThread != null) packetThread.interrupt();
        if (vpnInterface != null) {
            try { vpnInterface.close(); } catch (Exception ignored) {}
            vpnInterface = null;
        }
        DatagramSocket sock = upstreamSocket.getAndSet(null);
        if (sock != null && !sock.isClosed()) sock.close();
    }

    // ── Load config from local DB ─────────────────────────────────────────────

    private void fetchBlockedDomains() {
        try {
            List<String> domains = BlockerDatabase.getInstance(this).getActiveBlockedDomains();
            synchronized (blockedDomains) {
                blockedDomains.clear();
                blockedDomains.addAll(domains);
            }
            Log.d(TAG, "Loaded " + blockedDomains.size() + " domains from DB");
        } catch (Exception e) {
            Log.e(TAG, "fetchBlockedDomains failed: " + e.getMessage());
        }
    }

    // ── TUN interface ─────────────────────────────────────────────────────────

    private void openTunInterface() {
        try {
            Builder builder = new Builder()
                .setSession("BlockApp")
                .addAddress("10.0.0.1", 24)
                .addRoute(VPN_DNS_ADDRESS, 32)
                .addDnsServer(VPN_DNS_ADDRESS)
                .setMtu(1500);
            try { builder.addDisallowedApplication(getPackageName()); } catch (Exception ignored) {}
            vpnInterface = builder.establish();
            Log.d(TAG, "TUN established");
        } catch (Exception e) {
            Log.e(TAG, "TUN failed: " + e.getMessage());
            running.set(false);
        }
    }

    // ── Packet processing loop ────────────────────────────────────────────────

    private void processPackets() {
        if (vpnInterface == null) return;
        FileInputStream  in  = new FileInputStream(vpnInterface.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
        byte[] buf = new byte[32767];
        long lastRefresh = System.currentTimeMillis();
        // Rafraîchissement toutes les 5 minutes au lieu de 30 secondes
        final long REFRESH_INTERVAL_MS = 5 * 60 * 1_000L;

        while (running.get()) {
            try {
                int len = in.read(buf);
                if (len <= 0) continue;

                long now = System.currentTimeMillis();
                if (now - lastRefresh > REFRESH_INTERVAL_MS) {
                    lastRefresh = now;
                    new Thread(this::fetchBlockedDomains).start();
                }

                byte[] pkt = new byte[len];
                System.arraycopy(buf, 0, pkt, 0, len);
                handleIpPacket(pkt, out);
            } catch (Exception e) {
                if (running.get()) Log.e(TAG, "Packet loop error: " + e.getMessage());
                break;
            }
        }
        try { in.close();  } catch (Exception ignored) {}
        try { out.close(); } catch (Exception ignored) {}
    }

    // ── IP dispatch ───────────────────────────────────────────────────────────

    private void handleIpPacket(byte[] packet, FileOutputStream out) throws Exception {
        if (packet.length < 20) return;
        if (((packet[0] >> 4) & 0xF) != 4) return; // IPv4 only

        int ihl      = (packet[0] & 0xF) * 4;
        int protocol = packet[9] & 0xFF;

        if (protocol == 17) { // UDP → DNS
            if (packet.length < ihl + 8) return;
            int dstPort = ((packet[ihl+2] & 0xFF) << 8) | (packet[ihl+3] & 0xFF);
            if (dstPort != 53) return;
            int srcPort   = ((packet[ihl] & 0xFF) << 8) | (packet[ihl+1] & 0xFF);
            int dnsOff    = ihl + 8;
            int dnsLen    = packet.length - dnsOff;
            if (dnsLen <= 0) return;
            DnsPacket dns = DnsPacket.parse(packet, dnsOff, dnsLen);
            if (dns == null) return;
            byte[] resp = resolveDns(dns);
            if (resp == null) return;
            buildAndWriteUdpResponse(packet, ihl, srcPort, resp, out);
        }
    }

    // ── DNS resolution ────────────────────────────────────────────────────────

    private byte[] resolveDns(DnsPacket dns) {
        if (dns.queryType != DnsPacket.TYPE_A && dns.queryType != 28 /* AAAA */) {
            return forwardToUpstream(dns.rawPayload);
        }

        if (!isDomainBlocked(dns.queryName)) return forwardToUpstream(dns.rawPayload);

        Log.d(TAG, "[block] " + dns.queryName);
        return dns.buildNxDomainResponse();
    }

    private boolean isDomainBlocked(String domain) {
        synchronized (blockedDomains) {
            for (String blocked : blockedDomains) {
                if (DnsPacket.matches(domain, blocked)) return true;
            }
        }
        return false;
    }

    // ── DNS upstream forward ──────────────────────────────────────────────────

    private byte[] forwardToUpstream(byte[] dnsPayload) {
        try {
            DatagramSocket socket = upstreamSocket.get();
            if (socket == null || socket.isClosed()) {
                socket = new DatagramSocket();
                protect(socket);
                socket.setSoTimeout(3000);
                if (!upstreamSocket.compareAndSet(null, socket)) {
                    // Une autre requête a déjà mis un socket en place, utiliser le sien
                    socket.close();
                    socket = upstreamSocket.get();
                }
            }
            InetAddress upstream = InetAddress.getByName(UPSTREAM_DNS);
            DatagramPacket req = new DatagramPacket(dnsPayload, dnsPayload.length, upstream, 53);
            socket.send(req);
            byte[] buf = new byte[4096];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            socket.receive(resp);
            byte[] result = new byte[resp.getLength()];
            System.arraycopy(buf, 0, result, 0, resp.getLength());
            return result;
        } catch (Exception e) {
            Log.e(TAG, "DNS forward failed: " + e.getMessage());
            // Socket potentiellement corrompu, le réinitialiser
            DatagramSocket old = upstreamSocket.getAndSet(null);
            if (old != null && !old.isClosed()) old.close();
            return null;
        }
    }

    private void buildAndWriteUdpResponse(byte[] originalPacket, int originalIhl,
                                           int clientPort, byte[] dnsResponse,
                                           FileOutputStream out) throws Exception {
        byte[] dstIp = {10, 0, 0, 1};
        byte[] srcIp = {10, 0, 0, 2};
        int udpLen   = 8 + dnsResponse.length;
        int totalLen = 20 + udpLen;
        byte[] packet = new byte[totalLen];

        packet[0] = 0x45; packet[1] = 0x00;
        packet[2] = (byte)((totalLen >> 8) & 0xFF); packet[3] = (byte)(totalLen & 0xFF);
        packet[4] = originalPacket[4]; packet[5] = originalPacket[5];
        packet[6] = 0x00; packet[7] = 0x00;
        packet[8] = 0x40; packet[9] = 0x11; // TTL=64, UDP
        packet[10] = 0x00; packet[11] = 0x00;
        packet[12] = srcIp[0]; packet[13] = srcIp[1]; packet[14] = srcIp[2]; packet[15] = srcIp[3];
        packet[16] = dstIp[0]; packet[17] = dstIp[1]; packet[18] = dstIp[2]; packet[19] = dstIp[3];
        int cs = ipChecksum(packet, 0, 20);
        packet[10] = (byte)((cs >> 8) & 0xFF); packet[11] = (byte)(cs & 0xFF);

        packet[20] = 0x00; packet[21] = 53;
        packet[22] = (byte)((clientPort >> 8) & 0xFF); packet[23] = (byte)(clientPort & 0xFF);
        packet[24] = (byte)((udpLen >> 8) & 0xFF); packet[25] = (byte)(udpLen & 0xFF);
        packet[26] = 0x00; packet[27] = 0x00;
        System.arraycopy(dnsResponse, 0, packet, 28, dnsResponse.length);
        out.write(packet);
    }

    // ── Checksum ──────────────────────────────────────────────────────────────

    private static int ipChecksum(byte[] data, int offset, int length) {
        int sum = 0;
        for (int i = offset; i < offset + length - 1; i += 2) {
            sum += ((data[i] & 0xFF) << 8) | (data[i+1] & 0xFF);
        }
        if ((length & 1) != 0) sum += (data[offset + length - 1] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return ~sum & 0xFFFF;
    }

    // ── Foreground notification ───────────────────────────────────────────────

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "BlockApp VPN", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("VPN de blocage actif");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);

        Notification notification = builder
            .setContentTitle("BlockApp actif")
            .setContentText("Blocage DNS en cours")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }
}
