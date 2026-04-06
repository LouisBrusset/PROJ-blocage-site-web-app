package com.blockapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WifiMonitorService extends android.app.Service {

    private static final String TAG = "WifiMonitorService";
    static final String PREFS_NAME = "WifiMonitorPrefs";
    static final String KEY_TRUSTED = "trusted_networks";
    private static final String MONITOR_CHANNEL = "wifi_monitor_channel";
    static final String WARNING_CHANNEL = "wifi_warning_channel";
    private static final int MONITOR_NOTIF_ID = 2;
    static final int WARNING_NOTIF_ID = 3;

    // SSIDs contenant ces mots = probablement public
    // Note : "wifi" et "open" exclus volontairement (trop génériques : "MonWifi", "OpenSpace")
    private static final Set<String> PUBLIC_KEYWORDS = new HashSet<>(Arrays.asList(
        "freewifi", "guest", "public", "hotspot",
        "airport", "aeroport", "gare",
        "hotel", "cafe", "coffee", "restaurant",
        "starbucks", "mcdo", "mcdonalds",
        "sncf", "tgv", "inoui", "ouifi",
        "laposte", "biblio", "mediatheque", "mairie"
    ));

    private ConnectivityManager.NetworkCallback networkCallback;
    // SSID pour lequel on a déjà envoyé une notif (évite le spam)
    private static String lastNotifiedSsid = null;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        createNotificationChannels();
        startForegroundCompat();
        registerNetworkCallback();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (networkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            try { cm.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── NetworkCallback ───────────────────────────────────────────────────────

    private void registerNetworkCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkRequest request = new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                // onCapabilitiesChanged will follow immediately; use it for SSID on Q+
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    new Thread(() -> handleSsid(getSsidLegacy())).start();
                }
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiInfo info = (WifiInfo) caps.getTransportInfo();
                    if (info != null) {
                        new Thread(() -> handleSsid(extractSsid(info.getSSID()))).start();
                    }
                }
            }

            @Override
            public void onLost(Network network) {
                lastNotifiedSsid = null;
                cancelWarningNotification();
            }
        };

        cm.registerNetworkCallback(request, networkCallback);
    }

    // ── SSID helpers ──────────────────────────────────────────────────────────

    private String getSsidLegacy() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null) return null;
            WifiInfo info = wm.getConnectionInfo();
            return info != null ? extractSsid(info.getSSID()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractSsid(String raw) {
        if (raw == null || raw.equals("<unknown ssid>") || raw.isEmpty()) return null;
        if (raw.startsWith("\"") && raw.endsWith("\"")) raw = raw.substring(1, raw.length() - 1);
        return raw.trim().isEmpty() ? null : raw.trim();
    }

    // ── Main logic ────────────────────────────────────────────────────────────

    private void handleSsid(String ssid) {
        if (ssid == null) return;
        if (ssid.equals(lastNotifiedSsid)) return; // déjà notifié pour ce réseau

        if (isTrusted(ssid)) return; // réseau de confiance

        boolean isOpen = isOpenNetwork(ssid);
        boolean looksPublic = containsPublicKeyword(ssid);

        lastNotifiedSsid = ssid;
        sendWarningNotification(ssid, isOpen, looksPublic);
    }

    /** Vérifie si le réseau est ouvert (sans WPA/WEP) via les résultats de scan */
    private boolean isOpenNetwork(String ssid) {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null) return false;
            List<ScanResult> results = wm.getScanResults();
            if (results == null) return false;
            for (ScanResult r : results) {
                if (ssid.equalsIgnoreCase(r.SSID)) {
                    String caps = r.capabilities != null ? r.capabilities.toUpperCase() : "";
                    return !caps.contains("WPA") && !caps.contains("WEP") && !caps.contains("PSK");
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** SSID contient un mot-clé typique d'un réseau public */
    private boolean containsPublicKeyword(String ssid) {
        String normalized = ssid.toLowerCase().replaceAll("[^a-z0-9]", "");
        for (String kw : PUBLIC_KEYWORDS) {
            if (normalized.contains(kw.replaceAll("[^a-z0-9]", ""))) return true;
        }
        return false;
    }

    // ── Trusted list ──────────────────────────────────────────────────────────

    boolean isTrusted(String ssid) {
        Set<String> trusted = getTrustedNetworks();
        return trusted.contains(ssid.toLowerCase().trim());
    }

    Set<String> getTrustedNetworks() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return new HashSet<>(prefs.getStringSet(KEY_TRUSTED, new HashSet<>()));
    }

    void addTrustedNetwork(String ssid) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Set<String> trusted = getTrustedNetworks();
        trusted.add(ssid.toLowerCase().trim());
        prefs.edit().putStringSet(KEY_TRUSTED, trusted).apply();
        lastNotifiedSsid = null;
        cancelWarningNotification();
    }

    // ── Notification ──────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private void sendWarningNotification(String ssid, boolean isOpen, boolean looksPublic) {
        NotificationManager nm = getSystemService(NotificationManager.class);

        Intent trustIntent = new Intent(this, WifiNotificationReceiver.class);
        trustIntent.setAction("TRUST_WIFI");
        trustIntent.putExtra("ssid", ssid);
        PendingIntent trustPi = PendingIntent.getBroadcast(this, 10, trustIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent dismissIntent = new Intent(this, WifiNotificationReceiver.class);
        dismissIntent.setAction("DISMISS_WIFI");
        PendingIntent dismissPi = PendingIntent.getBroadcast(this, 11, dismissIntent,
            PendingIntent.FLAG_IMMUTABLE);

        String title = "⚠\uFE0F Wi-Fi inconnu : \u00ab" + ssid + "\u00bb";
        StringBuilder body = new StringBuilder();
        if (isOpen) body.append("Réseau ouvert (sans mot de passe). ");
        if (looksPublic) body.append("SSID typique d'un réseau public. ");
        body.append("\nTon DNS sécurisé Chrome est désactivé : tes requêtes DNS ne sont pas chiffrées sur ce réseau.");

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, WARNING_CHANNEL);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle(title)
            .setContentText(body.toString())
            .setStyle(new Notification.BigTextStyle().bigText(body.toString()))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .addAction(0, "Réseau de confiance \u2714", trustPi)
            .addAction(0, "Ignorer", dismissPi);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_HIGH);
        }

        nm.notify(WARNING_NOTIF_ID, builder.build());
        Log.d(TAG, "Warning notification sent for SSID: " + ssid);
    }

    void cancelWarningNotification() {
        getSystemService(NotificationManager.class).cancel(WARNING_NOTIF_ID);
    }

    // ── Foreground notification (surveillance discrète) ───────────────────────

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);

            NotificationChannel monitor = new NotificationChannel(
                MONITOR_CHANNEL, "Surveillance Wi-Fi", NotificationManager.IMPORTANCE_MIN);
            monitor.setDescription("Service actif en arrière-plan");
            nm.createNotificationChannel(monitor);

            NotificationChannel warning = new NotificationChannel(
                WARNING_CHANNEL, "Alertes Wi-Fi public", NotificationManager.IMPORTANCE_HIGH);
            warning.setDescription("Alerte quand un Wi-Fi non reconnu est détecté");
            nm.createNotificationChannel(warning);
        }
    }

    private void startForegroundCompat() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, MONITOR_CHANNEL);
        } else {
            builder = new Notification.Builder(this);
        }
        Notification notif = builder
            .setContentTitle("BlockApp")
            .setContentText("Surveillance Wi-Fi active")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(MONITOR_NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(MONITOR_NOTIF_ID, notif);
        }
    }
}
