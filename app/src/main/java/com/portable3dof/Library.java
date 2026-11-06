package com.portable3dof;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the browser shows: continue-watching, storage volumes (internal + USB) browsed as plain
 * folders, the MediaStore index ("All videos"), and stream URLs.
 */
public class Library {

    public static final int FOLDER = 0, VIDEO = 1, STREAM = 2, RESUME = 3, APP = 4, SETTINGS = 5, VOLUME = 6, ACTION = 7,
            HEADER = 8, SHELF = 9;

    /** App keys: the native YouTube client, and the browser's list of sites. */
    public static final String KEY_YOUTUBE = "yt:", KEY_WEB = "web:", KEY_MANAGE = "manage:";

    /** Folder keys: "dir:<path>" = real folder, "mediastore:" = indexed buckets, anything else = a bucket name. */
    public static final String KEY_MEDIASTORE = "mediastore:";
    public static final String KEY_DIR = "dir:";

    public static final class Entry {
        public final int type;
        public final String name;
        public final String sub;        // secondary line (duration, size, count...)
        public final Uri uri;
        public final String key;        // folder key / action id
        public final String path;       // file path when known (lets us find sidecar subtitles)
        public final long durationMs;
        public final long resumeMs;

        Entry(int type, String name, String sub, Uri uri, String key, String path, long durationMs, long resumeMs) {
            this.type = type;
            this.name = name;
            this.sub = sub;
            this.uri = uri;
            this.key = key;
            this.path = path;
            this.durationMs = durationMs;
            this.resumeMs = resumeMs;
        }
    }

    private final Context ctx;
    private final ResumeStore resume;
    private final List<Entry> streams = new ArrayList<>();
    private volatile Map<String, List<Entry>> buckets = new LinkedHashMap<>();
    private volatile boolean scanned = false;

    public Library(Context ctx, ResumeStore resume) {
        this.ctx = ctx.getApplicationContext();
        this.resume = resume;
    }

    public void addStream(String name, String url) {
        Uri u = Uri.parse(url);
        String host = u.getHost() == null ? url : u.getHost();
        streams.add(new Entry(STREAM, name, "Stream  ·  " + host, u, null, null, 0, 0));
    }

    // ---- permissions ------------------------------------------------------------------------

    public static String readPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    public boolean hasPermission() {
        return ctx.checkSelfPermission(readPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    /** "All files access": needed to browse USB drives and read sidecar subtitles. */
    public boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    public boolean isScanned() { return scanned; }

    // ---- MediaStore index -------------------------------------------------------------------

    /** Blocking; call from a background thread. */
    public void scan() {
        Map<String, List<Entry>> out = new LinkedHashMap<>();
        if (hasPermission()) {
            String[] proj = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.DATA};
            try (Cursor c = ctx.getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
                    null, null, MediaStore.Video.Media.DISPLAY_NAME + " COLLATE NOCASE ASC")) {
                while (c != null && c.moveToNext()) {
                    long id = c.getLong(0);
                    String name = c.getString(1);
                    long dur = c.getLong(2);
                    long size = c.getLong(3);
                    String bucket = c.getString(4);
                    String path = c.getString(5);
                    if (bucket == null) bucket = "Videos";
                    Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
                    List<Entry> l = out.get(bucket);
                    if (l == null) { l = new ArrayList<>(); out.put(bucket, l); }
                    l.add(new Entry(VIDEO, stripExt(name), meta(dur, size), uri, bucket, path, dur,
                            resume.positionFor(uri)));
                }
            } catch (RuntimeException e) {
                // permission revoked mid-scan etc. - show what we have
            }
        }
        buckets = out;
        scanned = true;
    }

    // ---- listings ---------------------------------------------------------------------------

    /**
     * The home screen: what you were watching first, then where to find something new, then setup.
     * HEADER entries are section titles and cannot be selected.
     */
    public List<Entry> root() {
        List<Entry> out = new ArrayList<>();
        if (!continueWatching().isEmpty()) {
            out.add(header("Continue watching"));
            out.add(new Entry(SHELF, "Continue watching", null, null, null, null, 0, 0));
        }
        out.add(header("Watch"));
        if (BuildConfig.YOUTUBE) out.add(new Entry(APP, "YouTube", "Search, subscriptions and channels", null, KEY_YOUTUBE, null, 0, 0));
        else out.add(new Entry(ACTION, "YouTube is not in this edition", "Build the YouTube edition yourself to get it. Press OK to see how.", null, "ytinfo", null, 0, 0));
        out.addAll(volumes());
        if (!hasPermission()) {
            out.add(new Entry(ACTION, "Allow video indexing", "Optional: lists all videos on the device by folder. Press OK.",
                    null, "videoperm", null, 0, 0));
        }
        if (!buckets.isEmpty()) {
            int n = 0;
            for (List<Entry> l : buckets.values()) n += l.size();
            out.add(new Entry(FOLDER, "All videos (indexed)", n + (n == 1 ? " video in " : " videos in ") + buckets.size() + (buckets.size() == 1 ? " folder" : " folders"),
                    null, KEY_MEDIASTORE, null, 0, 0));
        }
        out.add(new Entry(APP, "Browser", "Web search, Stremio, YouTube on the web", null, KEY_WEB, null, 0, 0));
        out.addAll(streams);
        out.add(header("Setup"));
        out.add(new Entry(SETTINGS, "Settings", BuildConfig.YOUTUBE ? "Screen, head tracking, air mouse, YouTube quality" : "Screen, head tracking, air mouse", null, null, null, 0, 0));
        return out;
    }

    /** What you were watching, most recent first (the home screen's shelf). */
    public List<Entry> continueWatching() { return continueWatching(12); }

    public List<Entry> continueWatching(int max) {
        List<Entry> out = new ArrayList<>();
        for (ResumeStore.Item i : resume.recent(max)) {
            boolean yt = "youtube".equals(i.uri.getScheme());
            if (yt && !BuildConfig.YOUTUBE) continue;       // left over from the YouTube edition: this one cannot play it
            String left = i.durationMs > 0 ? fmt(i.durationMs - i.positionMs) + " left" : "at " + fmt(i.positionMs);
            out.add(new Entry(RESUME, i.title, (yt ? "YouTube  \u00b7  " : "") + left, i.uri, null,
                    "file".equals(i.uri.getScheme()) ? i.uri.getPath() : null, i.durationMs, i.positionMs));
        }
        return out;
    }

    /** Remove one item from Continue watching (its resume point is forgotten). */
    public void forget(Uri uri) { if (uri != null) resume.markFinished(uri); }

    public void forgetAll() {
        for (ResumeStore.Item i : resume.recent(100000)) resume.markFinished(i.uri);
    }

    private static Entry header(String title) {
        return new Entry(HEADER, title, null, null, null, null, 0, 0);
    }

    /** Sites offered by the Browser entry. */
    private static List<Entry> sites() {
        List<Entry> out = new ArrayList<>();
        out.add(new Entry(APP, "Web search", "google.com", null, "https://www.google.com", null, 0, 0));
        out.add(new Entry(APP, "Stremio", "web.stremio.com  \u00b7  plain HTTP streams only", null,
                "https://web.stremio.com", null, 0, 0));
        out.add(new Entry(APP, "YouTube (web)", BuildConfig.YOUTUBE ? "youtube.com in the browser; the native YouTube is lighter" : "youtube.com in the browser", null,
                "https://www.youtube.com", null, 0, 0));
        return out;
    }

    public List<Entry> folder(String key) {
        if (KEY_WEB.equals(key)) return sites();
        if (KEY_MANAGE.equals(key)) {
            List<Entry> out = new ArrayList<>();
            List<Entry> items = continueWatching(500);
            if (!items.isEmpty()) {
                out.add(new Entry(ACTION, "Clear all", "Remove every item from Continue watching", null, "clearall",
                        null, 0, 0));
            } else {
                out.add(new Entry(ACTION, "Nothing to continue", "Videos you stop part-way through appear here",
                        null, "noop", null, 0, 0));
            }
            out.addAll(items);
            return out;
        }
        if (KEY_MEDIASTORE.equals(key)) {
            List<Entry> out = new ArrayList<>();
            List<String> names = new ArrayList<>(buckets.keySet());
            Collections.sort(names, new Comparator<String>() {
                @Override public int compare(String a, String b) { return a.compareToIgnoreCase(b); }
            });
            for (String n : names) {
                int count = buckets.get(n).size();
                out.add(new Entry(FOLDER, n, count + (count == 1 ? " video" : " videos"), null, n, null, 0, 0));
            }
            return out;
        }
        if (key != null && key.startsWith(KEY_DIR)) return listDir(new File(key.substring(KEY_DIR.length())));
        List<Entry> l = buckets.get(key);
        return l == null ? new ArrayList<Entry>() : l;
    }

    /** Mounted storage volumes as browsable entries: internal storage and each USB drive / SD card. */
    public List<Entry> volumes() {
        List<Entry> out = new ArrayList<>();
        StorageManager sm = ctx.getSystemService(StorageManager.class);
        if (sm == null) return out;
        for (StorageVolume v : sm.getStorageVolumes()) {
            String st = v.getState();
            if (!Environment.MEDIA_MOUNTED.equals(st) && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(st)) continue;
            File dir = Build.VERSION.SDK_INT >= 30 ? v.getDirectory() : null;
            if (dir == null) continue;
            boolean removable = v.isRemovable();
            String label = v.getDescription(ctx);
            String name = removable ? "USB / SD: " + label : "Internal storage";
            long total = dir.getTotalSpace(), free = dir.getFreeSpace();
            String sub = total > 0 ? String.format(Locale.US, "%s free of %s", gb(free), gb(total)) : dir.getPath();
            out.add(new Entry(VOLUME, name, sub, null, KEY_DIR + dir.getPath(), dir.getPath(), 0, 0));
        }
        return out;
    }

    /** Root paths of all mounted volumes (used for "up" navigation). */
    public Set<String> volumeRoots() {
        Set<String> s = new HashSet<>();
        for (Entry e : volumes()) s.add(e.path);
        return s;
    }

    private List<Entry> listDir(File dir) {
        List<Entry> out = new ArrayList<>();
        if (!hasAllFilesAccess()) {
            out.add(new Entry(ACTION, "Allow access to all files",
                    "Needed to browse USB drives and load subtitle files. Press OK.", null, "allfiles", null, 0, 0));
            return out;
        }
        File[] files = null;
        try { files = dir.listFiles(); } catch (SecurityException ignored) { }
        if (files == null) {
            out.add(new Entry(ACTION, "Can't read this folder", "The drive may have been removed.", null, "noop", null, 0, 0));
            return out;
        }
        List<File> dirs = new ArrayList<>(), vids = new ArrayList<>();
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (n.equals("Android") || n.equals("LOST.DIR") || n.equals("System Volume Information")) continue;
                dirs.add(f);
            } else if (isVideo(n)) {
                vids.add(f);
            }
        }
        Comparator<File> byName = new Comparator<File>() {
            @Override public int compare(File a, File b) { return natural(a.getName(), b.getName()); }
        };
        Collections.sort(dirs, byName);
        Collections.sort(vids, byName);
        for (File d : dirs) {
            out.add(new Entry(FOLDER, d.getName(), "", null, KEY_DIR + d.getPath(), d.getPath(), 0, 0));
        }
        for (File f : vids) {
            Uri u = Uri.fromFile(f);
            out.add(new Entry(VIDEO, stripExt(f.getName()), meta(0, f.length()), u, KEY_DIR + dir.getPath(),
                    f.getPath(), 0, resume.positionFor(u)));
        }
        if (out.isEmpty()) {
            out.add(new Entry(ACTION, "No videos in this folder", "", null, "noop", null, 0, 0));
        }
        return out;
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static boolean isVideo(String n) {
        String s = n.toLowerCase(Locale.US);
        for (String e : new String[]{".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".ts", ".m2ts", ".mpg", ".mpeg", ".3gp", ".wmv", ".flv"}) {
            if (s.endsWith(e)) return true;
        }
        return false;
    }

    private static String stripExt(String n) {
        if (n == null) return "";
        int d = n.lastIndexOf('.');
        return d > 0 ? n.substring(0, d) : n;
    }

    private static String gb(long bytes) {
        return String.format(Locale.US, "%.1f GB", bytes / 1073741824.0);
    }

    private static String meta(long durMs, long sizeBytes) {
        StringBuilder sb = new StringBuilder();
        if (durMs > 0) sb.append(fmt(durMs));
        if (sizeBytes > 0) {
            if (sb.length() > 0) sb.append("  ·  ");
            double mb = sizeBytes / 1048576.0;
            sb.append(mb >= 1024 ? String.format(Locale.US, "%.1f GB", mb / 1024) : String.format(Locale.US, "%.0f MB", mb));
        }
        return sb.toString();
    }

    public static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.US, "%d:%02d", m, sec);
    }

    /** Case-insensitive compare that orders embedded numbers numerically (Episode 2 before Episode 10). */
    static int natural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int c = Character.toLowerCase(ca) - Character.toLowerCase(cb);
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }
}
