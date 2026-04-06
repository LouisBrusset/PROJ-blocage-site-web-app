package com.blockapp;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * Reçoit les actions des boutons dans la notification Wi-Fi public.
 * - TRUST_WIFI  : ajoute le SSID à la liste de confiance et ferme la notif
 * - DISMISS_WIFI: ferme simplement la notif
 */
public class WifiNotificationReceiver extends BroadcastReceiver {

    private static final String TAG = "WifiNotifReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Received action: " + action);

        if ("TRUST_WIFI".equals(action)) {
            String ssid = intent.getStringExtra("ssid");
            if (ssid != null && !ssid.isEmpty()) {
                SharedPreferences prefs = context.getSharedPreferences(
                    WifiMonitorService.PREFS_NAME, Context.MODE_PRIVATE);
                Set<String> trusted = new HashSet<>(
                    prefs.getStringSet(WifiMonitorService.KEY_TRUSTED, new HashSet<>()));
                trusted.add(ssid.toLowerCase().trim());
                prefs.edit().putStringSet(WifiMonitorService.KEY_TRUSTED, trusted).apply();
                Log.d(TAG, "Added to trusted: " + ssid);
            }
        }

        // Annule la notification dans les deux cas
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(WifiMonitorService.WARNING_NOTIF_ID);
    }
}
