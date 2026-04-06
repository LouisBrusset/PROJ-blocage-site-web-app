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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final byte[] REDIRECT_IP     = {10, 0, 0, 3}; // virtual redirect server IP
    private static final int    OUR_TCP_ISN     = 0x41424344;    // arbitrary TCP initial seq

    private String backendUrl = "http://192.168.1.43:8001/api";

    private static final AtomicBoolean GLOBAL_RUNNING = new AtomicBoolean(false);
    public static boolean isRunning() { return GLOBAL_RUNNING.get(); }

    private ParcelFileDescriptor vpnInterface;
    private Thread               packetThread;
    private final AtomicBoolean  running = GLOBAL_RUNNING;

    // ── Blocked entries ───────────────────────────────────────────────────────

    private static class BlockEntry {
        String url;
        String action;      // "block" | "redirect" | "close"
        String redirectUrl; // only for action="redirect"
    }

    private final List<BlockEntry> blockedEntries = new ArrayList<>();

    // ── TCP session state (for redirect server) ───────────────────────────────

    private static class TcpSession {
        int           clientSeq; // next expected byte from client
        StringBuilder buf = new StringBuilder();
    }

    private final ConcurrentHashMap<Integer, TcpSession> tcpSessions = new ConcurrentHashMap<>();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && intent.hasExtra("backendUrl")) {
            backendUrl = intent.getStringExtra("backendUrl");
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
    }

    // ── Fetch config ──────────────────────────────────────────────────────────

    private void fetchBlockedDomains() {
        synchronized (blockedEntries) { blockedEntries.clear(); }
        try {
            java.net.URL url = new java.net.URL(backendUrl + "/vpn/config");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() == 200) {
                java.io.InputStream is = conn.getInputStream();
                byte[] buf = new byte[8192];
                int n = is.read(buf);
                String json = new String(buf, 0, n);
                JSONObject obj = new JSONObject(json);
                JSONArray arr = obj.getJSONArray("blocked");
                synchronized (blockedEntries) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject e = arr.getJSONObject(i);
                        BlockEntry entry = new BlockEntry();
                        entry.url         = e.getString("url").toLowerCase();
                        entry.action      = e.optString("action", "block");
                        entry.redirectUrl = e.isNull("redirect_url") ? null : e.optString("redirect_url", null);
                        blockedEntries.add(entry);
                    }
                }
                Log.d(TAG, "Loaded " + blockedEntries.size() + " entries");
            }
            conn.disconnect();
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
                .addRoute(VPN_DNS_ADDRESS, 32)  // virtual DNS server
                .addRoute("10.0.0.3", 32)       // virtual redirect HTTP server
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

        while (running.get()) {
            try {
                int len = in.read(buf);
                if (len <= 0) continue;

                long now = System.currentTimeMillis();
                if (now - lastRefresh > 30_000) {
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

        } else if (protocol == 6) { // TCP → redirect server
            handleTcpRedirect(packet, ihl, out);
        }
    }

    // ── DNS resolution ────────────────────────────────────────────────────────

    private byte[] resolveDns(DnsPacket dns) {
        if (dns.queryType != DnsPacket.TYPE_A && dns.queryType != 28 /* AAAA */) {
            return forwardToUpstream(dns.rawPayload);
        }

        BlockEntry entry = findEntry(dns.queryName);
        if (entry == null) return forwardToUpstream(dns.rawPayload);

        Log.d(TAG, "[" + entry.action + "] " + dns.queryName);

        if ("redirect".equals(entry.action) && entry.redirectUrl != null) {
            // Return our virtual redirect server IP so the browser connects to it
            return dns.buildARecordResponse(REDIRECT_IP);
        }
        // block / close → NXDOMAIN (browser shows "site unreachable")
        return dns.buildNxDomainResponse();
    }

    private BlockEntry findEntry(String domain) {
        synchronized (blockedEntries) {
            for (BlockEntry e : blockedEntries) {
                if (DnsPacket.matches(domain, e.url)) return e;
            }
        }
        return null;
    }

    // ── TCP redirect server (HTTP only, port 80) ──────────────────────────────

    private void handleTcpRedirect(byte[] packet, int ihl, FileOutputStream out) {
        try {
            if (packet.length < ihl + 20) return;
            // Only intercept packets to REDIRECT_IP (10.0.0.3)
            if (packet[16] != REDIRECT_IP[0] || packet[17] != REDIRECT_IP[1] ||
                packet[18] != REDIRECT_IP[2] || packet[19] != REDIRECT_IP[3]) return;

            int srcPort   = toInt16(packet, ihl);
            int dstPort   = toInt16(packet, ihl+2);
            if (dstPort != 80) return; // HTTP only — HTTPS requires TLS, not supported

            int tcpHdrLen = ((packet[ihl+12] >> 4) & 0xF) * 4;
            int flags     = packet[ihl+13] & 0xFF;
            int seqNum    = toInt32(packet, ihl+4);

            byte[] clientIp = {packet[12], packet[13], packet[14], packet[15]};

            if ((flags & 0x04) != 0 || (flags & 0x01) != 0) { // RST or FIN
                tcpSessions.remove(srcPort);
                return;
            }

            if ((flags & 0x02) != 0 && (flags & 0x10) == 0) { // SYN
                TcpSession session = new TcpSession();
                session.clientSeq = seqNum + 1;
                tcpSessions.put(srcPort, session);
                writeTcp(out, clientIp, srcPort, 0x12 /* SYN|ACK */, OUR_TCP_ISN, seqNum + 1, null);
                return;
            }

            TcpSession session = tcpSessions.get(srcPort);
            if (session == null) return;

            int dataStart = ihl + tcpHdrLen;
            int dataLen   = packet.length - dataStart;

            if (dataLen > 0 && (flags & 0x08) != 0) { // PSH — HTTP request data
                session.buf.append(new String(packet, dataStart, dataLen, "ISO-8859-1"));
                String http = session.buf.toString();
                if (!http.contains("\r\n\r\n") && !http.contains("\n\n")) return; // incomplete

                String redirectUrl = resolveRedirectUrl(http);
                if (redirectUrl == null) redirectUrl = "about:blank";

                String httpResp = "HTTP/1.1 302 Found\r\nLocation: " + redirectUrl +
                                  "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                byte[] respBytes = httpResp.getBytes("ISO-8859-1");

                int ourDataSeq = OUR_TCP_ISN + 1;
                int clientAck  = seqNum + dataLen;
                writeTcp(out, clientIp, srcPort, 0x10 /* ACK */,           ourDataSeq, clientAck, null);
                writeTcp(out, clientIp, srcPort, 0x19 /* FIN|PSH|ACK */,   ourDataSeq, clientAck, respBytes);
                tcpSessions.remove(srcPort);
            }
        } catch (Exception e) {
            Log.e(TAG, "TCP redirect error: " + e.getMessage());
        }
    }

    private String resolveRedirectUrl(String httpRequest) {
        String host = null;
        for (String line : httpRequest.split("\r?\n")) {
            if (line.toLowerCase().startsWith("host:")) {
                host = line.substring(5).trim().split(":")[0].toLowerCase();
                break;
            }
        }
        if (host == null) return null;
        synchronized (blockedEntries) {
            for (BlockEntry e : blockedEntries) {
                if ("redirect".equals(e.action) && e.redirectUrl != null && DnsPacket.matches(host, e.url)) {
                    return e.redirectUrl;
                }
            }
        }
        return null;
    }

    // ── TCP packet builder ────────────────────────────────────────────────────

    private void writeTcp(FileOutputStream out, byte[] dstIp, int dstPort,
                           int flags, int seq, int ack, byte[] data) throws Exception {
        int dataLen  = data != null ? data.length : 0;
        int tcpLen   = 20 + dataLen;
        int totalLen = 20 + tcpLen;
        byte[] pkt   = new byte[totalLen];

        // IP header
        pkt[0] = 0x45; pkt[1] = 0x00;
        pkt[2] = (byte)((totalLen >> 8) & 0xFF); pkt[3] = (byte)(totalLen & 0xFF);
        pkt[4] = 0x00; pkt[5] = 0x00; pkt[6] = 0x00; pkt[7] = 0x00;
        pkt[8] = 0x40; pkt[9] = 0x06; // TTL=64, TCP
        pkt[10] = 0x00; pkt[11] = 0x00;
        pkt[12] = REDIRECT_IP[0]; pkt[13] = REDIRECT_IP[1];
        pkt[14] = REDIRECT_IP[2]; pkt[15] = REDIRECT_IP[3];
        pkt[16] = dstIp[0]; pkt[17] = dstIp[1]; pkt[18] = dstIp[2]; pkt[19] = dstIp[3];
        int ipCs = ipChecksum(pkt, 0, 20);
        pkt[10] = (byte)((ipCs >> 8) & 0xFF); pkt[11] = (byte)(ipCs & 0xFF);

        // TCP header
        pkt[20] = 0x00; pkt[21] = 0x50; // src port = 80
        pkt[22] = (byte)((dstPort >> 8) & 0xFF); pkt[23] = (byte)(dstPort & 0xFF);
        pkt[24] = (byte)((seq >> 24) & 0xFF); pkt[25] = (byte)((seq >> 16) & 0xFF);
        pkt[26] = (byte)((seq >> 8)  & 0xFF); pkt[27] = (byte)(seq & 0xFF);
        pkt[28] = (byte)((ack >> 24) & 0xFF); pkt[29] = (byte)((ack >> 16) & 0xFF);
        pkt[30] = (byte)((ack >> 8)  & 0xFF); pkt[31] = (byte)(ack & 0xFF);
        pkt[32] = 0x50; // data offset = 5 (20 bytes header)
        pkt[33] = (byte)(flags & 0xFF);
        pkt[34] = (byte)0xFF; pkt[35] = (byte)0xFF; // window = 65535
        pkt[36] = 0x00; pkt[37] = 0x00; // checksum placeholder
        pkt[38] = 0x00; pkt[39] = 0x00; // urgent pointer
        if (data != null) System.arraycopy(data, 0, pkt, 40, dataLen);

        int tcpCs = tcpChecksum(pkt, tcpLen);
        pkt[36] = (byte)((tcpCs >> 8) & 0xFF); pkt[37] = (byte)(tcpCs & 0xFF);

        out.write(pkt);
        out.flush();
    }

    private int tcpChecksum(byte[] packet, int tcpLen) {
        // TCP pseudo-header: src IP + dst IP + 0x00 + protocol(6) + TCP length
        byte[] pseudo = new byte[12 + tcpLen];
        System.arraycopy(packet, 12, pseudo, 0, 4); // src IP
        System.arraycopy(packet, 16, pseudo, 4, 4); // dst IP
        pseudo[8] = 0; pseudo[9] = 0x06;
        pseudo[10] = (byte)((tcpLen >> 8) & 0xFF);
        pseudo[11] = (byte)(tcpLen & 0xFF);
        System.arraycopy(packet, 20, pseudo, 12, tcpLen);
        return ipChecksum(pseudo, 0, pseudo.length);
    }

    private static int toInt16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off+1] & 0xFF);
    }

    private static int toInt32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off+1] & 0xFF) << 16) |
               ((b[off+2] & 0xFF) << 8)  |  (b[off+3] & 0xFF);
    }

    // ── DNS upstream forward ──────────────────────────────────────────────────

    private byte[] forwardToUpstream(byte[] dnsPayload) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            protect(socket);
            socket.setSoTimeout(3000);
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
            return null;
        } finally {
            if (socket != null && !socket.isClosed()) socket.close();
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
