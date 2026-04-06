package com.blockapp;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;

public class VpnModule extends ReactContextBaseJavaModule {

    private static final int VPN_REQUEST_CODE = 1001;
    private Promise vpnPermissionPromise;

    public VpnModule(ReactApplicationContext context) {
        super(context);
        context.addActivityEventListener(activityEventListener);
    }

    @NonNull
    @Override
    public String getName() {
        return "VpnModule";
    }

    @ReactMethod
    public void startVpn(Promise promise) {
        Activity activity = getCurrentActivity();
        if (activity == null) {
            promise.reject("NO_ACTIVITY", "No activity available");
            return;
        }

        // Check if VPN permission is needed
        Intent prepare = VpnService.prepare(getReactApplicationContext());
        if (prepare != null) {
            // Need to ask for permission
            vpnPermissionPromise = promise;
            activity.startActivityForResult(prepare, VPN_REQUEST_CODE);
        } else {
            // Permission already granted
            launchVpnService(promise);
        }
    }

    @ReactMethod
    public void stopVpn(Promise promise) {
        try {
            Intent stopIntent = new Intent(getReactApplicationContext(), VpnBlockService.class);
            stopIntent.setAction("STOP");
            getReactApplicationContext().startService(stopIntent);
            promise.resolve(null);
        } catch (Exception e) {
            promise.reject("STOP_FAILED", e.getMessage());
        }
    }

    @ReactMethod
    public void isVpnRunning(Promise promise) {
        try {
            // Détection fiable via ConnectivityManager : cherche un réseau actif de type VPN
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                getReactApplicationContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            android.net.Network[] networks = cm.getAllNetworks();
            for (android.net.Network network : networks) {
                android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(
                        android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                    promise.resolve(true);
                    return;
                }
            }
            promise.resolve(false);
        } catch (Exception e) {
            // Repli sur le flag statique si ConnectivityManager échoue
            promise.resolve(VpnBlockService.isRunning());
        }
    }

    private void launchVpnService(Promise promise) {
        try {
            Intent intent = new Intent(getReactApplicationContext(), VpnBlockService.class);
            // Pass backend URL from AsyncStorage — for now uses default; can be extended
            getReactApplicationContext().startForegroundService(intent);
            promise.resolve(null);
        } catch (Exception e) {
            promise.reject("START_FAILED", e.getMessage());
        }
    }

    private final ActivityEventListener activityEventListener = new BaseActivityEventListener() {
        @Override
        public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
            if (requestCode == VPN_REQUEST_CODE && vpnPermissionPromise != null) {
                if (resultCode == Activity.RESULT_OK) {
                    launchVpnService(vpnPermissionPromise);
                } else {
                    vpnPermissionPromise.reject("PERMISSION_DENIED", "User denied VPN permission");
                }
                vpnPermissionPromise = null;
            }
        }
    };
}
