package com.blockapp;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;

import java.util.List;

/**
 * React Native bridge: replaces the FastAPI HTTP backend.
 * All data lives in SQLite on-device via BlockerDatabase.
 */
public class DatabaseModule extends ReactContextBaseJavaModule {

    public DatabaseModule(ReactApplicationContext context) {
        super(context);
    }

    @NonNull
    @Override
    public String getName() {
        return "DatabaseModule";
    }

    private BlockerDatabase db() {
        return BlockerDatabase.getInstance(getReactApplicationContext());
    }

    // ─── URLs ──────────────────────────────────────────────────────────────────

    @ReactMethod
    public void getUrls(Promise promise) {
        try {
            WritableArray arr = Arguments.createArray();
            for (BlockerDatabase.UrlEntry e : db().getUrls()) arr.pushMap(urlToMap(e));
            promise.resolve(arr);
        } catch (Exception e) { promise.reject("DB_ERROR", e.getMessage()); }
    }

    @ReactMethod
    public void createUrl(ReadableMap data, Promise promise) {
        try {
            String url = data.getString("url");
            String desc = data.hasKey("description") && !data.isNull("description")
                          ? data.getString("description") : null;
            Integer groupId = data.hasKey("group_id") && !data.isNull("group_id")
                              ? data.getInt("group_id") : null;
            promise.resolve(urlToMap(db().createUrl(url, desc, groupId)));
        } catch (Exception e) { promise.reject("DB_ERROR", e.getMessage()); }
    }

    @ReactMethod
    public void updateUrl(int id, ReadableMap data, Promise promise) {
        try {
            String url  = data.hasKey("url") ? data.getString("url") : null;
            String desc = data.hasKey("description") && !data.isNull("description")
                          ? data.getString("description") : null;
            Boolean active = data.hasKey("is_active") ? data.getBoolean("is_active") : null;
            boolean clearGroup = data.hasKey("group_id") && data.isNull("group_id");
            Integer groupId = (!clearGroup && data.hasKey("group_id"))
                              ? data.getInt("group_id") : null;
            promise.resolve(urlToMap(db().updateUrl(id, url, desc, active, groupId, clearGroup)));
        } catch (Exception e) { promise.reject("DB_ERROR", e.getMessage()); }
    }

    @ReactMethod
    public void deleteUrl(int id, Promise promise) {
        try { db().deleteUrl(id); promise.resolve(null); }
        catch (Exception e) { promise.reject("DB_ERROR", e.getMessage()); }
    }

    @ReactMethod
    public void toggleUrl(int id, Promise promise) {
        try { promise.resolve(urlToMap(db().toggleUrl(id))); }
        catch (Exception e) { promise.reject("DB_ERROR", e.getMessage()); }
    }

    // ─── Groups ────────────────────────────────────────────────────────────────

    @ReactMethod
    public void getGroups(Promise promise) {
        try {
            WritableArray arr = Arguments.createArray();
            for (BlockerDatabase.GroupEntry g : db().getGroups()) arr.pushMap(groupToMap(g));
            promise.resolve(arr);
        } catch (Exception e) { promise.reject("DB_ERROR", e.getMessage()); }
    }

    @ReactMethod
    public void createGroup(ReadableMap data, Promise promise) {
        try {
            String name  = data.getString("name");
            String color = data.hasKey("color") ? data.getString("color") : "#6200EE";
            promise.resolve(groupToMap(db().createGroup(name, color)));
        } catch (Exception e) { promise.reject("DB_ERROR", e.getMessage()); }
    }

    @ReactMethod
    public void updateGroup(int id, ReadableMap data, Promise promise) {
        try {
            String name   = data.hasKey("name")     ? data.getString("name")    : null;
            String color  = data.hasKey("color")    ? data.getString("color")   : null;
            Boolean active = data.hasKey("is_active") ? data.getBoolean("is_active") : null;
            promise.resolve(groupToMap(db().updateGroup(id, name, color, active)));
        } catch (Exception e) { promise.reject("DB_ERROR", e.getMessage()); }
    }

    @ReactMethod
    public void deleteGroup(int id, Promise promise) {
        try { db().deleteGroup(id); promise.resolve(null); }
        catch (Exception e) { promise.reject("DB_ERROR", e.getMessage()); }
    }

    @ReactMethod
    public void toggleGroup(int id, Promise promise) {
        try { promise.resolve(groupToMap(db().toggleGroup(id))); }
        catch (Exception e) { promise.reject("DB_ERROR", e.getMessage()); }
    }

    // ─── PIN / Settings ────────────────────────────────────────────────────────

    @ReactMethod
    public void hasPin(Promise promise) {
        try {
            WritableMap m = Arguments.createMap();
            m.putBoolean("has_pin", db().getPinHash() != null);
            promise.resolve(m);
        } catch (Exception e) { promise.reject("DB_ERROR", e.getMessage()); }
    }

    @ReactMethod
    public void verifyPin(String pin, Promise promise) {
        try {
            String stored = db().getPinHash();
            WritableMap m = Arguments.createMap();
            m.putBoolean("valid", stored == null || stored.equals(BlockerDatabase.sha256(pin)));
            promise.resolve(m);
        } catch (Exception e) { promise.reject("DB_ERROR", e.getMessage()); }
    }

    @ReactMethod
    public void setPin(String newPin, String currentPin, Promise promise) {
        try {
            String stored = db().getPinHash();
            if (stored != null) {
                if (currentPin == null || !stored.equals(BlockerDatabase.sha256(currentPin))) {
                    promise.reject("WRONG_PIN", "Current PIN incorrect");
                    return;
                }
            }
            db().setPinHash(BlockerDatabase.sha256(newPin));
            WritableMap m = Arguments.createMap();
            m.putBoolean("ok", true);
            promise.resolve(m);
        } catch (Exception e) { promise.reject("DB_ERROR", e.getMessage()); }
    }

    @ReactMethod
    public void removePin(String pin, Promise promise) {
        try {
            String stored = db().getPinHash();
            if (stored != null && !stored.equals(BlockerDatabase.sha256(pin))) {
                promise.reject("WRONG_PIN", "PIN incorrect");
                return;
            }
            db().setPinHash(null);
            WritableMap m = Arguments.createMap();
            m.putBoolean("ok", true);
            promise.resolve(m);
        } catch (Exception e) { promise.reject("DB_ERROR", e.getMessage()); }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private static WritableMap urlToMap(BlockerDatabase.UrlEntry e) {
        WritableMap m = Arguments.createMap();
        m.putInt("id", e.id);
        m.putString("url", e.url);
        if (e.description != null) m.putString("description", e.description);
        else m.putNull("description");
        m.putBoolean("is_active", e.isActive);
        if (e.groupId != null) m.putInt("group_id", e.groupId);
        else m.putNull("group_id");
        return m;
    }

    private static WritableMap groupToMap(BlockerDatabase.GroupEntry g) {
        WritableMap m = Arguments.createMap();
        m.putInt("id", g.id);
        m.putString("name", g.name);
        m.putString("color", g.color);
        m.putBoolean("is_active", g.isActive);
        return m;
    }
}
