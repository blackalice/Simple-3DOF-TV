package com.portable3dof;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.util.LruCache;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;

/**
 * The native YouTube screen: search, subscriptions feed, channels, history and trending. Painted into the overlay
 * like the library; playback is handed to the host (ExoPlayer, see PlayerUi). Views are kept on a back stack so BACK
 * returns to exactly where you were.
 */
public class YouTubeUi implements OnScreenKeyboard.Listener {

    public interface Host { void playYoutube(String url, String title); }

    /** Item rows and the shorter section-title rows; the list runs from LIST_TOP to LIST_BOTTOM. */
    private static final int ROW_H = 124, HEADER_H = 64, TILES_H = 130, SHELF_H = 246, PAGE = 5, LIST_TOP = 236,
            LIST_BOTTOM = 990;
    /** List-row thumbnail size, the size thumbnails are decoded at (also used by shelf cards), and card layout. */
    private static final int TH_W = 192, TH_H = 108, DEC_W = 320, DEC_H = 180;
    private static final int CARD_W = 280, CARD_H = 158, CARD_STEP = 306, CARD_X0 = 110, CARDS_VISIBLE = 5;
    private static final int BG = Ui.BG, PANEL = Ui.PANEL, ROW_SEL = Ui.ROW_SEL, ACCENT = Ui.ACCENT, RED = Ui.RED,
            TEXT = Ui.TEXT, TEXT_DIM = Ui.TEXT_DIM;

    private static final int V_HOME = 0, V_RESULTS = 1, V_FEED = 2, V_CHANNELS = 3, V_CHANNEL = 4, V_IMPORT = 5,
            V_HISTORY = 6;
    private static final int T_SEARCH = 0, T_HEADER = 1, T_ITEM = 2, T_MORE = 3, T_NAV = 4, T_TILES = 5, T_SHELF = 6;
    /** Home tiles, left to right. */
    private static final int TILE_SEARCH = 0, TILE_FEED = 1, TILE_CHANNELS = 2;
    private static final int N_FEED = 1, N_CHANNELS = 2, N_IMPORT = 3, N_REFRESH = 4, N_SUBSCRIBE = 5, N_MODE = 6,
            N_CLEARHIST = 7;
    /** Rows of cards that end with an "Edit list" card. */
    private static final String SHELF_HISTORY = "Recently watched";
    private int armedIdx = -1, paintIdx = -1;
    private long armedUntil = 0;
    private static final int N_FILE = 100;      // + index into importFiles
    private static final long FEED_STALE_MS = 20 * 60 * 1000L;

    private static final class Row {
        final int type, id;
        final YouTubeClient.Item item;
        final String label, sub;
        /** T_SHELF: the cards; T_SHELF / T_TILES remember their column under {@link #label}. */
        List<YouTubeClient.Item> shelf;

        Row(int type, int id, YouTubeClient.Item item, String label, String sub) {
            this.type = type;
            this.id = id;
            this.item = item;
            this.label = label;
            this.sub = sub;
        }
    }

    /** Column (and first visible card) of each horizontal row, by row label, so they survive rebuilds. */
    private final java.util.Map<String, Integer> cols = new java.util.HashMap<>(), firsts = new java.util.HashMap<>();

    private boolean horizontal(Row r) { return r.type == T_TILES || r.type == T_SHELF; }

    private int colCount(Row r) {
        if (r.type == T_TILES) return 3;
        if (r.shelf == null) return 0;
        return r.shelf.size() + (SHELF_HISTORY.equals(r.label) ? 1 : 0);
    }

    private boolean armed(int idx) { return armedIdx == idx && System.currentTimeMillis() < armedUntil; }

    private int colOf(Row r) {
        Integer c = cols.get(r.label);
        int n = colCount(r);
        return c == null ? 0 : Math.max(0, Math.min(n - 1, c));
    }

    private int firstOf(Row r) {
        Integer f = firsts.get(r.label);
        int col = colOf(r), first = f == null ? 0 : f;
        if (col < first) first = col;
        if (col >= first + CARDS_VISIBLE) first = col - CARDS_VISIBLE + 1;
        firsts.put(r.label, first);
        return first;
    }

    private void moveCol(Row r, int d) {
        int n = colCount(r), c = colOf(r) + d;
        if (c < 0 || c >= n) return;            // no wrap
        cols.put(r.label, c);
        firstOf(r);
    }

    /** One screen of the UI; its data lives here so popping back restores it untouched. */
    private static final class Frame {
        final int view;
        String query = "", title = "", status = "", chanUrl, chanThumb;
        long chanSubs;
        final List<YouTubeClient.Item> items = new ArrayList<>();
        YouTubeClient.Results more;
        boolean loading, loadingMore, dead;
        int sel, scroll;

        Frame(int view) { this.view = view; }
    }

    private final Context ctx;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final ExecutorService feedExec = Executors.newFixedThreadPool(4);
    private final ExecutorService thumbExec = Executors.newFixedThreadPool(2);
    private final OnScreenKeyboard kb = new OnScreenKeyboard(true);
    // search box: recent searches when empty, YouTube's suggestions while typing; chipFocus >= 0 = a chip has focus
    private final List<String> recentSearches = new ArrayList<>();
    private List<String> suggestions = new ArrayList<>();
    private String suggestedFor = "";
    private int chipFocus = -1, chipFirst = 0;
    private final Runnable suggestTask = new Runnable() { @Override public void run() { fetchSuggestions(); } };
    // imported channels have no avatar: resolve the visible ones in the background
    private final ExecutorService avatarExec = Executors.newFixedThreadPool(2);
    // feed videos checked for being Shorts (id -> true/false), persisted; the feed's RSS does not say
    private final java.util.Map<String, Boolean> shortCache = new java.util.HashMap<>();
    private final ExecutorService shortsExec = Executors.newFixedThreadPool(6);
    private boolean shortsChecking = false;
    private final Set<String> avatarTried = new HashSet<>();
    private final Subscriptions subs;
    private final LruCache<String, Bitmap> thumbs = new LruCache<>(48);
    private final Set<String> thumbPending = new HashSet<>();

    private final List<Row> rows = new ArrayList<>();
    private final List<YouTubeClient.Item> history = new ArrayList<>();
    private List<YouTubeClient.Item> trending = new ArrayList<>();
    private final List<YouTubeClient.Item> feedItems = new ArrayList<>();
    private long feedTime = 0;
    private boolean feedBusy = false;
    private int feedDone = 0, feedTotal = 0;
    private String importMsg = "";
    private final List<java.io.File> importFiles = new ArrayList<>();
    private boolean importScanning = false, importReplace = true;

    private final Frame home = new Frame(V_HOME);
    private Frame cur = home;
    private final Deque<Frame> stack = new ArrayDeque<>();

    private String typed = "";
    private boolean active = false, trendingTried = false, typing = false;
    private int sel = 0, scroll = 0, thumbVersion = 0;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    { stroke.setStyle(Paint.Style.STROKE); }
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Rect src = new Rect();
    private final Path path = new Path();

    public YouTubeUi(Context ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
        this.subs = new Subscriptions(ctx);
        text.setTypeface(Typeface.SANS_SERIF);
        loadHistory();
        loadFeedCache();
        loadSearches();
        loadShortCache();
    }

    // ---- lifecycle --------------------------------------------------------------------------

    public void open() {
        active = true;
        loadHistory();
        rebuild();
        if (!trendingTried) loadTrending();
    }

    /** Back from playback: refresh history, keep the list and selection. */
    public void reshow() {
        active = true;
        loadHistory();
        rebuildKeepSel();
    }

    public void close() {
        active = false;
        kb.close();
        typing = false;
    }

    public boolean keyboardOpen() { return typing && kb.isOpen(); }

    /** Show a channel's page (from the player's "Go to channel"); BACK returns to where YouTube was. */
    public void showChannel(String url, String name) {
        if (url == null || url.isEmpty()) return;
        openChannel(url, name, null);
    }

    // ---- rows -------------------------------------------------------------------------------

    private void rebuildKeepSel() {
        int keep = sel, keepScroll = scroll;
        rebuild();
        sel = Math.min(keep, Math.max(0, rows.size() - 1));
        scroll = keepScroll;
        normalizeSel(1);
        clampScroll();
    }

    private void rebuild() {
        rows.clear();
        Frame f = cur;
        switch (f.view) {
            case V_HOME:
                rows.add(new Row(T_TILES, 0, null, "tiles", null));
                if (!history.isEmpty()) {
                    rows.add(new Row(T_HEADER, 0, null, "Recently watched", null));
                    addShelf("Recently watched", history.subList(0, Math.min(20, history.size())));
                }
                if (!trending.isEmpty()) {
                    rows.add(new Row(T_HEADER, 0, null, "Trending", null));
                    addShelf("Trending", trending.subList(0, Math.min(24, trending.size())));
                }
                break;
            case V_RESULTS:
                rows.add(new Row(T_SEARCH, 0, null, "Search: " + f.query, null));
                for (YouTubeClient.Item i : f.items) addItem(i);
                if (f.more != null && f.more.hasMore()) rows.add(new Row(T_MORE, 0, null, "Load more results", null));
                break;
            case V_FEED: {
                rows.add(new Row(T_NAV, N_REFRESH, null, feedBusy ? "Refreshing\u2026" : "Refresh feed",
                        feedBusy ? (feedDone >= feedTotal ? "Checking for Shorts\u2026" : feedDone + " of " + feedTotal + " channels")
                                : feedTime == 0 ? "Not loaded yet" : "Updated " + ago(feedTime)));
                int shown = 0;
                for (YouTubeClient.Item i : feedItems) {
                    if (shown >= 100) break;
                    if (Settings.hideShorts && isKnownShort(i)) continue;
                    addItem(i);
                    shown++;
                }
                break;
            }
            case V_CHANNELS:
                rows.add(new Row(T_NAV, N_IMPORT, null, "Import subscriptions from a file",
                        importMsg.isEmpty() ? "Choose a Google Takeout CSV or NewPipe export" : importMsg));
                List<Subscriptions.Sub> all = subs.all();
                if (all.isEmpty()) {
                    rows.add(new Row(T_HEADER, 0, null, "No channels yet", null));
                }
                for (Subscriptions.Sub s : all) addItem(YouTubeClient.Item.channel(s.name, s.url, s.thumb, 0));
                break;
            case V_IMPORT:
                rows.add(new Row(T_NAV, N_MODE, null,
                        importReplace ? "Mode: replace all channels" : "Mode: merge into existing",
                        importReplace ? "Channels that are not in the file are removed (OK to switch)"
                                : "Adds new channels and refreshes names; nothing is removed (OK to switch)"));
                for (int i = 0; i < importFiles.size(); i++) {
                    java.io.File file = importFiles.get(i);
                    String dir = file.getParent() == null ? "" : file.getParent().replace("/storage/emulated/0", "Internal");
                    rows.add(new Row(T_NAV, N_FILE + i, null, file.getName(),
                            dir + "  ·  " + (file.length() / 1024) + " KB  ·  " + ago(file.lastModified())));
                }
                if (importFiles.isEmpty() && !importScanning) {
                    rows.add(new Row(T_HEADER, 0, null, "No subscription files found", null));
                }
                break;
            case V_HISTORY:
                if (history.isEmpty()) {
                    rows.add(new Row(T_HEADER, 0, null, "Nothing watched yet", null));
                } else {
                    rows.add(new Row(T_NAV, N_CLEARHIST, null, "Clear all", "Remove every video from Recently watched"));
                    for (YouTubeClient.Item i : history) addItem(i);
                }
                break;
            case V_CHANNEL:
                if (f.chanUrl != null && !f.loading) {
                    boolean has = subs.has(f.chanUrl);
                    rows.add(new Row(T_NAV, N_SUBSCRIBE, null, has ? "Unsubscribe" : "Subscribe",
                            has ? "You are subscribed to " + f.title : "Add " + f.title + " to your subscriptions"));
                }
                for (YouTubeClient.Item i : f.items) addItem(i);
                if (f.more != null && f.more.hasMore()) rows.add(new Row(T_MORE, 0, null, "Load more videos", null));
                break;
            default:
                break;
        }
        if (sel >= rows.size()) sel = Math.max(0, rows.size() - 1);
        normalizeSel(1);
        clampScroll();
    }

    private void addItem(YouTubeClient.Item i) { rows.add(new Row(T_ITEM, 0, i, null, null)); }

    private void addShelf(String label, List<YouTubeClient.Item> items) {
        Row r = new Row(T_SHELF, 0, null, label, null);
        r.shelf = new ArrayList<>(items);
        rows.add(r);
    }

    // ---- navigation stack -------------------------------------------------------------------

    private void push(Frame f) {
        cur.sel = sel;
        cur.scroll = scroll;
        stack.push(cur);
        cur = f;
        sel = 0;
        scroll = 0;
        rebuild();
    }

    /** Pops to the previous frame; false when already at home. */
    private boolean pop() {
        if (stack.isEmpty()) return false;
        cur.dead = true;
        cur = stack.pop();
        sel = cur.sel;
        scroll = cur.scroll;
        rebuild();
        sel = Math.min(cur.sel, Math.max(0, rows.size() - 1));
        normalizeSel(1);
        clampScroll();
        return true;
    }

    private void touched(Frame f) {
        if (!f.dead && f == cur) rebuildKeepSel();
    }

    // ---- network actions --------------------------------------------------------------------

    private void search(final String q) {
        rememberSearch(q);
        final Frame f = new Frame(V_RESULTS);
        f.query = q;
        f.loading = true;
        if (cur.view == V_RESULTS) {         // a new search replaces the current results
            cur.dead = true;
            cur = f;
            sel = 0;
            scroll = 0;
            rebuild();
        } else {
            push(f);
        }
        net.execute(new Runnable() {
            @Override public void run() {
                try {
                    final YouTubeClient.Results r = YouTubeClient.search(q);
                    main.post(new Runnable() { @Override public void run() {
                        if (f.dead) return;
                        f.items.addAll(r.items);
                        f.more = r;
                        f.loading = false;
                        f.status = r.items.isEmpty() ? "No results" : "";
                        if (f == cur) { rebuild(); sel = f.items.isEmpty() ? 0 : 1; scroll = 0; normalizeSel(1); }
                    } });
                } catch (final Exception e) {
                    main.post(new Runnable() { @Override public void run() {
                        if (f.dead) return;
                        f.loading = false;
                        f.status = "Search failed: " + msg(e);
                        touched(f);
                    } });
                }
            }
        });
    }

    private void openChannel(final String url, String name, String thumb) {
        final Frame f = new Frame(V_CHANNEL);
        f.title = name == null || name.isEmpty() ? "Channel" : name;
        f.chanThumb = thumb;
        f.loading = true;
        push(f);
        net.execute(new Runnable() {
            @Override public void run() {
                try {
                    final YouTubeClient.Channel c = YouTubeClient.channel(url);
                    main.post(new Runnable() { @Override public void run() {
                        if (f.dead) return;
                        f.title = c.name;
                        f.chanUrl = c.url;
                        f.chanThumb = c.thumb != null ? c.thumb : f.chanThumb;
                        f.chanSubs = c.subs;
                        f.items.addAll(c.videos.items);
                        f.more = c.videos;
                        f.loading = false;
                        f.status = c.videos.items.isEmpty() ? "No videos" : "";
                        if (f == cur) { rebuild(); sel = rows.size() > 1 ? 1 : 0; scroll = 0; normalizeSel(1); }
                    } });
                } catch (final Exception e) {
                    main.post(new Runnable() { @Override public void run() {
                        if (f.dead) return;
                        f.loading = false;
                        f.status = "Couldn't open channel: " + msg(e);
                        touched(f);
                    } });
                }
            }
        });
    }

    private void loadMore() {
        final Frame f = cur;
        if (f.loadingMore || f.more == null || !f.more.hasMore()) return;
        f.loadingMore = true;
        final YouTubeClient.Results c = f.more;
        net.execute(new Runnable() {
            @Override public void run() {
                try {
                    final YouTubeClient.Results r = YouTubeClient.more(c);
                    main.post(new Runnable() { @Override public void run() {
                        f.loadingMore = false;
                        if (f.dead) return;
                        f.items.addAll(r.items);
                        f.more = r;
                        touched(f);
                    } });
                } catch (final Exception e) {
                    main.post(new Runnable() { @Override public void run() {
                        f.loadingMore = false;
                        f.status = "Couldn't load more: " + msg(e);
                        touched(f);
                    } });
                }
            }
        });
    }

    private void loadTrending() {
        trendingTried = true;
        net.execute(new Runnable() {
            @Override public void run() {
                try {
                    final List<YouTubeClient.Item> t = YouTubeClient.trending();
                    main.post(new Runnable() { @Override public void run() {
                        trending = t;
                        if (active && cur == home) rebuildKeepSel();
                    } });
                } catch (Exception ignored) {
                    // trending is optional; many regions no longer have it
                }
            }
        });
    }

    private static String msg(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m.length() > 90 ? m.substring(0, 90) : m;
    }

    // ---- subscriptions feed -----------------------------------------------------------------

    private void openFeed() {
        Frame f = new Frame(V_FEED);
        push(f);
        if (Settings.hideShorts && !feedBusy && !shortsChecking && !feedItems.isEmpty()) {
            checkShorts(new ArrayList<>(feedItems), new Runnable() { @Override public void run() {
                if (cur.view == V_FEED) rebuildKeepSel();
            } });
        }
        if (!feedBusy && (feedItems.isEmpty() || System.currentTimeMillis() - feedTime > FEED_STALE_MS)) {
            refreshFeed();
        }
    }

    private void refreshFeed() {
        if (feedBusy) return;
        final List<Subscriptions.Sub> list = subs.all();
        if (list.isEmpty()) return;
        feedBusy = true;
        feedDone = 0;
        feedTotal = list.size();
        final List<YouTubeClient.Item> acc = Collections.synchronizedList(new ArrayList<YouTubeClient.Item>());
        for (final Subscriptions.Sub s : list) {
            feedExec.execute(new Runnable() {
                @Override public void run() {
                    try {
                        List<YouTubeClient.Item> l = YouTubeClient.feed(s.url);
                        int n = 0;
                        for (YouTubeClient.Item i : l) {
                            if (n++ >= 6) break;
                            acc.add(i);
                        }
                    } catch (Exception ignored) {
                        // one broken channel must not stop the feed
                    }
                    main.post(new Runnable() { @Override public void run() {
                        feedDone++;
                        if (feedDone >= feedTotal) finishFeed(acc);
                        else if (cur.view == V_FEED) rebuildKeepSel();
                    } });
                }
            });
        }
    }

    private void finishFeed(List<YouTubeClient.Item> acc) {
        List<YouTubeClient.Item> all;
        synchronized (acc) { all = new ArrayList<>(acc); }
        Collections.sort(all, new Comparator<YouTubeClient.Item>() {
            @Override public int compare(YouTubeClient.Item a, YouTubeClient.Item b) {
                return Long.compare(b.uploadedMs, a.uploadedMs);
            }
        });
        Set<String> seen = new HashSet<>();
        final List<YouTubeClient.Item> out = new ArrayList<>();
        for (YouTubeClient.Item i : all) {
            if (out.size() >= 160) break;          // extra room: some will turn out to be Shorts
            if (seen.add(i.url)) out.add(i);
        }
        if (Settings.hideShorts) {
            if (cur.view == V_FEED) rebuildKeepSel();      // shows "Checking for Shorts..."
            checkShorts(out, new Runnable() { @Override public void run() { publishFeed(out); } });
        } else {
            publishFeed(out);
        }
    }

    private void publishFeed(List<YouTubeClient.Item> out) {
        feedBusy = false;
        if (!out.isEmpty()) {
            feedItems.clear();
            feedItems.addAll(out);
            feedTime = System.currentTimeMillis();
            saveFeedCache();
        }
        if (cur.view == V_FEED) rebuildKeepSel();
    }

    private boolean isKnownShort(YouTubeClient.Item i) {
        if (i.title != null && i.title.toLowerCase(Locale.ROOT).contains("#shorts")) return true;
        Boolean b = shortCache.get(YouTubeClient.videoId(i.url));
        return b != null && b;
    }

    /** Ask youtube.com about any feed videos not checked yet (in parallel), then run done on the main thread. */
    private void checkShorts(final List<YouTubeClient.Item> items, final Runnable done) {
        final List<String> todo = new ArrayList<>();
        for (YouTubeClient.Item i : items) {
            String id = YouTubeClient.videoId(i.url);
            if (id != null && !shortCache.containsKey(id) && !todo.contains(id)) todo.add(id);
        }
        if (todo.isEmpty()) { done.run(); return; }
        shortsChecking = true;
        new Thread(new Runnable() {
            @Override public void run() {
                final java.util.Map<String, Boolean> found = new java.util.concurrent.ConcurrentHashMap<>();
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(todo.size());
                for (final String id : todo) {
                    shortsExec.execute(new Runnable() {
                        @Override public void run() {
                            try {
                                Boolean b = YouTubeClient.isShort("https://www.youtube.com/watch?v=" + id);
                                if (b != null) found.put(id, b);
                            } finally {
                                latch.countDown();
                            }
                        }
                    });
                }
                try { latch.await(45, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                main.post(new Runnable() { @Override public void run() {
                    shortCache.putAll(found);
                    saveShortCache();
                    shortsChecking = false;
                    done.run();
                } });
            }
        }, "shorts-check").start();
    }

    private void loadShortCache() {
        try {
            JSONObject o = new JSONObject(prefs().getString("shorts", "{}"));
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) { String k = it.next(); shortCache.put(k, o.getBoolean(k)); }
        } catch (Exception ignored) { }
    }

    private void saveShortCache() {
        try {
            // keep it bounded: only ids still in the feed, plus what was just learned
            JSONObject o = new JSONObject();
            Set<String> keep = new HashSet<>();
            for (YouTubeClient.Item i : feedItems) keep.add(YouTubeClient.videoId(i.url));
            int n = 0;
            for (java.util.Map.Entry<String, Boolean> e : shortCache.entrySet()) {
                if (keep.contains(e.getKey()) || n < 400) { o.put(e.getKey(), e.getValue()); n++; }
            }
            prefs().edit().putString("shorts", o.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void loadFeedCache() {
        try {
            SharedPreferences p = prefs();
            feedTime = p.getLong("feedTime", 0);
            JSONArray a = new JSONArray(p.getString("feed", "[]"));
            for (int i = 0; i < a.length(); i++) feedItems.add(YouTubeClient.Item.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) { }
    }

    private void saveFeedCache() {
        try {
            JSONArray a = new JSONArray();
            for (YouTubeClient.Item i : feedItems) a.put(i.toJson());
            prefs().edit().putString("feed", a.toString()).putLong("feedTime", feedTime).apply();
        } catch (Exception ignored) { }
    }

    /** Opens the file picker and scans storage for subscription exports. */
    private void openImport() {
        final Frame f = new Frame(V_IMPORT);
        importFiles.clear();
        importScanning = true;
        push(f);
        net.execute(new Runnable() {
            @Override public void run() {
                final List<java.io.File> found = subs.findFiles();
                main.post(new Runnable() { @Override public void run() {
                    importScanning = false;
                    if (f.dead) return;
                    importFiles.clear();
                    importFiles.addAll(found);
                    rebuildKeepSel();
                    if (sel == 0 && rows.size() > 1) { sel = 1; clampScroll(); }   // start on the newest file
                } });
            }
        });
    }

    private void runImport(final java.io.File file) {
        final boolean replace = importReplace;
        importMsg = "Importing " + file.getName() + "…";
        pop();                                  // back to Channels; the result shows on the import row
        net.execute(new Runnable() {
            @Override public void run() {
                final String m = subs.importFile(file, replace);
                main.post(new Runnable() { @Override public void run() {
                    importMsg = m;
                    feedTime = 0;
                    if (replace) { feedItems.clear(); saveFeedCache(); }
                    if (cur.view == V_CHANNELS || cur.view == V_HOME) rebuildKeepSel();
                } });
            }
        });
    }

    // ---- history ----------------------------------------------------------------------------

    private SharedPreferences prefs() { return ctx.getSharedPreferences("youtube", Context.MODE_PRIVATE); }

    private void loadHistory() {
        history.clear();
        try {
            JSONArray a = new JSONArray(prefs().getString("history", "[]"));
            for (int i = 0; i < a.length(); i++) history.add(YouTubeClient.Item.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) { }
    }

    private void removeHistory(YouTubeClient.Item it) {
        for (int i = history.size() - 1; i >= 0; i--) if (history.get(i).url.equals(it.url)) history.remove(i);
        saveHistory();
    }

    private void addHistory(YouTubeClient.Item it) {
        for (int i = history.size() - 1; i >= 0; i--) if (history.get(i).url.equals(it.url)) history.remove(i);
        history.add(0, it);
        while (history.size() > 40) history.remove(history.size() - 1);
        saveHistory();
    }

    /**
     * Called by the player when a YouTube video actually starts, whichever screen launched it (the home
     * "Continue watching" shelf, "Go to channel", ...). Moves it to the front of Recently watched.
     */
    public void recordPlayed(YouTubeClient.Playable p) {
        if (p == null || p.id == null || p.id.isEmpty()) return;
        loadHistory();
        String url = "https://www.youtube.com/watch?v=" + p.id;
        YouTubeClient.Item it = null;
        for (YouTubeClient.Item h : history) if (url.equals(h.url)) { it = h; break; }
        if (it == null) {
            it = new YouTubeClient.Item(p.title, p.uploader, url, "https://i.ytimg.com/vi/" + p.id + "/mqdefault.jpg",
                    0, 0, p.live, p.uploaderUrl, 0, "", false);
        }
        addHistory(it);
        if (active) rebuildKeepSel();
    }

    private void saveHistory() {
        try {
            JSONArray a = new JSONArray();
            for (YouTubeClient.Item h : history) a.put(h.toJson());
            prefs().edit().putString("history", a.toString()).apply();
        } catch (Exception ignored) { }
    }

    // ---- thumbnails -------------------------------------------------------------------------

    private Bitmap thumb(final String url, final boolean avatar) {
        if (url == null) return null;
        final String key = (avatar ? "a:" : "t:") + url;
        Bitmap b = thumbs.get(key);
        if (b != null) return b;
        synchronized (thumbPending) {
            if (!thumbPending.add(key)) return null;
        }
        thumbExec.execute(new Runnable() {
            @Override public void run() {
                try {
                    Request rq = new Request.Builder().url(url).build();
                    try (Response r = YouTubeClient.http().client().newCall(rq).execute()) {
                        if (!r.isSuccessful() || r.body() == null) return;
                        byte[] data = r.body().bytes();
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(data, 0, data.length, o);
                        int s = 1;
                        while (o.outWidth / (s * 2) >= DEC_W) s *= 2;
                        o = new BitmapFactory.Options();
                        o.inSampleSize = s;
                        Bitmap full = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                        if (full == null) return;
                        int w = full.getWidth(), h = full.getHeight();
                        final Bitmap scaled;
                        if (avatar) {         // centre square
                            int sq = Math.min(w, h);
                            Bitmap crop = Bitmap.createBitmap(full, (w - sq) / 2, (h - sq) / 2, sq, sq);
                            scaled = Bitmap.createScaledBitmap(crop, DEC_H, DEC_H, true);
                        } else {              // the thumbnail may have letterbox bars: crop to 16:9 from the middle
                            int ch = Math.min(h, w * 9 / 16);
                            Bitmap crop = Bitmap.createBitmap(full, 0, (h - ch) / 2, w, ch);
                            scaled = Bitmap.createScaledBitmap(crop, DEC_W, DEC_H, true);
                        }
                        thumbs.put(key, scaled);
                        main.post(new Runnable() { @Override public void run() { thumbVersion++; } });
                    }
                } catch (Exception ignored) {
                } finally {
                    synchronized (thumbPending) { thumbPending.remove(key); }
                }
            }
        });
        return null;
    }

    /** For the home screen's Continue watching shelf (PlayerUi): a cached thumbnail, loading it if needed. */
    public Bitmap thumbFor(String url) { return thumb(url, false); }

    /** Changes whenever a thumbnail arrives (lets other screens repaint). */
    public int thumbVersion() { return thumbVersion; }

    /**
     * Queue a channel's avatar lookup. Newest requests go first (they are the rows on screen now; rows that
     * only flashed past while scrolling wait), two at a time.
     */
    private void requestAvatar(final String url) {
        if (url == null || url.isEmpty() || !avatarTried.add(url)) return;
        synchronized (avatarQueue) { avatarQueue.addLast(url); }
        for (int i = 0; i < 2; i++) avatarExec.execute(avatarWorker);
    }

    private final Deque<String> avatarQueue = new ArrayDeque<>();
    private final Runnable avatarWorker = new Runnable() {
        @Override public void run() {
            final String url;
            synchronized (avatarQueue) { url = avatarQueue.pollLast(); }
            if (url == null) return;
            final String th;
            try { th = YouTubeClient.channelAvatar(url); } catch (Exception e) { return; }
            if (th == null) return;
            subs.setThumb(url, th);
            main.post(new Runnable() { @Override public void run() {
                if (cur.view == V_CHANNELS) rebuildKeepSel();
                thumbVersion++;
            } });
        }
    };

    // ---- selection --------------------------------------------------------------------------

    private boolean selectable(int i) { return i >= 0 && i < rows.size() && rows.get(i).type != T_HEADER; }

    private void normalizeSel(int dir) {
        if (rows.isEmpty()) { sel = 0; return; }
        sel = Math.max(0, Math.min(rows.size() - 1, sel));
        int i = sel;
        while (i >= 0 && i < rows.size() && !selectable(i)) i += dir;
        if (i < 0 || i >= rows.size()) {
            i = sel;
            while (i >= 0 && i < rows.size() && !selectable(i)) i -= dir;
        }
        sel = Math.max(0, Math.min(rows.size() - 1, i));
    }

    private void moveSel(int d) {
        int step = d > 0 ? 1 : -1;
        int n = Math.max(0, Math.min(rows.size() - 1, sel + d));
        while (n >= 0 && n < rows.size() && !selectable(n)) n += step;
        if (n < 0 || n >= rows.size()) return;      // no wrap at the ends
        sel = n;
        clampScroll();
        if (sel >= rows.size() - 3) loadMore();
    }

    private int rowH(int i) {
        switch (rows.get(i).type) {
            case T_HEADER: return HEADER_H;
            case T_TILES: return TILES_H;
            case T_SHELF: return SHELF_H;
            default: return ROW_H;
        }
    }

    /** Keep the selection, and the section title just above it, on screen. */
    private void clampScroll() {
        if (rows.isEmpty()) { scroll = 0; return; }
        sel = Math.max(0, Math.min(rows.size() - 1, sel));
        scroll = Math.max(0, Math.min(scroll, rows.size() - 1));
        if (sel < scroll) scroll = sel;
        if (scroll == sel && sel > 0 && rows.get(sel - 1).type == T_HEADER) scroll = sel - 1;
        int h = 0;
        for (int i = scroll; i <= sel; i++) h += rowH(i);
        while (scroll < sel && h > LIST_BOTTOM - LIST_TOP) { h -= rowH(scroll); scroll++; }
    }

    // ---- keyboard callbacks -----------------------------------------------------------------

    @Override public void onText(String s) { typed += s; typedChanged(); }

    @Override public void onBackspace() {
        if (!typed.isEmpty()) typed = typed.substring(0, typed.length() - 1);
        typedChanged();
    }

    @Override public void onClear() { typed = ""; typedChanged(); }

    @Override
    public void onEnter() {
        String q = typed.trim();
        kb.close();
        typing = false;
        chipFocus = -1;
        if (!q.isEmpty()) search(q);
    }

    @Override public void onClose() { kb.close(); typing = false; chipFocus = -1; }

    private void startTyping() {
        typed = "";
        typing = true;
        chipFocus = -1;
        chipFirst = 0;
        suggestions = new ArrayList<>();
        suggestedFor = "";
        kb.open();
    }

    private void typedChanged() {
        chipFirst = 0;
        main.removeCallbacks(suggestTask);
        if (!typed.trim().isEmpty()) main.postDelayed(suggestTask, 300);     // after a short pause in typing
    }

    /** Recent searches while the box is empty, YouTube's suggestions once something is typed. */
    private List<String> chips() {
        if (typed.trim().isEmpty()) return recentSearches;
        return suggestions;
    }

    private void fetchSuggestions() {
        final String q = typed.trim();
        if (q.isEmpty() || q.equals(suggestedFor)) return;
        net.execute(new Runnable() {
            @Override public void run() {
                List<String> l;
                try { l = YouTubeClient.suggestions(q); } catch (Exception e) { return; }
                final List<String> res = l.size() > 10 ? new ArrayList<>(l.subList(0, 10)) : l;
                main.post(new Runnable() { @Override public void run() {
                    if (!typing || !q.equals(typed.trim())) return;      // typed on since
                    suggestions = res;
                    suggestedFor = q;
                    if (chipFocus >= suggestions.size()) chipFocus = suggestions.isEmpty() ? -1 : 0;
                    thumbVersion++;
                } });
            }
        });
    }

    private void loadSearches() {
        recentSearches.clear();
        try {
            JSONArray a = new JSONArray(prefs().getString("searches", "[]"));
            for (int i = 0; i < a.length(); i++) recentSearches.add(a.getString(i));
        } catch (Exception ignored) { }
    }

    private void rememberSearch(String q) {
        for (int i = recentSearches.size() - 1; i >= 0; i--) {
            if (recentSearches.get(i).equalsIgnoreCase(q)) recentSearches.remove(i);
        }
        recentSearches.add(0, q);
        while (recentSearches.size() > 12) recentSearches.remove(recentSearches.size() - 1);
        JSONArray a = new JSONArray();
        for (String s : recentSearches) a.put(s);
        prefs().edit().putString("searches", a.toString()).apply();
    }

    private void searchChip(int i) {
        List<String> l = chips();
        if (i < 0 || i >= l.size()) return;
        typed = l.get(i);
        onEnter();
    }

    // ---- input ------------------------------------------------------------------------------

    /** Returns true if consumed; false for BACK when already at the YouTube home (so the caller leaves). */
    public boolean onKey(int k, int repeat) {
        if (kb.isOpen() && chipFocus >= 0) {       // focus on the recent / suggested searches above the keys
            int n = chips().size();
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_LEFT: if (chipFocus > 0) chipFocus--; return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: if (chipFocus < n - 1) chipFocus++; return true;
                case KeyEvent.KEYCODE_DPAD_UP: return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: chipFocus = -1; return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A:
                    searchChip(chipFocus);
                    return true;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                    onClose();
                    return true;
                default:
                    return false;
            }
        }
        if (kb.isOpen()) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_LEFT: kb.left(); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: kb.right(); return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (kb.atTop() && !chips().isEmpty()) chipFocus = Math.min(chipFirst, chips().size() - 1);
                    else kb.up();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: kb.down(); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A:
                    kb.pressSelected(this);
                    return true;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                    onClose();
                    return true;
                default:
                    return false;
            }
        }
        if (!(k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER
                || k == KeyEvent.KEYCODE_BUTTON_A)) armedIdx = -1;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: moveSel(-1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: moveSel(1); return true;
            case KeyEvent.KEYCODE_PAGE_UP: moveSel(-PAGE); return true;
            case KeyEvent.KEYCODE_PAGE_DOWN: moveSel(PAGE); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                activate();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_LEFT: {
                int d = k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                Row r = sel >= 0 && sel < rows.size() ? rows.get(sel) : null;
                if (r != null && horizontal(r)) moveCol(r, d);                         // tiles / shelf
                else if (cur.view == V_CHANNELS && r != null && r.type == T_ITEM) jumpLetter(d);   // A-Z
                else if (d > 0) openSelectedChannel();
                else pop();                 // Left at home does nothing
                return true;
            }
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                return pop();               // false at home so the caller leaves YouTube
            default:
                return false;
        }
    }

    /** Channels list: Left / Right jump to the previous / next first letter. */
    private void jumpLetter(int d) {
        char curL = letterAt(sel);
        if (d > 0) {
            for (int i = sel + 1; i < rows.size(); i++) {
                if (rows.get(i).type == T_ITEM && letterAt(i) != curL) { sel = i; clampScroll(); return; }
            }
        } else {
            int start = sel;
            while (start - 1 >= 0 && rows.get(start - 1).type == T_ITEM && letterAt(start - 1) == curL) start--;
            if (start < sel) { sel = start; clampScroll(); return; }      // first go to the top of this letter
            int i = start - 1;
            if (i < 0 || rows.get(i).type != T_ITEM) return;
            char prev = letterAt(i);
            while (i - 1 >= 0 && rows.get(i - 1).type == T_ITEM && letterAt(i - 1) == prev) i--;
            sel = i;
            clampScroll();
        }
    }

    private char letterAt(int i) {
        YouTubeClient.Item it = rows.get(i).item;
        String t = it == null || it.title == null ? "" : it.title.trim();
        char ch = t.isEmpty() ? '#' : Character.toUpperCase(t.charAt(0));
        return Character.isLetter(ch) ? ch : '#';
    }

    /** The video (not channel) under the cursor, or null. Used to resolve its streams ahead of the click. */
    public YouTubeClient.Item focusedVideo() {
        if (sel < 0 || sel >= rows.size()) return null;
        Row r = rows.get(sel);
        YouTubeClient.Item it = null;
        if (r.type == T_ITEM) it = r.item;
        else if (r.type == T_SHELF && r.shelf != null) {
            int c = colOf(r);
            if (c >= 0 && c < r.shelf.size()) it = r.shelf.get(c);
        }
        return it != null && !it.channel && it.url != null ? it : null;
    }

    /** Right on a video (or channel) opens that channel's page. */
    private void openSelectedChannel() {
        if (sel < 0 || sel >= rows.size()) return;
        YouTubeClient.Item it = rows.get(sel).item;
        if (it == null) return;
        if (it.channel) openChannel(it.url, it.title, it.thumb);
        else if (it.uploaderUrl != null && !it.uploaderUrl.isEmpty()) openChannel(it.uploaderUrl, it.uploader, null);
    }

    private void activate() {
        if (sel < 0 || sel >= rows.size()) return;
        Row r = rows.get(sel);
        if (cur.view == V_HISTORY && (r.type == T_ITEM || r.id == N_CLEARHIST)) {
            if (!armed(sel)) { armedIdx = sel; armedUntil = System.currentTimeMillis() + 4000; return; }
            armedIdx = -1;
            if (r.id == N_CLEARHIST) {
                history.clear();
                saveHistory();
                pop();
            } else {
                removeHistory(r.item);
                rebuildKeepSel();
            }
            return;
        }
        switch (r.type) {
            case T_SEARCH:
                startTyping();
                break;
            case T_TILES:
                switch (colOf(r)) {
                    case TILE_SEARCH: startTyping(); break;
                    case TILE_FEED: if (subs.count() > 0) openFeed(); else push(new Frame(V_CHANNELS)); break;
                    default: push(new Frame(V_CHANNELS)); break;
                }
                break;
            case T_SHELF: {
                int c = colOf(r);
                if (r.shelf != null && c == r.shelf.size() && SHELF_HISTORY.equals(r.label)) {
                    push(new Frame(V_HISTORY));
                    break;
                }
                if (r.shelf == null || c >= r.shelf.size()) break;
                YouTubeClient.Item it = r.shelf.get(c);
                if (it.channel) {
                    openChannel(it.url, it.title, it.thumb);
                } else {
                    addHistory(it);
                    host.playYoutube(it.url, it.title);
                }
                break;
            }
            case T_MORE:
                loadMore();
                break;
            case T_NAV:
                switch (r.id) {
                    case N_FEED: openFeed(); break;
                    case N_CHANNELS: push(new Frame(V_CHANNELS)); break;
                    case N_IMPORT: openImport(); break;
                    case N_MODE: importReplace = !importReplace; rebuildKeepSel(); break;
                    case N_REFRESH: refreshFeed(); rebuildKeepSel(); break;
                    case N_SUBSCRIBE: toggleSubscribe(); break;
                    default:
                        if (r.id >= N_FILE && r.id - N_FILE < importFiles.size()) runImport(importFiles.get(r.id - N_FILE));
                        break;
                }
                break;
            case T_ITEM:
                if (r.item.channel) {
                    openChannel(r.item.url, r.item.title, r.item.thumb);
                } else {
                    addHistory(r.item);
                    host.playYoutube(r.item.url, r.item.title);
                }
                break;
            default:
                break;
        }
    }

    private void toggleSubscribe() {
        Frame f = cur;
        if (f.view != V_CHANNEL || f.chanUrl == null) return;
        if (subs.has(f.chanUrl)) subs.remove(f.chanUrl);
        else subs.add(f.title, f.chanUrl, f.chanThumb);
        feedTime = 0;                       // the feed is stale now
        rebuildKeepSel();
    }

    // ---- painting ---------------------------------------------------------------------------

    private String headerTitle() {
        switch (cur.view) {
            case V_FEED: return "Subscriptions";
            case V_CHANNELS: return "Channels";
            case V_IMPORT: return "Import subscriptions";
            case V_HISTORY: return "Edit Recently watched";
            case V_CHANNEL: return cur.title;
            default: return "YouTube";
        }
    }

    private String headerSub() {
        Frame f = cur;
        if (f.loading) return f.view == V_RESULTS ? "Searching…" : "Loading…";
        if (!f.status.isEmpty()) return f.status;
        switch (f.view) {
            case V_RESULTS: return "Results for \"" + f.query + "\"";
            case V_FEED:
                return feedBusy ? "Updating " + feedDone + " of " + feedTotal + " channels…"
                        : feedItems.isEmpty() ? "Nothing yet — subscribe to some channels"
                        : "Newest uploads from your channels";
            case V_CHANNELS: return subs.count() + " channels";
            case V_HISTORY: return "OK on a video removes it (press OK twice to confirm)";
            case V_IMPORT:
                return importScanning ? "Looking for files…" : importFiles.isEmpty()
                        ? "Copy subscriptions.csv (Google Takeout) to Downloads or a USB/SD card"
                        : "Pick a file to import · " + subs.count() + " channels now";
            case V_CHANNEL: return "Channel" + (f.chanSubs > 0 ? " · " + count(f.chanSubs, "subscribers") : "");
            default: return "Native player · " + qualityLabel();
        }
    }

    private String qualityLabel() {
        String[] l = {"360p", "480p", "720p", "1080p", "1440p", "2160p"};
        return "up to " + l[Settings.ytQuality];
    }

    public void paintTop(Canvas c) {
        Ui.page(c);
        text.setColor(TEXT);
        text.setTextSize(64);
        text.setFakeBoldText(true);
        String title = ellipsize(headerTitle(), 1500);
        c.drawText(title, 110, 150, text);
        float tw = text.measureText(title);
        text.setFakeBoldText(false);
        if (cur.view == V_HOME) {          // red play badge next to the title
            float bx = 110 + tw + 30;
            fill.setColor(RED);
            rect.set(bx, 110, bx + 70, 152);
            c.drawRoundRect(rect, 12, 12, fill);
            fill.setColor(Color.WHITE);
            path.reset();
            path.moveTo(bx + 26, 119);
            path.lineTo(bx + 26, 143);
            path.lineTo(bx + 46, 131);
            path.close();
            c.drawPath(path, fill);
        }
        text.setTextSize(32);
        text.setColor(TEXT_DIM);
        c.drawText(ellipsize(headerSub(), 1600), 110, 200, text);
        if (cur.view == V_CHANNELS && sel >= 0 && sel < rows.size() && rows.get(sel).type == T_ITEM && !typing) {
            String L = String.valueOf(letterAt(sel));
            text.setTextSize(84);
            text.setFakeBoldText(true);
            text.setColor(ACCENT);
            c.drawText(L, 1800 - text.measureText(L), 172, text);
            text.setFakeBoldText(false);
        }

        if (typing && kb.isOpen()) {
            paintTyping(c);
            return;
        }

        int y = LIST_TOP, last = scroll - 1;
        for (int idx = scroll; idx < rows.size(); idx++) {
            int h = rowH(idx);
            if (y + h > LIST_BOTTOM + 4) break;
            paintIdx = idx;
            paintRow(c, rows.get(idx), y, idx == sel);
            y += h;
            last = idx;
        }
        if (cur.loading || cur.loadingMore) {
            text.setColor(TEXT_DIM);
            text.setTextSize(34);
            if (y + 70 <= LIST_BOTTOM) c.drawText(cur.loadingMore ? "Loading more\u2026" : "Loading\u2026", 130, y + 56, text);
        }
        if (scroll > 0 || last < rows.size() - 1) {         // scrollbar
            float trackH = LIST_BOTTOM - LIST_TOP;
            float shown = Math.max(1, last - scroll + 1);
            float thumbH = Math.max(60, trackH * shown / rows.size());
            float yy = LIST_TOP + (trackH - thumbH) * scroll / Math.max(1, rows.size() - shown);
            fill.setColor(Color.argb(90, 255, 255, 255));
            rect.set(1850, yy, 1862, yy + thumbH);
            c.drawRoundRect(rect, 6, 6, fill);
        }
        paintHints(c);
    }

    private void paintTyping(Canvas c) {
        fill.setColor(PANEL);
        rect.set(110, 268, 1810, 376);
        c.drawRoundRect(rect, 20, 20, fill);
        text.setColor(TEXT);
        text.setTextSize(54);
        c.drawText(ellipsizeStart("\u2315  " + typed + "_", 1620), 150, 342, text);

        List<String> l = chips();
        text.setTextSize(28);
        text.setFakeBoldText(true);
        text.setColor(TEXT_DIM);
        String label = typed.trim().isEmpty() ? (l.isEmpty() ? "" : "RECENT SEARCHES") : (l.isEmpty() ? "" : "SUGGESTIONS");
        c.drawText(label, 112, 424, text);
        text.setFakeBoldText(false);
        // chips: keep the focused one in view
        text.setTextSize(32);
        if (chipFocus >= 0) {
            if (chipFocus < chipFirst) chipFirst = chipFocus;
            while (chipFirst < chipFocus && chipsWidth(l, chipFirst, chipFocus) > 1700) chipFirst++;
        }
        float x = 110;
        for (int i = chipFirst; i < l.size(); i++) {
            String s = ellipsize(l.get(i), 440);
            float w = text.measureText(s) + 48;
            if (x + w > 1810) {
                text.setColor(TEXT_DIM);
                c.drawText("\u203A", 1830, 488, text);
                break;
            }
            boolean f = i == chipFocus;
            fill.setColor(f ? ROW_SEL : Ui.CHIP);
            rect.set(x, 440, x + w, 506);
            c.drawRoundRect(rect, 33, 33, fill);
            if (f) {
                stroke.setColor(ACCENT);
                stroke.setStrokeWidth(4);
                c.drawRoundRect(rect, 33, 33, stroke);
            }
            text.setColor(f ? TEXT : Color.rgb(200, 208, 218));
            c.drawText(s, x + 24, 485, text);
            x += w + 16;
        }
        if (chipFocus >= 0) {
            Ui.hints(c, 110, 588, Ui.K_OK, "Search for this", Ui.K_LR, "Choose", Ui.K_DOWN, "Keyboard", Ui.K_BACK, "Close");
        } else {
            Ui.hints(c, 110, 588, Ui.K_OK, "Type", l.isEmpty() ? null : Ui.K_UP, l.isEmpty() ? null : (typed.trim().isEmpty()
                    ? "Recent searches" : "Suggestions"), Ui.K_BACK, "Close");
        }
    }

    private float chipsWidth(List<String> l, int from, int to) {
        float w = 0;
        for (int i = from; i <= to && i < l.size(); i++) w += text.measureText(ellipsize(l.get(i), 440)) + 64;
        return w;
    }

    /** Only the keys that do something on the focused row. */
    private void paintHints(Canvas c) {
        String ok = null, right = null;
        if (cur.view == V_HISTORY) {
            Ui.hints(c, 110, Ui.HINT_Y, Ui.K_OK, history.isEmpty() ? null : armed(sel) ? "Remove now" : "Remove",
                    Ui.K_BACK, "Done");
            return;
        }
        if (sel >= 0 && sel < rows.size()) {
            Row r = rows.get(sel);
            switch (r.type) {
                case T_SEARCH: ok = "Type a search"; break;
                case T_MORE: ok = "Load more"; break;
                case T_NAV:
                    ok = r.id == N_MODE ? "Switch mode" : r.id == N_REFRESH ? "Refresh" : r.id == N_SUBSCRIBE
                            ? (r.label.startsWith("Un") ? "Unsubscribe" : "Subscribe") : r.id >= N_FILE ? "Import" : "Open";
                    break;
                case T_ITEM:
                    ok = r.item.channel ? "Open channel" : "Play";
                    if (!r.item.channel && r.item.uploaderUrl != null && !r.item.uploaderUrl.isEmpty()
                            && cur.view != V_CHANNEL) right = "Channel";
                    break;
                case T_TILES: ok = "Open"; break;
                case T_SHELF: ok = r.shelf != null && colOf(r) >= r.shelf.size() ? "Edit list" : "Play"; break;
                default: break;
            }
            if (horizontal(r)) {
                Ui.hints(c, 110, Ui.HINT_Y, Ui.K_OK, ok, Ui.K_LR, "Choose", Ui.K_BACK, stack.isEmpty() ? "Home" : "Back",
                        Ui.K_HOLD_OK, "Recentre");
                return;
            }
            if (cur.view == V_CHANNELS && r.type == T_ITEM) {
                Ui.hints(c, 110, Ui.HINT_Y, Ui.K_OK, ok, Ui.K_LR, "Jump a letter", Ui.K_BACK, "Back",
                        Ui.K_HOLD_OK, "Recentre");
                return;
            }
        }
        Ui.hints(c, 110, Ui.HINT_Y, Ui.K_OK, ok, Ui.K_RIGHT, right, Ui.K_BACK, stack.isEmpty() ? "Home" : "Back",
                Ui.K_HOLD_OK, "Recentre");
    }

    private void paintRow(Canvas c, Row r, int y, boolean selected) {
        if (r.type == T_TILES) { paintTiles(c, r, y, selected); return; }
        if (r.type == T_SHELF) { paintShelf(c, r, y, selected); return; }
        if (r.type == T_HEADER) {
            text.setColor(TEXT_DIM);
            text.setTextSize(30);
            text.setFakeBoldText(true);
            c.drawText(r.label.toUpperCase(Locale.getDefault()), 112, y + 46, text);
            text.setFakeBoldText(false);
            return;
        }
        boolean arm = selected && cur.view == V_HISTORY && armed(paintIdx);
        rect.set(90, y, 1840, y + ROW_H - 10);
        fill.setColor(arm ? Ui.REMOVE : selected ? ROW_SEL : PANEL);
        c.drawRoundRect(rect, 18, 18, fill);
        if (arm) {
            text.setColor(TEXT);
            text.setTextSize(34);
            text.setFakeBoldText(true);
            String m = r.id == N_CLEARHIST ? "Press OK again to clear everything" : "Press OK again to remove";
            c.drawText(m, 1810 - text.measureText(m), y + 70, text);
            text.setFakeBoldText(false);
        }
        if (selected) {
            fill.setColor(ACCENT);
            rect.set(90, y, 100, y + ROW_H - 10);
            c.drawRoundRect(rect, 5, 5, fill);
        }

        if (r.type == T_SEARCH || r.type == T_MORE || r.type == T_NAV) {
            boolean two = r.sub != null;
            text.setColor(TEXT);
            text.setTextSize(42);
            float x = r.type == T_SEARCH ? 250 : 150;
            c.drawText(ellipsize(r.label, 1500), x, y + (two ? 52 : 70), text);
            if (two) {
                text.setColor(TEXT_DIM);
                text.setTextSize(30);
                c.drawText(ellipsize(r.sub, 1500), x, y + 94, text);
            }
            if (r.type == T_SEARCH) {      // magnifier
                fill.setColor(ACCENT);
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(8);
                c.drawCircle(165, y + 50, 22, fill);
                c.drawLine(181, y + 66, 200, y + 86, fill);
                fill.setStyle(Paint.Style.FILL);
                fill.setStrokeWidth(0);
            }
            return;
        }

        YouTubeClient.Item it = r.item;
        if (it.channel) {
            paintChannelRow(c, it, y);
            return;
        }
        rect.set(120, y + 3, 120 + TH_W, y + 3 + TH_H);
        Bitmap b = thumb(it.thumb, false);
        if (b != null) {
            c.drawBitmap(b, null, rect, bmpPaint);
        } else {
            fill.setColor(Color.rgb(44, 52, 64));
            c.drawRoundRect(rect, 8, 8, fill);
        }
        String dur = it.live ? "LIVE" : it.durationSec > 0 ? Library.fmt(it.durationSec * 1000) : "";
        if (!dur.isEmpty()) {
            text.setTextSize(24);
            float tw = text.measureText(dur);
            fill.setColor(it.live ? RED : Color.argb(210, 0, 0, 0));
            rect.set(120 + TH_W - tw - 22, y + 3 + TH_H - 36, 120 + TH_W - 6, y + 3 + TH_H - 6);
            c.drawRoundRect(rect, 6, 6, fill);
            text.setColor(Color.WHITE);
            c.drawText(dur, 120 + TH_W - tw - 14, y + 3 + TH_H - 13, text);
        }
        text.setColor(TEXT);
        text.setTextSize(38);
        c.drawText(ellipsize(it.title, 1420), 350, y + 48, text);
        text.setColor(TEXT_DIM);
        text.setTextSize(30);
        String meta = meta(it);
        String line = it.uploader == null || it.uploader.isEmpty() ? meta
                : meta.isEmpty() ? it.uploader : it.uploader + "  \u00b7  " + meta;
        c.drawText(ellipsize(line, 1420), 350, y + 92, text);
    }

    private void paintTiles(Canvas c, Row r, int y, boolean selected) {
        int col = colOf(r);
        float w = (1840 - 90 - 2 * 24) / 3f;
        String[] title = {"Search", "Subscriptions", "Channels"};
        String[] sub = {
                recentSearches.isEmpty() ? "Videos and channels" : "Last: " + recentSearches.get(0),
                subs.count() > 0 ? (feedBusy ? "Updating\u2026" : "Latest from " + subs.count() + " channels") : "None yet: import or subscribe",
                subs.count() > 0 ? subs.count() + " channels \u00b7 import" : "Import a subscriptions file"};
        for (int i = 0; i < 3; i++) {
            float x = 90 + i * (w + 24);
            boolean s = selected && i == col;
            rect.set(x, y, x + w, y + TILES_H - 12);
            fill.setColor(s ? ROW_SEL : PANEL);
            c.drawRoundRect(rect, 18, 18, fill);
            if (s) {
                stroke.setColor(ACCENT);
                stroke.setStrokeWidth(4);
                c.drawRoundRect(rect, 18, 18, stroke);
            }
            paintTileIcon(c, i, x + 62, y + (TILES_H - 12) / 2f);
            text.setColor(TEXT);
            text.setTextSize(40);
            c.drawText(title[i], x + 120, y + 52, text);
            text.setColor(TEXT_DIM);
            text.setTextSize(28);
            c.drawText(ellipsize(sub[i], w - 140), x + 120, y + 92, text);
        }
    }

    private void paintTileIcon(Canvas c, int i, float cx, float cy) {
        if (i == TILE_SEARCH) {
            fill.setColor(ACCENT);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(7);
            c.drawCircle(cx - 4, cy - 4, 18, fill);
            c.drawLine(cx + 9, cy + 9, cx + 24, cy + 24, fill);
            fill.setStyle(Paint.Style.FILL);
            fill.setStrokeWidth(0);
        } else if (i == TILE_FEED) {
            fill.setColor(RED);
            rect.set(cx - 28, cy - 19, cx + 28, cy + 19);
            c.drawRoundRect(rect, 10, 10, fill);
            fill.setColor(Color.WHITE);
            path.reset();
            path.moveTo(cx - 7, cy - 10);
            path.lineTo(cx - 7, cy + 10);
            path.lineTo(cx + 11, cy);
            path.close();
            c.drawPath(path, fill);
        } else {
            fill.setColor(Ui.TEXT_DIM);
            for (int k = 0; k < 3; k++) c.drawCircle(cx - 18 + k * 18, cy, 10, fill);
        }
    }

    private void paintShelf(Canvas c, Row r, int y, boolean selected) {
        if (r.shelf == null) return;
        int col = colOf(r), first = firstOf(r), n = colCount(r);
        for (int i = first; i < n && i < first + CARDS_VISIBLE; i++) {
            float x = CARD_X0 + (i - first) * CARD_STEP;
            boolean s = selected && i == col;
            if (i >= r.shelf.size()) {
                Ui.editCard(c, x, y + 4, CARD_W, CARD_H, s);
                break;
            }
            YouTubeClient.Item it = r.shelf.get(i);
            rect.set(x, y + 4, x + CARD_W, y + 4 + CARD_H);
            Bitmap b = thumb(it.thumb, false);
            if (b != null) {
                c.drawBitmap(b, null, rect, bmpPaint);
            } else {
                fill.setColor(Color.rgb(44, 52, 64));
                c.drawRoundRect(rect, 8, 8, fill);
            }
            String dur = it.live ? "LIVE" : it.durationSec > 0 ? Library.fmt(it.durationSec * 1000) : "";
            if (!dur.isEmpty()) {
                text.setTextSize(24);
                float tw = text.measureText(dur);
                fill.setColor(it.live ? RED : Color.argb(210, 0, 0, 0));
                rect.set(x + CARD_W - tw - 22, y + 4 + CARD_H - 36, x + CARD_W - 6, y + 4 + CARD_H - 6);
                c.drawRoundRect(rect, 6, 6, fill);
                text.setColor(Color.WHITE);
                c.drawText(dur, x + CARD_W - tw - 14, y + 4 + CARD_H - 13, text);
            }
            if (s) {
                stroke.setColor(ACCENT);
                stroke.setStrokeWidth(6);
                rect.set(x - 5, y - 1, x + CARD_W + 5, y + 9 + CARD_H);
                c.drawRoundRect(rect, 12, 12, stroke);
            }
            text.setColor(s ? TEXT : Color.rgb(196, 204, 214));
            text.setTextSize(30);
            c.drawText(ellipsize(it.title, CARD_W), x, y + CARD_H + 44, text);
            text.setColor(TEXT_DIM);
            text.setTextSize(26);
            c.drawText(ellipsize(it.uploader, CARD_W), x, y + CARD_H + 78, text);
        }
        text.setTextSize(46);
        text.setColor(TEXT_DIM);
        if (first > 0) c.drawText("\u2039", 76, y + 98, text);
        if (first + CARDS_VISIBLE < n) c.drawText("\u203A", 1836, y + 98, text);
    }

    private void paintChannelRow(Canvas c, YouTubeClient.Item it, int y) {
        if (it.thumb == null || it.thumb.isEmpty()) {
            if (cur.view == V_CHANNELS) requestAvatar(it.url);
        }
        float cx = 120 + TH_H / 2f, cy = y + 3 + TH_H / 2f;
        Bitmap b = thumb(it.thumb, true);
        if (b != null) {
            c.save();
            path.reset();
            path.addCircle(cx, cy, TH_H / 2f - 6, Path.Direction.CW);
            c.clipPath(path);
            src.set(0, 0, b.getWidth(), b.getHeight());
            rect.set(cx - TH_H / 2f, cy - TH_H / 2f, cx + TH_H / 2f, cy + TH_H / 2f);
            c.drawBitmap(b, src, rect, bmpPaint);
            c.restore();
        } else {
            fill.setColor(Color.rgb(44, 52, 64));
            c.drawCircle(cx, cy, TH_H / 2f - 6, fill);
            text.setColor(TEXT_DIM);
            text.setTextSize(52);
            String ini = it.title.isEmpty() ? "?" : it.title.substring(0, 1).toUpperCase(Locale.ROOT);
            c.drawText(ini, cx - text.measureText(ini) / 2f, cy + 18, text);
        }
        text.setColor(TEXT);
        text.setTextSize(42);
        if (it.views > 0) {
            c.drawText(ellipsize(it.title, 1380), 270, y + 50, text);
            text.setColor(TEXT_DIM);
            text.setTextSize(30);
            c.drawText(count(it.views, "subscribers"), 270, y + 92, text);
        } else {
            c.drawText(ellipsize(it.title, 1380), 270, y + 72, text);
        }
    }

    private String meta(YouTubeClient.Item it) {
        StringBuilder sb = new StringBuilder();
        if (it.uploadedMs > 0) sb.append(ago(it.uploadedMs));
        else if (!it.when.isEmpty()) sb.append(it.when);
        if (it.views > 0) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(count(it.views, "views"));
        }
        return sb.toString();
    }

    private static String ago(long ms) {
        long s = Math.max(0, (System.currentTimeMillis() - ms) / 1000);
        if (s < 90) return "just now";
        long m = s / 60;
        if (m < 60) return m + (m == 1 ? " minute ago" : " minutes ago");
        long h = m / 60;
        if (h < 24) return h + (h == 1 ? " hour ago" : " hours ago");
        long d = h / 24;
        if (d < 30) return d + (d == 1 ? " day ago" : " days ago");
        long mo = d / 30;
        if (mo < 12) return mo + (mo == 1 ? " month ago" : " months ago");
        long y = d / 365;
        return y + (y == 1 ? " year ago" : " years ago");
    }

    private static String count(long v, String unit) {
        if (v < 1000) return v + " " + unit;
        if (v < 1000000) return String.format(Locale.US, "%.1fK %s", v / 1000.0, unit);
        if (v < 1000000000L) return String.format(Locale.US, "%.1fM %s", v / 1000000.0, unit);
        return String.format(Locale.US, "%.1fB %s", v / 1000000000.0, unit);
    }

    public void paintBottom(Canvas c) { kb.paint(c, chipFocus < 0); }

    public int topHash() {
        Frame f = cur;
        int h = sel * 31 + scroll * 7 + rows.size() * 13 + (f.loading ? 1 : 0) + (f.loadingMore ? 2 : 0)
                + (typing ? 4 : 0) + (importScanning ? 5 : 0) + (importReplace ? 6 : 0) + importFiles.size() * 19 + f.view * 101 + stack.size() * 1009 + (feedBusy ? 8 : 0) + feedDone * 17;
        h = h * 31 + f.status.hashCode() + typed.hashCode() + f.query.hashCode() + f.title.hashCode()
                + thumbVersion * 17 + Settings.ytQuality + importMsg.hashCode() + subs.count() * 3;
        h = h * 31 + cols.hashCode() + firsts.hashCode() * 7 + chipFocus * 13 + chipFirst * 29
                + suggestions.hashCode() + recentSearches.size() + (armed(armedIdx) ? armedIdx * 5 + 1 : 0)
            + history.size() * 37 + (Settings.hideShorts ? 41 : 0) + shortCache.size();
        return h;
    }

    public int bottomHash() { return kb.hash() * 3 + (chipFocus >= 0 ? 1 : 0); }

    private String ellipsize(String s, float maxW) {
        if (s == null) return "";
        if (text.measureText(s) <= maxW) return s;
        int n = s.length();
        while (n > 1 && text.measureText(s, 0, n) + text.measureText("…") > maxW) n--;
        return s.substring(0, n) + "…";
    }

    private String ellipsizeStart(String s, float maxW) {
        if (text.measureText(s) <= maxW) return s;
        int n = 0;
        while (n < s.length() - 1 && text.measureText(s, n, s.length()) + text.measureText("…") > maxW) n++;
        return "…" + s.substring(n);
    }
}
