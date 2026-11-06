package com.portable3dof;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Remembers where you stopped in each video (key = uri string; value = pos|dur|title|lastPlayed). */
public class ResumeStore {

    public static final class Item {
        public final Uri uri;
        public final String title;
        public final long positionMs, durationMs, lastPlayed;

        Item(Uri uri, String title, long positionMs, long durationMs, long lastPlayed) {
            this.uri = uri;
            this.title = title;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.lastPlayed = lastPlayed;
        }
    }

    private final SharedPreferences prefs;

    public ResumeStore(Context ctx) {
        prefs = ctx.getSharedPreferences("resume", Context.MODE_PRIVATE);
    }

    public long positionFor(Uri uri) {
        Item i = parse(uri.toString(), prefs.getString(uri.toString(), null));
        return i == null ? 0 : i.positionMs;
    }

    public void put(Uri uri, String title, long positionMs, long durationMs) {
        // treat the last 20 seconds (or 3%) as "finished"
        if (durationMs > 0 && positionMs > durationMs - Math.max(20000, durationMs * 3 / 100)) {
            markFinished(uri);
            return;
        }
        if (positionMs < 5000) return;
        prefs.edit().putString(uri.toString(), positionMs + "|" + durationMs + "|"
                + title.replace('|', ' ') + "|" + System.currentTimeMillis()).apply();
    }

    public void markFinished(Uri uri) {
        prefs.edit().remove(uri.toString()).apply();
    }

    /** Most recently played, unfinished items first. */
    public List<Item> recent(int max) {
        List<Item> out = new ArrayList<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            Item i = parse(e.getKey(), String.valueOf(e.getValue()));
            if (i != null) out.add(i);
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) { return Long.compare(b.lastPlayed, a.lastPlayed); }
        });
        return out.size() > max ? out.subList(0, max) : out;
    }

    private static Item parse(String key, String v) {
        if (v == null) return null;
        String[] p = v.split("\\|", 4);
        if (p.length < 4) return null;
        try {
            return new Item(Uri.parse(key), p[2], Long.parseLong(p[0]), Long.parseLong(p[1]), Long.parseLong(p[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
