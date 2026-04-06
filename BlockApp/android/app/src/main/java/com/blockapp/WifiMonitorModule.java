package com.blockapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableArray;

import java.util.HashSet;
import java.util.Set;

public class WifiMonitorModule extends ReactContextBaseJavaModule {

    public WifiMonitorModule(ReactApplicationContext context) {
        super(context);
    }

    @NonNull
    @Override
    public String getName() { return "WifiMonitorModule"; }

    private static final String KEY_MONITORING_ENABLED = "monitoring_enabled";

    private SharedPreferences prefs() {
        return getReactApplicationContext()
            .getSharedPreferences(WifiMonitorService.PREFS_NAME, android.content.Context.MODE_PRIVATE);
    }

    // ── Contrôle du service ───────────────────────────────────────────────────

    @ReactMethod
    public void startMonitoring(Promise promise) {
        try {
            Intent intent = new Intent(getReactApplicationContext(), WifiMonitorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getReactApplicationContext().startForegroundService(intent);
            } else {
                getReactApplicationContext().startService(intent);
            }
            prefs().edit().putBoolean(KEY_MONITORING_ENABLED, true).apply();
            promise.resolve(null);
        } catch (Exception e) {
            promise.reject("START_FAILED", e.getMessage());
        }
    }

    @ReactMethod
    public void stopMonitoring(Promise promise) {
        try {
            Intent intent = new Intent(getReactApplicationContext(), WifiMonitorService.class);
            intent.setAction("STOP");
            getReactApplicationContext().startService(intent);
            prefs().edit().putBoolean(KEY_MONITORING_ENABLED, false).apply();
            promise.resolve(null);
        } catch (Exception e) {
            promise.reject("STOP_FAILED", e.getMessage());
        }
    }

    /** Indique si la surveillance était activée lors de la dernière session */
    @ReactMethod
    public void isMonitoringEnabled(Promise promise) {
        promise.resolve(prefs().getBoolean(KEY_MONITORING_ENABLED, false));
    }

    // ── Réseaux de confiance ──────────────────────────────────────────────────

    @ReactMethod
    public void getTrustedNetworks(Promise promise) {
        Set<String> trusted = prefs().getStringSet(WifiMonitorService.KEY_TRUSTED, new HashSet<>());
        WritableArray arr = Arguments.createArray();
        for (String s : trusted) arr.pushString(s);
        promise.resolve(arr);
    }

    @ReactMethod
    public void addTrustedNetwork(String ssid, Promise promise) {
        Set<String> trusted = new HashSet<>(
            prefs().getStringSet(WifiMonitorService.KEY_TRUSTED, new HashSet<>()));
        trusted.add(ssid.toLowerCase().trim());
        prefs().edit().putStringSet(WifiMonitorService.KEY_TRUSTED, trusted).apply();
        promise.resolve(null);
    }

    @ReactMethod
    public void removeTrustedNetwork(String ssid, Promise promise) {
        Set<String> trusted = new HashSet<>(
            prefs().getStringSet(WifiMonitorService.KEY_TRUSTED, new HashSet<>()));
        trusted.remove(ssid.toLowerCase().trim());
        prefs().edit().putStringSet(WifiMonitorService.KEY_TRUSTED, trusted).apply();
        promise.resolve(null);
    }

    // ── Réseau courant ────────────────────────────────────────────────────────

    @ReactMethod
    public void getCurrentSsid(Promise promise) {
        try {
            WifiManager wm = (WifiManager) getReactApplicationContext()
                .getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
            if (wm == null) { promise.resolve(null); return; }
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) { promise.resolve(null); return; }
            String ssid = info.getSSID();
            if (ssid == null || ssid.equals("<unknown ssid>") || ssid.isEmpty()) {
                promise.resolve(null);
                return;
            }
            if (ssid.startsWith("\"") && ssid.endsWith("\"")) ssid = ssid.substring(1, ssid.length() - 1);
            promise.resolve(ssid.trim().isEmpty() ? null : ssid.trim());
        } catch (Exception e) {
            promise.resolve(null);
        }
    }
}
