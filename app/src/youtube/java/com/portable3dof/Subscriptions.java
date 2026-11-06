package com.portable3dof;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Locally stored channel subscriptions (no Google account needed), plus import from a Google Takeout
 * "subscriptions.csv" or a NewPipe subscriptions export.
 */
public final class Subscriptions {

    public static final class Sub {
        public final String name, url, thumb;

        Sub(String name, String url, String thumb) {
            this.name = name == null ? "" : name;
            this.url = url;
            // imports stored JSON null, which optString reads back as the text "null"
            this.thumb = thumb == null || thumb.isEmpty() || "null".equals(thumb) ? null : thumb;
        }
    }

    private final Context ctx;
    private final List<Sub> subs = new ArrayList<>();
    private int lastUpdated = 0;

    public Subscriptions(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        load();
    }

    private SharedPreferences prefs() { return ctx.getSharedPreferences("youtube", Context.MODE_PRIVATE); }

    private void load() {
        subs.clear();
        try {
            JSONArray a = new JSONArray(prefs().getString("subs", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                subs.add(new Sub(o.optString("n"), o.getString("url"), o.optString("th", null)));
            }
        } catch (Exception ignored) { }
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Sub s : subs) a.put(new JSONObject().put("n", s.name).put("url", s.url).put("th", s.thumb));
            prefs().edit().putString("subs", a.toString()).apply();
        } catch (Exception ignored) { }
    }

    public synchronized List<Sub> all() { return new ArrayList<>(subs); }

    public synchronized int count() { return subs.size(); }

    public synchronized boolean has(String url) { return indexOf(url) >= 0; }

    private int indexOf(String url) {
        String k = key(url);
        for (int i = 0; i < subs.size(); i++) if (key(subs.get(i).url).equals(k)) return i;
        return -1;
    }

    public synchronized void add(String name, String url, String thumb) {
        if (url == null || url.isEmpty()) return;
        int i = indexOf(url);
        Sub s = new Sub(name, url, thumb);
        if (i >= 0) subs.set(i, s); else subs.add(s);
        sort();
        save();
    }

    /** Fill in a channel's avatar (resolved in the background for imported channels). */
    public synchronized void setThumb(String url, String thumb) {
        int i = indexOf(url);
        if (i < 0 || thumb == null || thumb.isEmpty()) return;
        Sub s = subs.get(i);
        subs.set(i, new Sub(s.name, s.url, thumb));
        save();
    }

    public synchronized void remove(String url) {
        int i = indexOf(url);
        if (i >= 0) { subs.remove(i); save(); }
    }

    private void sort() {
        Collections.sort(subs, new Comparator<Sub>() {
            @Override public int compare(Sub a, Sub b) { return a.name.compareToIgnoreCase(b.name); }
        });
    }

    /** Channel identity: the id (or handle) part of the URL, so http/https/www/mobile variants match. */
    static String key(String url) {
        String u = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        int i = u.indexOf("/channel/");
        if (i >= 0) return u.substring(i + 9);
        i = u.indexOf("/@");
        if (i >= 0) return u.substring(i + 1);
        i = u.indexOf("youtube.com/");
        return i >= 0 ? u.substring(i + 12) : u;
    }

    // ---- import -----------------------------------------------------------------------------------

    /** Subscription export files found on shared storage (Takeout CSV, NewPipe JSON), newest first. */
    public List<File> findFiles() {
        List<File> dirs = new ArrayList<>();
        File ext = Environment.getExternalStorageDirectory();
        List<File> roots = new ArrayList<>();
        roots.add(ext);
        File[] vols = new File("/storage").listFiles();
        if (vols != null) {
            for (File v : vols) {
                String n = v.getName();
                if (n.equals("self") || n.equals("emulated")) continue;
                roots.add(v);
            }
        }
        for (File r : roots) {
            dirs.add(r);
            File[] kids = r.listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                String n = k.getName().toLowerCase(Locale.ROOT);
                if (k.isDirectory() && (n.equals("download") || n.equals("downloads") || n.equals("documents")
                        || n.contains("subscri") || n.contains("newpipe") || n.contains("youtube"))) {
                    dirs.add(k);
                }
            }
        }
        List<File> out = new ArrayList<>();
        for (File d : dirs) {
            File[] list = d.listFiles();
            if (list == null) continue;
            boolean downloads = d.getName().toLowerCase(Locale.ROOT).startsWith("download");
            for (File f : list) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (!f.isFile()) continue;
                boolean data = n.endsWith(".csv") || n.endsWith(".json") || n.endsWith(".txt");
                boolean ok = (n.contains("subscri") && data)
                        || (n.contains("newpipe") && n.endsWith(".json"))
                        || (downloads && (n.endsWith(".csv") || n.endsWith(".json")));
                if (ok && !out.contains(f)) out.add(f);
            }
        }
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        return out;
    }

    /**
     * Imports one file. replace = true makes the stored list match the file exactly; otherwise channels are added
     * and renamed but nothing is removed. Returns a one-line result for the UI.
     */
    public String importFile(File f, boolean replace) {
        List<String[]> parsed;
        try {
            parsed = parse(f);
        } catch (Exception e) {
            return "Couldn't read " + f.getName() + ": " + e.getMessage();
        }
        if (parsed.isEmpty()) return "No YouTube channels found in " + f.getName();
        int added = 0, renamed = 0, removed = 0;
        synchronized (this) {
            List<Sub> next = replace ? new ArrayList<Sub>() : subs;
            for (String[] p : parsed) {
                String url = p[1].trim().replace("http://", "https://");
                String name = p[0] == null ? "" : p[0].trim();
                int oi = indexOf(url);
                Sub old = oi >= 0 ? subs.get(oi) : null;
                int ni = -1;
                if (replace) {
                    String k = key(url);
                    for (int i = 0; i < next.size(); i++) if (key(next.get(i).url).equals(k)) { ni = i; break; }
                } else ni = oi;
                if (ni >= 0 && replace) continue;                 // duplicate line in the file
                if (old != null) {
                    if (!name.isEmpty() && !name.equals(old.name)) renamed++;
                    Sub s = new Sub(name.isEmpty() ? old.name : name, old.url, old.thumb);
                    if (replace) next.add(s); else subs.set(oi, s);
                } else {
                    next.add(new Sub(name, url, null));
                    added++;
                }
            }
            if (replace) {
                for (Sub o : subs) {
                    String k = key(o.url);
                    boolean kept = false;
                    for (Sub s : next) if (key(s.url).equals(k)) { kept = true; break; }
                    if (!kept) removed++;
                }
                subs.clear();
                subs.addAll(next);
            }
            sort();
            save();
        }
        StringBuilder sb = new StringBuilder(replace ? "Replaced with " : "Merged ").append(replace ? "" : "")
                .append(replace ? parsedCount(subs.size()) : added + " new");
        if (!replace && renamed > 0) sb.append(", ").append(renamed).append(" renamed");
        if (replace) sb.append(" (").append(added).append(" added, ").append(removed).append(" removed, ")
                .append(renamed).append(" renamed)");
        sb.append(" from ").append(f.getName()).append(" - ").append(count()).append(" total");
        return sb.toString();
    }

    private static String parsedCount(int n) { return n + " channels"; }

    /** Parses a Takeout CSV, a NewPipe JSON export or a plain list of URLs into {name, url} pairs. */
    private List<String[]> parse(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0 && sb.length() < 8_000_000) sb.append(buf, 0, n);
        }
        String text = sb.toString();
        if (text.startsWith("﻿")) text = text.substring(1);
        List<String[]> parsed = new ArrayList<>();   // {name, url}
        String t = text.trim();
        if (t.startsWith("{") || t.startsWith("[")) {
            JSONArray arr;
            if (t.startsWith("[")) arr = new JSONArray(t);
            else {
                JSONObject o = new JSONObject(t);
                arr = o.optJSONArray("subscriptions");
            }
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String url = o.optString("url", "");
                    if (o.has("service_id") && o.optInt("service_id", 0) != 0) continue;   // NewPipe: 0 = YouTube
                    if (!url.contains("youtube.com")) continue;
                    parsed.add(new String[]{o.optString("name", ""), url});
                }
            }
        } else {
            String[] lines = text.split("\r?\n");
            int urlCol = -1, idCol = -1, nameCol = -1;
            for (int li = 0; li < lines.length; li++) {
                List<String> cols = csv(lines[li]);
                if (li == 0 && !lines[li].contains("youtube.com") && cols.size() > 1) {
                    for (int c = 0; c < cols.size(); c++) {
                        String h = cols.get(c).toLowerCase(Locale.ROOT);
                        if (h.contains("url")) urlCol = c;
                        else if (h.contains("id")) idCol = c;
                        else if (h.contains("title") || h.contains("name")) nameCol = c;
                    }
                    continue;
                }
                String url = null, name = "";
                if (urlCol >= 0 && urlCol < cols.size()) url = cols.get(urlCol);
                if ((url == null || url.isEmpty()) && idCol >= 0 && idCol < cols.size() && !cols.get(idCol).isEmpty()) {
                    url = "https://www.youtube.com/channel/" + cols.get(idCol);
                }
                if (nameCol >= 0 && nameCol < cols.size()) name = cols.get(nameCol);
                if (url == null) {
                    for (String c : cols) if (c.contains("youtube.com/")) { url = c.trim(); break; }
                }
                if (url != null && url.contains("youtube.com/")) parsed.add(new String[]{name, url});
            }
        }
        return parsed;
    }

    /** Minimal CSV line splitter (quoted fields, doubled quotes). */
    private static List<String> csv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else q = false;
                } else cur.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == ',') { out.add(cur.toString().trim()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString().trim());
        return out;
    }
}
