package com.blockapp;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared SQLite helper used by both DatabaseModule (RN bridge) and VpnBlockService.
 */
public class BlockerDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "blocker.db";
    private static final int DB_VERSION = 1;

    private static BlockerDatabase instance;

    public static synchronized BlockerDatabase getInstance(Context context) {
        if (instance == null) {
            instance = new BlockerDatabase(context.getApplicationContext());
        }
        return instance;
    }

    private BlockerDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS blocked_url (" +
            "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  url TEXT NOT NULL," +
            "  description TEXT," +
            "  is_active INTEGER NOT NULL DEFAULT 1," +
            "  group_id INTEGER" +
            ")"
        );
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS group_entry (" +
            "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  name TEXT NOT NULL," +
            "  color TEXT NOT NULL DEFAULT '#6200EE'," +
            "  is_active INTEGER NOT NULL DEFAULT 1" +
            ")"
        );
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS settings (" +
            "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  pin_hash TEXT" +
            ")"
        );
        db.execSQL("INSERT INTO settings (pin_hash) VALUES (NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    // ─── URL model ─────────────────────────────────────────────────────────────

    public static class UrlEntry {
        public int id;
        public String url;
        public String description;
        public boolean isActive;
        public Integer groupId;
    }

    // ─── URL CRUD ──────────────────────────────────────────────────────────────

    public List<UrlEntry> getUrls() {
        List<UrlEntry> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, url, description, is_active, group_id FROM blocked_url", null)) {
            while (c.moveToNext()) list.add(rowToUrl(c));
        }
        return list;
    }

    public UrlEntry getUrl(int id) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, url, description, is_active, group_id FROM blocked_url WHERE id = ?",
                new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? rowToUrl(c) : null;
        }
    }

    public UrlEntry createUrl(String url, String description, Integer groupId) {
        ContentValues cv = new ContentValues();
        cv.put("url", url);
        if (description != null) cv.put("description", description); else cv.putNull("description");
        cv.put("is_active", 1);
        if (groupId != null) cv.put("group_id", groupId); else cv.putNull("group_id");
        long id = getWritableDatabase().insert("blocked_url", null, cv);
        return getUrl((int) id);
    }

    public UrlEntry updateUrl(int id, String url, String description,
                               Boolean isActive, Integer groupId, boolean clearGroupId) {
        ContentValues cv = new ContentValues();
        if (url != null) cv.put("url", url);
        if (description != null) cv.put("description", description);
        if (isActive != null) cv.put("is_active", isActive ? 1 : 0);
        if (clearGroupId) cv.putNull("group_id");
        else if (groupId != null) cv.put("group_id", groupId);
        if (cv.size() > 0)
            getWritableDatabase().update("blocked_url", cv, "id = ?",
                                         new String[]{String.valueOf(id)});
        return getUrl(id);
    }

    public void deleteUrl(int id) {
        getWritableDatabase().delete("blocked_url", "id = ?", new String[]{String.valueOf(id)});
    }

    public UrlEntry toggleUrl(int id) {
        getWritableDatabase().execSQL(
            "UPDATE blocked_url SET is_active = CASE WHEN is_active=1 THEN 0 ELSE 1 END WHERE id=?",
            new Object[]{id});
        return getUrl(id);
    }

    /** Returns active domain strings — used by VpnBlockService. */
    public List<String> getActiveBlockedDomains() {
        List<String> domains = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT url FROM blocked_url WHERE is_active = 1", null)) {
            while (c.moveToNext()) domains.add(c.getString(0).toLowerCase());
        }
        return domains;
    }

    private static UrlEntry rowToUrl(Cursor c) {
        UrlEntry e = new UrlEntry();
        e.id = c.getInt(0);
        e.url = c.getString(1);
        e.description = c.isNull(2) ? null : c.getString(2);
        e.isActive = c.getInt(3) == 1;
        e.groupId = c.isNull(4) ? null : c.getInt(4);
        return e;
    }

    // ─── Group model ───────────────────────────────────────────────────────────

    public static class GroupEntry {
        public int id;
        public String name;
        public String color;
        public boolean isActive;
    }

    // ─── Group CRUD ────────────────────────────────────────────────────────────

    public List<GroupEntry> getGroups() {
        List<GroupEntry> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, name, color, is_active FROM group_entry", null)) {
            while (c.moveToNext()) list.add(rowToGroup(c));
        }
        return list;
    }

    public GroupEntry getGroup(int id) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, name, color, is_active FROM group_entry WHERE id = ?",
                new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? rowToGroup(c) : null;
        }
    }

    public GroupEntry createGroup(String name, String color) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("color", color);
        cv.put("is_active", 1);
        long id = getWritableDatabase().insert("group_entry", null, cv);
        return getGroup((int) id);
    }

    public GroupEntry updateGroup(int id, String name, String color, Boolean isActive) {
        ContentValues cv = new ContentValues();
        if (name != null) cv.put("name", name);
        if (color != null) cv.put("color", color);
        if (isActive != null) cv.put("is_active", isActive ? 1 : 0);
        if (cv.size() > 0)
            getWritableDatabase().update("group_entry", cv, "id = ?",
                                         new String[]{String.valueOf(id)});
        return getGroup(id);
    }

    public void deleteGroup(int id) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.putNull("group_id");
        db.update("blocked_url", cv, "group_id = ?", new String[]{String.valueOf(id)});
        db.delete("group_entry", "id = ?", new String[]{String.valueOf(id)});
    }

    public GroupEntry toggleGroup(int id) {
        GroupEntry group = getGroup(id);
        if (group == null) return null;
        int newState = group.isActive ? 0 : 1;
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE group_entry SET is_active = ? WHERE id = ?",
                   new Object[]{newState, id});
        db.execSQL("UPDATE blocked_url SET is_active = ? WHERE group_id = ?",
                   new Object[]{newState, id});
        return getGroup(id);
    }

    private static GroupEntry rowToGroup(Cursor c) {
        GroupEntry g = new GroupEntry();
        g.id = c.getInt(0);
        g.name = c.getString(1);
        g.color = c.getString(2);
        g.isActive = c.getInt(3) == 1;
        return g;
    }

    // ─── PIN / Settings ────────────────────────────────────────────────────────

    public String getPinHash() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT pin_hash FROM settings LIMIT 1", null)) {
            if (c.moveToFirst()) return c.isNull(0) ? null : c.getString(0);
        }
        return null;
    }

    public void setPinHash(String hash) {
        ContentValues cv = new ContentValues();
        if (hash != null) cv.put("pin_hash", hash); else cv.putNull("pin_hash");
        int rows = getWritableDatabase().update("settings", cv, null, null);
        if (rows == 0) getWritableDatabase().insert("settings", null, cv);
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }
}
