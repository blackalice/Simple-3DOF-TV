package com.portable3dof;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.KeyEvent;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The media player's UI state machine and painter: home / storage browser, playback HUD + subtitles, the playback
 * options panel and the settings page, all drawn into an {@link Overlay} that is textured onto the floating screen.
 * Input is D-pad, OK and BACK (MENU is optional); holding OK recentres everywhere.
 */
public class PlayerUi implements Runnable, YouTubeUi.Host {

    public interface Host {
        void recenter();
        void requestVideoPermission();
        void requestAllFilesAccess();
        void applyDisplaySettings();
        void saveSettings();
        String trackingStatus();
        boolean glassesConnected();
        /** Glasses stopped streaming: the view goes back to straight ahead. */
        void glassesLost();
        /** 0..100, or -1 when unknown. */
        int batteryPercent();
        void exitApp();
    }

    private enum Mode { LIBRARY, PLAYER, SETTINGS, BROWSER, YOUTUBE, LEGAL }

    private static final int ROW_H = 104, HEADER_H = 66, SHELF_H = 250, LIST_TOP = 236, LIST_BOTTOM = 990;
    private static final int CARD_W = 280, CARD_H = 158, CARD_STEP = 306, CARD_X0 = 110, CARDS_VISIBLE = 5;
    /** Continue-watching shelf: its cards, the focused card and the first one drawn. */
    private List<Library.Entry> shelf = new ArrayList<>();
    private int shelfCol = 0, shelfFirst = 0;

    private final Overlay overlay;
    private final MediaEngine engine;
    private final Library library;
    private final SceneRenderer renderer;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private BrowserController browser;
    private YouTubeUi yt;
    private boolean returnToYoutube = false;

    private Mode mode = Mode.LIBRARY;
    private Mode settingsReturn = Mode.LIBRARY;
    private String folderKey = null;          // null = home
    private List<Library.Entry> entries = new ArrayList<>();
    private int sel = 0, scroll = 0;
    private List<Library.Entry> playlist = new ArrayList<>();
    private int playIndex = -1;

    private long hudUntil = 0;
    private String toast = "";
    private long toastUntil = 0;
    private int lastFull = 0, lastBand = 0;
    private long exitArmedUntil = 0;
    private final long startedAt = System.currentTimeMillis();
    private Boolean lastGlasses = null;

    // YouTube video being resolved (network) and the one playing
    private String loadingTitle = null;
    private int loadingToken = 0;
    private YouTubeClient.Playable playingYt = null;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint scrim = new Paint();
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subtitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subtitleOutline = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

    public PlayerUi(Overlay overlay, MediaEngine engine, Library library, SceneRenderer renderer, Host host) {
        this.overlay = overlay;
        this.engine = engine;
        this.library = library;
        this.renderer = renderer;
        this.host = host;
        text.setTypeface(Typeface.SANS_SERIF);
        stroke.setStyle(Paint.Style.STROKE);
        subtitle.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        subtitle.setTextSize(58);
        subtitle.setColor(Color.WHITE);
        subtitleOutline.set(subtitle);
        subtitleOutline.setStyle(Paint.Style.STROKE);
        subtitleOutline.setStrokeWidth(9);
        subtitleOutline.setStrokeJoin(Paint.Join.ROUND);
        subtitleOutline.setColor(Color.BLACK);
        refreshEntries();
    }

    public void start() { ui.post(this); }

    public void stop() { ui.removeCallbacks(this); }

    /** Library contents changed (scan finished, USB plugged/unplugged, permission granted). */
    public void rescan() {
        refreshEntries();
        lastFull = 0;
    }

    private Uri pendingUri;
    private String pendingTitle, pendingPath;

    /** Activity going to the background: remember what was playing and free the decoder. */
    public void pauseApp() {
        if (browser != null) browser.suspend();
        if (mode != Mode.LIBRARY && engine.hasMedia()) {
            pendingUri = engine.getUri();
            pendingTitle = engine.getTitle();
            pendingPath = engine.getPath();
        } else {
            pendingUri = null;
        }
        engine.close();
        overlay.bandActive = false;
    }

    /** Activity back in the foreground: pick up where we left off. */
    public void resumeApp() {
        if (browser != null) browser.resume();
        if (pendingUri != null) {
            if ("youtube".equals(pendingUri.getScheme())) {
                playYoutube("https://www.youtube.com/watch?v=" + pendingUri.toString().substring("youtube://".length()),
                        pendingTitle);
            } else {
                engine.open(pendingUri, pendingTitle, pendingPath);
                showHud();
            }
            pendingUri = null;
        }
        lastFull = 0;
    }

    /** Open a stream/file straight away (launch intent). */
    public void playNow(Uri uri, String title) {
        playlist = new ArrayList<>();
        playIndex = -1;
        playingYt = null;
        engine.open(uri, title, null);
        mode = Mode.PLAYER;
        quickOpen = false;
        showHud();
    }

    // ---- periodic tick: auto-advance + repaint when something changed -----------------------

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        if (mode == Mode.PLAYER && engine.isEnded()) advance(1, true);
        watchGlasses(now);
        prefetchTick(now);
        qualityTick(now);
        renderer.setUiFlat(wantsFlatScreen());
        int fs = fullSig(now);
        if (fs != lastFull) {
            lastFull = fs;
            lastBand = bandSig(now);
            paintAll();
        } else if (mode == Mode.PLAYER || mode == Mode.BROWSER || mode == Mode.YOUTUBE) {
            int bs = bandSig(now);
            if (bs != lastBand) {
                lastBand = bs;
                paintBandOnly();
            }
        }
        ui.postDelayed(this, 100);
    }

    /**
     * Menus and lists are drawn on a flat screen (curved text bends and shimmers through the optics; CRTXR dropped
     * its curved menu for the same reason). The curve is for video, and for previewing the curve setting itself.
     */
    private boolean wantsFlatScreen() {
        switch (mode) {
            case PLAYER: return false;
            case SETTINGS: return settingsReturn != Mode.PLAYER && settingId() != S_CURVE;
            default: return true;
        }
    }

    /**
     * Head tracking only ever comes from the glasses. When they drop out the screen goes back to straight ahead;
     * when they connect the view is recentred. Say so after start-up, once the probe has settled.
     */
    private void watchGlasses(long now) {
        boolean g = host.glassesConnected();
        if (lastGlasses != null && g != lastGlasses) {
            if (g) host.recenter(); else host.glassesLost();
            if (now - startedAt > 6000) {
                toast(g ? "Glasses connected: head tracking on"
                        : "Glasses disconnected: the screen stays in front of you", 4000);
            }
        }
        lastGlasses = g;
    }

    /** Everything that needs the whole panel repainted. */
    private int fullSig(long now) {
        int h = mode.ordinal() * 31 + settingsReturn.ordinal();
        h = h * 31 + sel * 7 + scroll * 13 + settingsSel * 101 + settingsScroll * 41 + entries.size();
        if (mode == Mode.LEGAL && legal != null) h = h * 31 + legal.hash();
        h = h * 31 + (library.isScanned() ? 1 : 0) + (library.hasAllFilesAccess() ? 2 : 0) + (library.hasPermission() ? 4 : 0);
        h = h * 31 + (now < toastUntil ? toast.hashCode() : 0);
        h = h * 31 + (loadingTitle != null ? loadingTitle.hashCode() + (int) (now / 350) : 0);
        h = h * 31 + Math.round(renderer.getScreenWidthDeg() * 10) + Math.round(renderer.getFov() * 10) * 7
                + Math.round(renderer.getEnvBrightness() * 10) * 11 + Math.round(renderer.getCurve() * 100) * 17
                + Settings.smoothing * 19 + Settings.predictMs * 23 + (Settings.hz120 ? 29 : 0)
                + (Settings.gyroPointer ? 31 : 0) + Settings.pointerSens * 3 + Settings.pointerAxis * 37
                + Settings.ytQuality * 43 + Math.round(engine.getVolume() * 100) * 47
                + (Settings.lostArrow ? 53 : 0) + Settings.lostWhen * 59 + Settings.soundLevel * 61
                + Settings.background * 67 + (Settings.hideShorts ? 71 : 0) + Settings.panoramaFile.hashCode()
                + Settings.panoramaTurn * 73 + panoramas.size() * 79 + (panoramasScanning ? 83 : 0);
        if (mode == Mode.LIBRARY) {
            h = h * 31 + shelfCol * 7 + shelfFirst * 11 + shelf.size() + (yt != null ? yt.thumbVersion() * 13 : 0)
                    + (armed(removeArmed) ? removeArmed * 17 + 1 : 0);
            h = h * 31 + host.trackingStatus().hashCode() + (host.glassesConnected() ? 1 : 0)
                    + host.batteryPercent() * 3 + (int) (now / 60000) + (now < exitArmedUntil ? 5 : 0);
        }
        if (mode == Mode.PLAYER) {
            h = h * 31 + (engine.getError() == null ? 0 : 1) + (engine.isBuffering() ? 2 : 0);
            h = h * 31 + (quickOpen ? 1 + quickSel * 3 : 0);
            if (quickOpen) h = h * 31 + engine.audioLabel().hashCode() + engine.subtitleLabel().hashCode()
                    + Math.round(engine.getSpeed() * 100);
        }
        if (mode == Mode.BROWSER && browser != null) h = h * 31 + browser.stateHash();
        if (mode == Mode.YOUTUBE && yt != null) h = h * 31 + yt.topHash();
        return h;
    }

    /** Playback-strip state (HUD + subtitles). */
    private int bandSig(long now) {
        if (mode == Mode.BROWSER) return browser == null ? 0 : browser.bottomHash();
        if (mode == Mode.YOUTUBE) return yt == null ? 0 : yt.bottomHash();
        int h = hudVisible(now) ? 1 : 0;
        if (hudVisible(now)) h = h * 31 + (int) (engine.getPositionMs() / 1000) + engine.getTitle().hashCode();
        h = h * 31 + (engine.isPlaying() ? 1 : 0);
        h = h * 31 + engine.getCueText().hashCode();
        h = h * 31 + Math.round(engine.getVolume() * 100);
        return h;
    }

    private boolean hudVisible(long now) {
        return mode == Mode.PLAYER && (quickOpen || now < hudUntil || !engine.isPlaying() || engine.getError() != null);
    }

    private void showHud() { hudUntil = System.currentTimeMillis() + 4500; }

    private void toast(String s) { toast(s, 1600); }

    private void toast(String s, long ms) { toast = s; toastUntil = System.currentTimeMillis() + ms; }

    // ---- library navigation -----------------------------------------------------------------

    private void refreshEntries() {
        entries = folderKey == null ? library.root() : library.folder(folderKey);
        shelf = folderKey == null ? library.continueWatching() : new ArrayList<Library.Entry>();
        shelfCol = Math.max(0, Math.min(shelf.size(), shelfCol));      // shelf.size() = the "Edit list" card
        if (sel >= entries.size()) sel = Math.max(0, entries.size() - 1);
        normalizeSel(1);
        clampScroll();
    }

    private boolean selectable(int i) { return i >= 0 && i < entries.size() && entries.get(i).type != Library.HEADER; }

    private void normalizeSel(int dir) {
        if (entries.isEmpty()) { sel = 0; return; }
        sel = Math.max(0, Math.min(entries.size() - 1, sel));
        int i = sel;
        while (i >= 0 && i < entries.size() && !selectable(i)) i += dir;
        if (i < 0 || i >= entries.size()) {
            i = sel;
            while (i >= 0 && i < entries.size() && !selectable(i)) i -= dir;
        }
        sel = Math.max(0, Math.min(entries.size() - 1, i));
    }

    private void moveSel(int d) {
        int n = sel + d, step = d > 0 ? 1 : -1;
        n = Math.max(0, Math.min(entries.size() - 1, n));
        while (n >= 0 && n < entries.size() && !selectable(n)) n += step;
        if (n < 0 || n >= entries.size()) return;      // no wrap at the ends
        sel = n;
        clampScroll();
    }

    private int rowH(int i) {
        int t = entries.get(i).type;
        return t == Library.HEADER ? HEADER_H : t == Library.SHELF ? SHELF_H : ROW_H;
    }

    private void moveShelf(int d) {
        int n = shelfCol + d;
        if (n < 0 || n > shelf.size()) return;      // no wrap; the last card is "Edit list"
        shelfCol = n;
        if (shelfCol < shelfFirst) shelfFirst = shelfCol;
        if (shelfCol >= shelfFirst + CARDS_VISIBLE) shelfFirst = shelfCol - CARDS_VISIBLE + 1;
    }

    /** Keep the selection (and the section title just above it) on screen. */
    private void clampScroll() {
        if (entries.isEmpty()) { scroll = 0; return; }
        if (sel < scroll) scroll = sel;
        if (scroll == sel && sel > 0 && entries.get(sel - 1).type == Library.HEADER) scroll = sel - 1;
        // nothing selectable above: show from the top
        boolean any = false;
        for (int i = 0; i < scroll; i++) if (selectable(i)) { any = true; break; }
        if (!any) scroll = 0;
        int avail = LIST_BOTTOM - LIST_TOP;
        while (scroll < sel && spanH(scroll, sel) > avail) scroll++;
    }

    private int spanH(int from, int to) {
        int h = 0;
        for (int i = from; i <= to; i++) h += rowH(i);
        return h;
    }

    private void goUp() {
        if (folderKey == null) return;
        String prev = folderKey;
        if (folderKey.startsWith(Library.KEY_DIR)) {
            String p = folderKey.substring(Library.KEY_DIR.length());
            if (library.volumeRoots().contains(p)) {
                folderKey = null;
            } else {
                File parent = new File(p).getParentFile();
                folderKey = parent == null ? null : Library.KEY_DIR + parent.getPath();
            }
        } else if (Library.KEY_MEDIASTORE.equals(folderKey) || Library.KEY_WEB.equals(folderKey)
                || Library.KEY_MANAGE.equals(folderKey)) {
            folderKey = null;
        } else {
            folderKey = Library.KEY_MEDIASTORE;     // a bucket -> back to the bucket list
        }
        entries = folderKey == null ? library.root() : library.folder(folderKey);
        // land on the folder we just came out of
        sel = 0;
        for (int i = 0; i < entries.size(); i++) {
            String k = entries.get(i).key;
            if (k != null && k.equals(prev)) { sel = i; break; }
        }
        scroll = 0;
        normalizeSel(1);
        clampScroll();
    }

    private void enter(String key) {
        folderKey = key;
        sel = 0;
        scroll = 0;
        refreshEntries();
    }

    private void activate(Library.Entry e) {
        switch (e.type) {
            case Library.SETTINGS:
                openSettings();
                break;
            case Library.FOLDER:
            case Library.VOLUME:
                enter(e.key);
                break;
            case Library.APP:
                if (Library.KEY_YOUTUBE.equals(e.key)) openYoutubeUi();
                else if (Library.KEY_WEB.equals(e.key)) enter(e.key);
                else openBrowser(e.key);
                break;
            case Library.ACTION:
                if ("allfiles".equals(e.key)) host.requestAllFilesAccess();
                else if ("videoperm".equals(e.key)) host.requestVideoPermission();
                else if ("ytinfo".equals(e.key)) toast("Native YouTube is in the separate YouTube edition, which you build from source: see the README, \"Build the YouTube version yourself\".", 9000);
                break;
            case Library.VIDEO:
            case Library.STREAM:
            case Library.RESUME:
                if (e.uri != null && "youtube".equals(e.uri.getScheme())) {
                    playYoutube("https://www.youtube.com/watch?v=" + e.uri.toString().substring("youtube://".length()), e.name);
                    break;
                }
                playlist = new ArrayList<>();
                for (Library.Entry x : entries) if (x.type == Library.VIDEO) playlist.add(x);
                playIndex = playlist.indexOf(e);
                playingYt = null;
                engine.open(e.uri, e.name, e.path);
                mode = Mode.PLAYER;
                quickOpen = false;
                showHud();
                break;
            default:
                break;
        }
    }

    // ---- editing Continue watching: OK once arms a removal, OK again (within 4 s) does it ----

    private int removeArmed = -1;
    private long removeArmedUntil = 0;

    private boolean armed(int idx) { return removeArmed == idx && System.currentTimeMillis() < removeArmedUntil; }

    private void manageOk(Library.Entry e) {
        if ("noop".equals(e.key)) return;
        if (!armed(sel)) {
            removeArmed = sel;
            removeArmedUntil = System.currentTimeMillis() + 4000;
            return;
        }
        removeArmed = -1;
        if ("clearall".equals(e.key)) {
            library.forgetAll();
            toast("Continue watching cleared");
            goUp();
        } else {
            library.forget(e.uri);
            toast("Removed");
            int keep = sel;
            refreshEntries();
            sel = Math.min(keep, entries.size() - 1);
            normalizeSel(-1);
            clampScroll();
        }
    }

    /** Jump to the next/previous video in the folder (or leave playback when there is none). */
    private void advance(int delta, boolean auto) {
        int n = playIndex + delta;
        if (playIndex >= 0 && n >= 0 && n < playlist.size()) {
            Library.Entry e = playlist.get(n);
            playIndex = n;
            engine.open(e.uri, e.name, e.path);
            showHud();
        } else if (auto) {
            leavePlayback();
        } else {
            toast(delta > 0 ? "No next video" : "No previous video");
        }
    }

    private void leavePlayback() {
        engine.close();
        overlay.bandActive = false;
        quickOpen = false;
        playingYt = null;
        shelfCol = 0;              // what you just watched is now first
        shelfFirst = 0;
        if (returnToYoutube && yt != null) {
            mode = Mode.YOUTUBE;
            yt.reshow();
        } else {
            mode = Mode.LIBRARY;
            refreshEntries();      // the resume list may have changed
        }
        returnToYoutube = false;
        lastFull = 0;
    }

    // ---- YouTube ----------------------------------------------------------------------------

    private final java.util.concurrent.ExecutorService net = java.util.concurrent.Executors.newSingleThreadExecutor();

    // ---- resolving ahead of the click -------------------------------------------------------------
    // Extracting a video's streams takes ~2 s even when warm (several sequential requests plus parsing on a slow CPU),
    // so once the cursor has rested on a video for a moment its streams are resolved in the background. Pressing OK
    // then uses the finished (or in-flight) result. Entries expire well before the stream URLs do.
    private static final long PREFETCH_DWELL_MS = 500, PREFETCH_TTL_MS = 15 * 60_000L;
    private final java.util.concurrent.ExecutorService prefetchExec = java.util.concurrent.Executors.newFixedThreadPool(2);
    private final java.util.LinkedHashMap<String, Prefetch> prefetched = new java.util.LinkedHashMap<>();
    private String dwellUrl;
    private long dwellSince;

    private static final class Prefetch {
        final long at = System.currentTimeMillis();
        final java.util.concurrent.FutureTask<YouTubeClient.Playable> task;
        Prefetch(final String url, final int h) {
            task = new java.util.concurrent.FutureTask<>(new java.util.concurrent.Callable<YouTubeClient.Playable>() {
                @Override public YouTubeClient.Playable call() throws Exception { return YouTubeClient.resolve(url, h); }
            });
        }
    }

    private static String prefetchKey(String url, int h) { return h + "|" + url; }

    /** Returns the (possibly still running) resolve for this video, starting one if there is none. */
    private synchronized java.util.concurrent.FutureTask<YouTubeClient.Playable> resolveFuture(String url, int h) {
        String key = prefetchKey(url, h);
        Prefetch p = prefetched.get(key);
        if (p != null && System.currentTimeMillis() - p.at > PREFETCH_TTL_MS) { prefetched.remove(key); p = null; }
        if (p == null) {
            p = new Prefetch(url, h);
            prefetched.put(key, p);
            while (prefetched.size() > 4) prefetched.remove(prefetched.keySet().iterator().next());
            prefetchExec.execute(p.task);
        }
        return p.task;
    }

    private synchronized void forgetPrefetch(String url, int h) { prefetched.remove(prefetchKey(url, h)); }

    private void prefetchTick(long now) {
        String url = null;
        if (mode == Mode.YOUTUBE && yt != null && loadingTitle == null) {
            YouTubeClient.Item it = yt.focusedVideo();
            if (it != null) url = it.url;
        }
        if (url == null) { dwellUrl = null; return; }
        if (!url.equals(dwellUrl)) { dwellUrl = url; dwellSince = now; return; }
        if (now - dwellSince >= PREFETCH_DWELL_MS) resolveFuture(url, YT_HEIGHTS[Settings.ytQuality]);   // no-op when already there
    }

    public void playYoutube(String url) { playYoutube(url, null); }

    /** Resolve a YouTube watch URL (network, off the main thread) and play it. BACK cancels while it loads. */
    @Override
    public void playYoutube(final String url, String title) {
        returnToYoutube = mode == Mode.YOUTUBE;
        loadingTitle = title == null || title.isEmpty() ? "YouTube video" : title;
        final int token = ++loadingToken;
        lastFull = 0;
        net.execute(new Runnable() {
            @Override public void run() {
                try {
                    final int h = YT_HEIGHTS[Settings.ytQuality];
                    YouTubeClient.Playable got;
                    try {
                        got = resolveFuture(url, h).get();
                    } catch (java.util.concurrent.ExecutionException ee) {
                        forgetPrefetch(url, h);                  // a failed resolve is not cached; one fresh try below
                        got = YouTubeClient.resolve(url, h);
                    }
                    final YouTubeClient.Playable p = got;
                    ui.post(new Runnable() { @Override public void run() {
                        if (token != loadingToken || loadingTitle == null) return;     // cancelled
                        loadingTitle = null;
                        startYoutube(p);
                    } });
                } catch (final Exception e) {
                    ui.post(new Runnable() { @Override public void run() {
                        if (token != loadingToken || loadingTitle == null) return;
                        loadingTitle = null;
                        toast("Couldn't load the video: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), 5000);
                        lastFull = 0;
                    } });
                }
            }
        });
    }

    private void cancelLoading() {
        loadingTitle = null;
        loadingToken++;
        toast("Cancelled");
    }

    private void startYoutube(YouTubeClient.Playable p) {
        if (!p.playable()) { toast("No playable stream for this video", 4000); return; }
        playlist = new ArrayList<>();
        playIndex = -1;
        playingYt = p;
        if (yt != null) yt.recordPlayed(p);
        engine.openYoutube(Uri.parse("youtube://" + p.id), p.title, p);
        mode = Mode.PLAYER;
        quickOpen = false;
        showHud();
        lastFull = 0;
    }

    // ---- YouTube UI -------------------------------------------------------------------------

    public void setYoutube(YouTubeUi y) { yt = y; }

    // ---- licence agreement / About and legal -------------------------------------------------

    private LegalUi legal;

    /** Hands over the legal pages; the first time the app runs, the licence agreement is shown before anything else. */
    public void setLegal(LegalUi l, boolean forceShow) {
        legal = l;
        if (forceShow || (!BuildConfig.DEBUG && Settings.eulaAccepted < LegalUi.EULA_VERSION)) {
            legal.open(true);
            mode = Mode.LEGAL;
        }
    }

    private boolean legalKey(int k) {
        switch (legal.onKey(k)) {
            case ACCEPT:
                Settings.eulaAccepted = LegalUi.EULA_VERSION;
                host.saveSettings();
                mode = Mode.LIBRARY;
                break;
            case EXIT:
                host.exitApp();
                break;
            case CLOSE:
                mode = Mode.SETTINGS;
                break;
            default:
                break;
        }
        return true;
    }

    public void openYoutubeUi() {
        if (yt == null) return;
        engine.close();
        overlay.bandActive = false;
        mode = Mode.YOUTUBE;
        yt.open();
        lastFull = 0;
    }

    private void leaveYoutube() {
        if (yt != null) yt.close();
        mode = Mode.LIBRARY;
        refreshEntries();
        lastFull = 0;
    }

    private boolean youtubeKey(int k, int repeat) {
        if (yt == null) { leaveYoutube(); return true; }
        if (yt.onKey(k, repeat)) return true;
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) { leaveYoutube(); return true; }
        return false;
    }

    /** From the playback panel: stop and show the playing video's channel in the YouTube screens. */
    private void goToChannel() {
        YouTubeClient.Playable p = playingYt;
        if (p == null || p.uploaderUrl == null || p.uploaderUrl.isEmpty() || yt == null) return;
        boolean wasYoutube = returnToYoutube;
        returnToYoutube = false;
        engine.close();
        overlay.bandActive = false;
        quickOpen = false;
        playingYt = null;
        mode = Mode.YOUTUBE;
        if (wasYoutube) yt.reshow(); else yt.open();
        yt.showChannel(p.uploaderUrl, p.uploader);
        lastFull = 0;
    }

    // ---- browser ----------------------------------------------------------------------------

    public void setBrowser(BrowserController b) {
        browser = b;
        b.setExitHandler(new Runnable() { @Override public void run() { leaveBrowser(); } });
    }

    public void openBrowser(String url) {
        if (browser == null) return;
        engine.close();
        overlay.bandActive = false;
        mode = Mode.BROWSER;
        browser.open(url);
        lastFull = 0;
    }

    private void leaveBrowser() {
        if (browser != null) browser.close();
        mode = Mode.LIBRARY;
        refreshEntries();
        lastFull = 0;
    }

    private boolean browserKey(int k, int repeat) {
        if (browser == null) { leaveBrowser(); return true; }
        if (browser.onKey(k, repeat)) return true;
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) { leaveBrowser(); return true; }
        return false;
    }

    // ---- input ------------------------------------------------------------------------------

    /** Short press (called on key-up for OK, on key-down for everything else). Returns true if handled. */
    public boolean onKey(int keyCode, int repeat) {
        boolean menu = mode != Mode.BROWSER && (mode != Mode.PLAYER || quickOpen);
        int before = menu ? soundSig() : 0;
        boolean r = handleKey(keyCode, repeat);
        if (menu && Settings.soundLevel > 0) {
            boolean stillMenu = mode != Mode.BROWSER && (mode != Mode.PLAYER || quickOpen);
            if (soundSig() != before || !stillMenu) {
                if (isOk(keyCode)) Sounds.confirm(); else Sounds.move();
            }
        }
        return r;
    }

    /** What a key press can visibly change in a menu (selection, values, screen). */
    private int soundSig() {
        long now = System.currentTimeMillis();
        int h = fullSig(now) - (now < toastUntil ? toast.hashCode() * 31 : 0);
        if (mode == Mode.YOUTUBE && yt != null) h = h * 31 + yt.bottomHash();
        return h;
    }

    private boolean handleKey(int keyCode, int repeat) {
        lastFull = 0;   // force a repaint
        if (loadingTitle != null) {         // a video is loading: BACK cancels, everything else waits
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) cancelLoading();
            return true;
        }
        if (mode == Mode.LEGAL) return legalKey(keyCode);
        if (isMenuKey(keyCode)) {
            if (mode == Mode.SETTINGS) mode = settingsReturn;
            else if (mode == Mode.PLAYER) { quickOpen = !quickOpen; quickSel = 0; }
            else openSettings();
            return true;
        }
        switch (mode) {
            case LIBRARY: return libraryKey(keyCode);
            case PLAYER: return quickOpen ? quickKey(keyCode) : playerKey(keyCode, repeat);
            case BROWSER: return browserKey(keyCode, repeat);
            case YOUTUBE: return youtubeKey(keyCode, repeat);
            default: return settingsKey(keyCode);
        }
    }

    private List<File> panoramas = new ArrayList<>();
    private boolean panoramasScanning = false;

    private void scanPanoramas() {
        if (panoramasScanning) return;
        panoramasScanning = true;
        new Thread(new Runnable() {
            @Override public void run() {
                final List<File> l = Backdrops.findPanoramas();
                ui.post(new Runnable() { @Override public void run() {
                    panoramas = l;
                    panoramasScanning = false;
                } });
            }
        }, "panorama-scan").start();
    }

    private void openSettings() {
        scanPanoramas();
        settingsReturn = mode;
        mode = Mode.SETTINGS;
        quickOpen = false;
        settingsSel = 1;          // first line is a section title
        settingsScroll = 0;
    }

    private static boolean isMenuKey(int k) {
        return k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_SETTINGS || k == KeyEvent.KEYCODE_INFO
                || k == KeyEvent.KEYCODE_BUTTON_Y || k == KeyEvent.KEYCODE_TV_CONTENTS_MENU
                || k == KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU;
    }

    private static boolean isOk(int k) {
        return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
    }

    private boolean libraryKey(int k) {
        if (k != KeyEvent.KEYCODE_BACK) exitArmedUntil = 0;
        boolean onShelf = selectable(sel) && entries.get(sel).type == Library.SHELF;
        boolean managing = Library.KEY_MANAGE.equals(folderKey);
        if (!isOk(k)) removeArmed = -1;
        if (isOk(k)) {
            if (onShelf) {
                if (shelfCol < shelf.size()) activate(shelf.get(shelfCol));
                else enter(Library.KEY_MANAGE);
            } else if (managing && selectable(sel)) {
                manageOk(entries.get(sel));
            } else if (selectable(sel)) {
                activate(entries.get(sel));
            }
            return true;
        }
        if (onShelf && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            moveShelf(k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            return true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                moveSel(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveSel(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (selectable(sel)) {
                    int t = entries.get(sel).type;
                    if (t == Library.FOLDER || t == Library.VOLUME) activate(entries.get(sel));
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_ESCAPE:
                goUp();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (folderKey != null) {
                    goUp();
                } else if (System.currentTimeMillis() < exitArmedUntil) {
                    host.exitApp();
                } else {
                    exitArmedUntil = System.currentTimeMillis() + 2500;
                    toast("Press BACK again to exit", 2500);
                }
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                moveSel(6);
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
                moveSel(-6);
                return true;
            default:
                return false;
        }
    }

    private boolean playerKey(int k, int repeat) {
        showHud();
        if (isOk(k) || k == KeyEvent.KEYCODE_SPACE || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            engine.togglePlay();
            return true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                engine.seekBy(repeat > 6 ? -30000 : -10000);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                engine.seekBy(repeat > 6 ? 30000 : 10000);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                quickOpen = true;       // no MENU key needed: Up or Down opens the playback options
                quickSel = 0;
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                advance(1, false);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_PAGE_UP:
                advance(-1, false);
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                leavePlayback();
                return true;
            default:
                return false;
        }
    }

    // ---- playback options panel (Up / Down while a video plays) -----------------------------

    private static final int Q_AUDIO = 0, Q_SUBS = 1, Q_SPEED = 2, Q_VOLUME = 3, Q_SIZE = 4, Q_CHANNEL = 5,
            Q_RECENTRE = 6, Q_SETTINGS = 7, Q_QUALITY = 8;
    private boolean quickOpen = false;
    private int quickSel = 0;

    private List<Integer> quickRows() {
        List<Integer> l = new ArrayList<>();
        l.add(Q_AUDIO);
        l.add(Q_SUBS);
        if (BuildConfig.YOUTUBE && playingYt != null && !playingYt.live) l.add(Q_QUALITY);
        l.add(Q_SPEED);
        l.add(Q_VOLUME);
        l.add(Q_SIZE);
        if (playingYt != null && playingYt.uploaderUrl != null && !playingYt.uploaderUrl.isEmpty()) l.add(Q_CHANNEL);
        l.add(Q_RECENTRE);
        l.add(Q_SETTINGS);
        return l;
    }

    private static boolean quickIsValue(int id) { return id <= Q_SIZE || id == Q_QUALITY; }

    /** Audio / subtitles with nothing to switch to show no arrows. */
    private boolean quickAdjustable(int id) {
        if (id == Q_AUDIO) return engine.getAudioTracks().size() > 1;
        if (id == Q_SUBS) return !engine.getTextTracks().isEmpty();
        return true;
    }

    private boolean quickKey(int k) {
        List<Integer> rows = quickRows();
        quickSel = Math.max(0, Math.min(rows.size() - 1, quickSel));
        int id = rows.get(quickSel);
        if (isOk(k)) {
            switch (id) {
                case Q_CHANNEL: goToChannel(); break;
                case Q_QUALITY: changeQuality((Settings.ytQuality + 1) % YT_HEIGHTS.length); break;   // OK cycles round
                case Q_RECENTRE: host.recenter(); toast("View recentred"); quickOpen = false; break;
                case Q_SETTINGS: openSettings(); settingsReturn = Mode.PLAYER; break;
                default: quickAdjust(id, 1); break;
            }
            return true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (quickSel > 0) quickSel--;
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (quickSel < rows.size() - 1) quickSel++;
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (quickIsValue(id)) quickAdjust(id, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (quickIsValue(id)) quickAdjust(id, 1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                engine.togglePlay();
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                quickOpen = false;
                showHud();
                return true;
            default:
                return false;
        }
    }

    private void quickAdjust(int id, int d) {
        switch (id) {
            case Q_AUDIO: engine.cycleAudio(d); break;
            case Q_SUBS: engine.cycleSubtitles(d); break;
            case Q_SPEED: engine.setSpeed(Math.round((engine.getSpeed() + 0.25f * d) * 4f) / 4f); break;
            case Q_VOLUME: engine.adjustVolume(0.05f * d); host.saveSettings(); break;
            case Q_SIZE: renderer.setScreenWidthDeg(renderer.getScreenWidthDeg() + 2f * d); host.saveSettings(); break;
            case Q_QUALITY: changeQuality(step(Settings.ytQuality, d, YT_HEIGHTS.length, false)); break;
            default: break;
        }
    }

    private String quickLabel(int id) {
        switch (id) {
            case Q_AUDIO: return "Audio";
            case Q_SUBS: return "Subtitles";
            case Q_SPEED: return "Speed";
            case Q_VOLUME: return "Volume";
            case Q_SIZE: return "Screen size";
            case Q_QUALITY: return "Quality";
            case Q_CHANNEL: return "Go to channel";
            case Q_RECENTRE: return "Recentre view";
            default: return "All settings";
        }
    }

    private String quickValue(int id) {
        switch (id) {
            case Q_AUDIO: return engine.audioLabel();
            case Q_SUBS: return engine.subtitleLabel();
            case Q_SPEED: return speedLabel(engine.getSpeed());
            case Q_VOLUME: return Math.round(engine.getVolume() * 100) + "%";
            case Q_SIZE: return String.format(Locale.US, "%.0f°", renderer.getScreenWidthDeg());
            case Q_CHANNEL: return playingYt != null ? playingYt.uploader : "";
            case Q_QUALITY: return qualityValue();
            default: return "";
        }
    }

    // ---- YouTube quality from the Options panel ---------------------------------------------------
    // Left/Right changes the highest resolution to ask for (also the default for later videos). After a short pause the
    // video is re-resolved at that quality (the prefetch cache makes a revisited quality instant) and reopened at the
    // current position; until then the old stream keeps playing.

    private static final String[] YT_SHORT = {"360p", "480p", "720p", "1080p", "1440p", "4K"};
    private long qualityApplyAt = 0;
    private int qualitySwitchToken = 0;

    private void changeQuality(int q) {
        if (q == Settings.ytQuality) return;
        Settings.ytQuality = q;
        host.saveSettings();
        qualityApplyAt = System.currentTimeMillis() + 1000;
    }

    /** The limit, and what is actually playing when the video has nothing that high. */
    private String qualityValue() {
        String v = YT_SHORT[Settings.ytQuality];
        if (playingYt != null && playingYt.height > 0 && playingYt.height < YT_HEIGHTS[Settings.ytQuality]) v += " (" + playingYt.height + "p)";
        return v;
    }

    private void qualityTick(long now) {
        if (qualityApplyAt == 0 || now < qualityApplyAt) return;
        qualityApplyAt = 0;
        final YouTubeClient.Playable cur = playingYt;
        if (cur == null || cur.live || cur.id == null || cur.id.isEmpty()) return;
        final int h = YT_HEIGHTS[Settings.ytQuality];
        final int token = ++qualitySwitchToken;
        final String url = "https://www.youtube.com/watch?v=" + cur.id;
        toast("Quality: " + YT_SHORT[Settings.ytQuality] + "\u2026", 4000);
        net.execute(new Runnable() {
            @Override public void run() {
                try {
                    final YouTubeClient.Playable p = resolveFuture(url, h).get();
                    ui.post(new Runnable() { @Override public void run() {
                        if (token != qualitySwitchToken || playingYt != cur) return;     // another change, or a different video
                        if (p.height == cur.height) { toast(p.height + "p is the best this video has", 2500); return; }
                        playingYt = p;
                        engine.openYoutube(Uri.parse("youtube://" + p.id), p.title, p, engine.getPositionMs());
                        toast("Playing " + p.height + "p", 2500);
                    } });
                } catch (final Exception e) {
                    forgetPrefetch(url, h);
                    ui.post(new Runnable() { @Override public void run() {
                        if (token == qualitySwitchToken) toast("Couldn't change quality", 3000);
                    } });
                }
            }
        });
    }

    private static String speedLabel(float s) {
        return Math.abs(s - 1f) < 0.01f ? "Normal" : String.format(Locale.US, "%.2gx", s);
    }

    // ---- settings ---------------------------------------------------------------------------

    private static final int S_SIZE = 0, S_CURVE = 1, S_ROOM = 2, S_VOLUME = 3, S_YTQ = 4, S_SMOOTH = 5, S_HZ = 6,
            S_RECENTRE = 7, S_GYRO = 8, S_PSPEED = 9, S_FOV = 10, S_PREDICT = 11, S_PAXIS = 12, S_ARROW = 13,
            S_ARROW_WHEN = 14, S_SOUNDS = 15, S_BACKGROUND = 16, S_SHORTS = 17, S_PANO = 18, S_PANO_TURN = 19, S_LEGAL = 20;
    /** Settings page lines: >= 0 a setting id, < 0 a section title (index -1 - n into SECTION_NAMES). */
    private static final int[] SETTINGS_LINES = withoutYoutube(new int[] {
            -1, S_BACKGROUND, S_PANO, S_PANO_TURN, S_ROOM, S_SIZE, S_CURVE,
            -2, S_VOLUME, S_SOUNDS, S_YTQ, S_SHORTS,
            -3, S_SMOOTH, S_HZ, S_RECENTRE, S_ARROW, S_ARROW_WHEN,
            -4, S_GYRO, S_PSPEED,
            -5, S_FOV, S_PREDICT, S_PAXIS,
            -6, S_LEGAL});

    /** The core edition has no YouTube, so its two settings are left out of the page. */
    private static int[] withoutYoutube(int[] lines) {
        if (BuildConfig.YOUTUBE) return lines;
        int[] out = new int[lines.length];
        int n = 0;
        for (int l : lines) if (l != S_YTQ && l != S_SHORTS) out[n++] = l;
        return java.util.Arrays.copyOf(out, n);
    }
    private static final String[] SECTION_NAMES = {"Screen & surroundings", "Playback", "Head tracking", "Air mouse (browser)",
            "Advanced: calibration", "About"};
    private static final String[] SETTING_NAMES = {"Screen size", "Screen curve (video)", "Background brightness", "Volume",
            "YouTube quality", "Smoothing", "Refresh rate", "Recentre view", "Air mouse", "Pointer speed",
            "Field of view", "Prediction", "Pointer axis", "Off-screen arrow", "Arrow appears when", "Menu sounds", "Background", "YouTube Shorts", "360\u00b0 photo",
            "Turn photo", "About and legal"};
    private static final String[] SETTING_HELP = {
            "How wide the screen looks. Text is easiest to read at 30-36°.",
            "Wraps the screen around you while a video plays. Menus are always shown flat.",
            "How bright the surroundings are. Lower is calmer.",
            "Playback volume inside the app.",
            "Highest YouTube resolution to ask for. Lower starts faster on slow Wi-Fi.",
            "Steadies the screen while your head is still. Off is raw tracking.",
            "120 Hz makes head movement smoother when the glasses support it.",
            "Makes where you are looking straight ahead. Same as holding OK anywhere.",
            "Point the Pocket TV at the screen to move the browser cursor.",
            "How far the cursor moves when you turn the Pocket TV.",
            "Match this to your glasses. A wrong value makes the room swim as you turn.",
            "Looks ahead to hide tracking delay. Too much overshoots when you stop.",
            "Which end of the Pocket TV points at the screen, for the air mouse.",
            "When the screen is out of view, an arrow at the edge of your view points back to it.",
            "How far the screen must leave your view before the arrow shows.",
            "Short clicks when you move and select in menus. Never during a video.",
            "What surrounds the screen. Pure black is see-through on the glasses.",
            "Hide Shorts in search, your subscriptions feed and channel pages.",
            "Any 2:1 (equirectangular) image in Pictures/Panoramas or Download on the device. Left/Right to pick one.",
            "Turns the photo around you, to put the part you like behind or beside the screen.",
            "The licence agreement, privacy policy, and notices for the software this app includes."};
    private static final String[] SOUND_NAMES = {"Off", "Quiet", "Normal"};
    private static final String[] ARROW_WHEN = {"Fully out of view", "Mostly out", "Half out"};
    private static final int SETTINGS_VISIBLE = 12, SET_LINE_H = 60, SET_TOP = 150;
    private int settingsSel = 1, settingsScroll = 0;
    private static final String[] SMOOTH_NAMES = {"Off", "Low", "Medium", "High"};
    private static final String[] YT_LABELS = {"360p", "480p", "720p", "1080p", "1440p", "2160p (4K)"};
    private static final int[] YT_HEIGHTS = {360, 480, 720, 1080, 1440, 2160};
    private static final String[] AXIS_NAMES = {"+X", "-X", "+Y", "-Y", "+Z (screen)", "-Z (back)"};

    // defaults (SceneRenderer / Settings / MediaEngine initial values) for the "changed" marker
    private static final float D_SIZE = 32f, D_CURVE = 0f, D_ROOM = 0.35f, D_FOV = 23.5f, D_VOLUME = 1f;

    private int settingId() {
        if (settingsSel < 0 || settingsSel >= SETTINGS_LINES.length) return -1;
        return SETTINGS_LINES[settingsSel];
    }

    private static boolean isAction(int id) { return id == S_RECENTRE || id == S_LEGAL; }

    private boolean settingsKey(int k) {
        int id = settingId();
        if (isOk(k)) {
            if (id == S_RECENTRE) { host.recenter(); toast("View recentred"); mode = settingsReturn; }
            else if (id == S_LEGAL) { legal.open(false); mode = Mode.LEGAL; }
            else if (id >= 0) adjustSetting(id, 1, true);
            return true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                moveSetting(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveSetting(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (id >= 0 && !isAction(id)) adjustSetting(id, -1, false);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (id >= 0 && !isAction(id)) adjustSetting(id, 1, false);
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                mode = settingsReturn;
                if (mode == Mode.PLAYER) { quickOpen = true; quickSel = quickRows().size() - 1; }
                return true;
            default:
                return false;
        }
    }

    private void moveSetting(int d) {
        int n = settingsSel + d;
        while (n >= 0 && n < SETTINGS_LINES.length && SETTINGS_LINES[n] < 0) n += d;
        if (n < 0 || n >= SETTINGS_LINES.length) return;    // no wrap
        settingsSel = n;
        if (settingsSel < settingsScroll) settingsScroll = settingsSel;
        if (settingsScroll == settingsSel && settingsSel > 0 && SETTINGS_LINES[settingsSel - 1] < 0) settingsScroll--;
        if (settingsSel >= settingsScroll + SETTINGS_VISIBLE) settingsScroll = settingsSel - SETTINGS_VISIBLE + 1;
        settingsScroll = Math.max(0, Math.min(SETTINGS_LINES.length - SETTINGS_VISIBLE, settingsScroll));
    }

    /** wrap = OK pressed: toggles and choices cycle round; numbers step up. */
    private void adjustSetting(int id, int d, boolean wrap) {
        switch (id) {
            case S_VOLUME: engine.adjustVolume(0.05f * d); break;
            case S_SIZE: renderer.setScreenWidthDeg(renderer.getScreenWidthDeg() + 2f * d); break;
            case S_FOV: renderer.setFov(renderer.getFov() + 0.5f * d); break;
            case S_ROOM: renderer.setEnvBrightness(renderer.getEnvBrightness() + 0.1f * d); break;
            case S_CURVE: renderer.setCurve(renderer.getCurve() + 0.25f * d); break;
            case S_SMOOTH: Settings.smoothing = step(Settings.smoothing, d, 4, wrap); break;
            case S_PREDICT: Settings.predictMs = Math.max(0, Math.min(30, Settings.predictMs + 2 * d)); break;
            case S_HZ:
                Settings.hz120 = !Settings.hz120;
                host.applyDisplaySettings();
                break;
            case S_GYRO: Settings.gyroPointer = !Settings.gyroPointer; break;
            case S_PSPEED: Settings.pointerSens = Math.max(400, Math.min(4000, Settings.pointerSens + 200 * d)); break;
            case S_PAXIS: Settings.pointerAxis = ((Settings.pointerAxis + d) % 6 + 6) % 6; break;
            case S_YTQ: Settings.ytQuality = step(Settings.ytQuality, d, 6, wrap); break;
            case S_ARROW: Settings.lostArrow = !Settings.lostArrow; break;
            case S_ARROW_WHEN: Settings.lostWhen = step(Settings.lostWhen, d, 3, wrap); break;
            case S_SOUNDS: Settings.soundLevel = step(Settings.soundLevel, d, 3, wrap); break;
            case S_BACKGROUND:
                Settings.background = step(Settings.background, d, Backdrops.NAMES.length, true);
                if (Settings.background == Backdrops.PANORAMA && !panoramas.isEmpty()) {
                    boolean present = false;        // the saved photo may have been deleted since
                    for (File f : panoramas) if (f.getPath().equals(Settings.panoramaFile)) present = true;
                    if (!present) Settings.panoramaFile = panoramas.get(0).getPath();
                }
                break;
            case S_PANO: {
                if (panoramas.isEmpty()) return;
                int i = 0;
                for (int j = 0; j < panoramas.size(); j++) if (panoramas.get(j).getPath().equals(Settings.panoramaFile)) i = j;
                Settings.panoramaFile = panoramas.get(((i + d) % panoramas.size() + panoramas.size()) % panoramas.size()).getPath();
                Settings.background = Backdrops.PANORAMA;       // picking a photo shows it
                break;
            }
            case S_PANO_TURN: Settings.panoramaTurn = ((Settings.panoramaTurn + 15 * d) % 360 + 360) % 360; break;
            case S_SHORTS: Settings.hideShorts = !Settings.hideShorts; break;
            default: return;
        }
        host.saveSettings();
    }

    private static int step(int v, int d, int n, boolean wrap) {
        return wrap ? ((v + d) % n + n) % n : Math.max(0, Math.min(n - 1, v + d));
    }

    private String settingValue(int id) {
        switch (id) {
            case S_VOLUME: return Math.round(engine.getVolume() * 100) + "%";
            case S_SIZE: return String.format(Locale.US, "%.0f°", renderer.getScreenWidthDeg());
            case S_FOV: return String.format(Locale.US, "%.1f°", renderer.getFov());
            case S_ROOM: return Math.round(renderer.getEnvBrightness() * 100) + "%";
            case S_CURVE: return renderer.getCurve() <= 0.01f ? "Flat" : String.format(Locale.US, "%.0f%%", renderer.getCurve() * 100);
            case S_SMOOTH: return SMOOTH_NAMES[Settings.smoothing];
            case S_PREDICT: return Settings.predictMs + " ms";
            case S_HZ: return Settings.hz120 ? "120 Hz" : "60 Hz";
            case S_GYRO: return Settings.gyroPointer ? "On" : "Off";
            case S_PSPEED: return String.valueOf(Settings.pointerSens / 100);
            case S_PAXIS: return AXIS_NAMES[Settings.pointerAxis];
            case S_YTQ: return "Up to " + YT_LABELS[Settings.ytQuality];
            case S_ARROW: return Settings.lostArrow ? "On" : "Off";
            case S_ARROW_WHEN: return Settings.lostArrow ? ARROW_WHEN[Settings.lostWhen] : "(arrow off)";
            case S_SOUNDS: return SOUND_NAMES[Settings.soundLevel];
            case S_BACKGROUND: return Backdrops.NAMES[Settings.background];
            case S_PANO: {
                if (panoramasScanning) return "Looking\u2026";
                if (panoramas.isEmpty()) return "None found";
                String n = new File(Settings.panoramaFile).getName();
                return Settings.panoramaFile.isEmpty() ? panoramas.size() + " found" : Ui.ellipsize(n, text, 520);
            }
            case S_PANO_TURN: return Settings.panoramaTurn + "\u00b0";
            case S_SHORTS: return Settings.hideShorts ? "Hidden" : "Shown";
            default: return "";
        }
    }

    private boolean settingChanged(int id) {
        switch (id) {
            case S_VOLUME: return Math.abs(engine.getVolume() - D_VOLUME) > 0.01f;
            case S_SIZE: return Math.abs(renderer.getScreenWidthDeg() - D_SIZE) > 0.1f;
            case S_FOV: return Math.abs(renderer.getFov() - D_FOV) > 0.05f;
            case S_ROOM: return Math.abs(renderer.getEnvBrightness() - D_ROOM) > 0.01f;
            case S_CURVE: return Math.abs(renderer.getCurve() - D_CURVE) > 0.01f;
            case S_SMOOTH: return Settings.smoothing != 2;
            case S_PREDICT: return Settings.predictMs != 8;
            case S_HZ: return !Settings.hz120;
            case S_GYRO: return Settings.gyroPointer;
            case S_PSPEED: return Settings.pointerSens != 1400;
            case S_PAXIS: return Settings.pointerAxis != 2;
            case S_YTQ: return Settings.ytQuality != 3;
            case S_ARROW: return !Settings.lostArrow;
            case S_ARROW_WHEN: return Settings.lostWhen != 0;
            case S_SOUNDS: return Settings.soundLevel != 1;
            case S_BACKGROUND: return Settings.background != Backdrops.NIGHT;
            case S_PANO_TURN: return Settings.panoramaTurn != 0;
            case S_SHORTS: return !Settings.hideShorts;
            default: return false;
        }
    }

    /** Long-press on OK anywhere: recentre the view. */
    public void onLongPressOk() {
        host.recenter();
        toast("View recentred");
        lastFull = 0;
    }

    // ---- painting ---------------------------------------------------------------------------

    /** Whole-panel repaint. In playback mode the bottom strip is painted separately into the band. */
    private void paintAll() {
        synchronized (overlay.lock) {
            Canvas c = overlay.canvas;
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            long now = System.currentTimeMillis();
            switch (mode) {
                case LIBRARY:
                    paintLibrary(c);
                    overlay.bandActive = false;
                    break;
                case PLAYER:
                    paintPlayerCenter(c);
                    if (quickOpen) paintQuick(c);
                    paintBandLocked(now);
                    overlay.bandActive = true;
                    overlay.bandDirty = true;
                    break;
                case BROWSER:
                    if (browser != null) { browser.paintTop(c); paintBandLocked(now); }
                    overlay.bandActive = true;
                    overlay.bandDirty = true;
                    break;
                case YOUTUBE:
                    if (yt != null) yt.paintTop(c);
                    // the bottom strip is a separate texture: only use it for the keyboard, otherwise the list
                    // would be cut off at the strip
                    boolean kbOpen = yt != null && yt.keyboardOpen();
                    if (kbOpen) paintBandLocked(now);
                    overlay.bandActive = kbOpen;
                    overlay.bandDirty = kbOpen;
                    break;
                case LEGAL:
                    if (legal != null) legal.paint(c);
                    overlay.bandActive = false;
                    break;
                default:
                    if (settingsReturn == Mode.LIBRARY) paintLibrary(c);
                    else if (settingsReturn == Mode.YOUTUBE && yt != null) yt.paintTop(c);
                    paintSettings(c);
                    overlay.bandActive = false;
                    break;
            }
            if (loadingTitle != null) paintLoading(c, now);
            if (now < toastUntil) paintToast(c);
            overlay.fullDirty = true;
        }
    }

    private void paintBandOnly() {
        synchronized (overlay.lock) {
            paintBandLocked(System.currentTimeMillis());
            overlay.bandDirty = true;
        }
    }

    private void paintBandLocked(long now) {
        Canvas c = overlay.bandCanvas;
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        c.save();
        c.translate(0, -(Overlay.H - Overlay.BAND_H));   // draw with full-panel coordinates
        if (mode == Mode.BROWSER) { if (browser != null) browser.paintBottom(c); }
        else if (mode == Mode.YOUTUBE) { if (yt != null) yt.paintBottom(c); }
        else {
            paintBand(c, now);
            if (quickOpen) paintQuick(c);       // the panel crosses into the strip: paint its lower part here too
        }
        c.restore();
    }

    private void paintLibrary(Canvas c) {
        Ui.page(c);
        text.setColor(Ui.TEXT);
        text.setTextSize(64);
        text.setFakeBoldText(true);
        c.drawText(Ui.ellipsize(headerTitle(), text, 1150), 110, 140, text);
        text.setFakeBoldText(false);
        Ui.status(c, 1810, 132, clockFmt.format(new Date()), host.batteryPercent(), host.glassesConnected());
        text.setTextSize(32);
        text.setColor(folderKey == null && !host.glassesConnected() ? Ui.AMBER : Ui.TEXT_DIM);
        c.drawText(Ui.ellipsizeStart(headerSub(), text, 1600), 110, 192, text);

        int y = LIST_TOP;
        int last = scroll;
        for (int idx = scroll; idx < entries.size(); idx++) {
            int h = rowH(idx);
            if (y + h > LIST_BOTTOM + 4) break;
            Library.Entry e = entries.get(idx);
            if (e.type == Library.HEADER) paintHeaderRow(c, e.name, y);
            else if (e.type == Library.SHELF) paintShelf(c, y, idx == sel);
            else paintRow(c, e, y, idx == sel);
            y += h;
            last = idx;
        }
        if (scroll > 0 || last < entries.size() - 1) {      // scrollbar
            float trackTop = LIST_TOP, trackH = LIST_BOTTOM - LIST_TOP;
            float shown = Math.max(1, last - scroll + 1);
            float thumbH = Math.max(60, trackH * shown / entries.size());
            float yy = trackTop + (trackH - thumbH) * scroll / Math.max(1, entries.size() - shown);
            fill.setColor(Color.argb(90, 255, 255, 255));
            rect.set(1790, yy, 1802, yy + thumbH);
            c.drawRoundRect(rect, 6, 6, fill);
        }
        paintLibraryHints(c);
    }

    private void paintLibraryHints(Canvas c) {
        String ok = null;
        String back0 = folderKey == null ? (System.currentTimeMillis() < exitArmedUntil ? "Exit now" : "Exit") : "Up a folder";
        if (selectable(sel) && entries.get(sel).type == Library.SHELF) {
            Ui.hints(c, 110, Ui.HINT_Y, Ui.K_OK, shelfCol < shelf.size() ? "Resume" : "Edit list", Ui.K_LR, "Choose",
                    Ui.K_BACK, back0, Ui.K_HOLD_OK, "Recentre");
            return;
        }
        if (Library.KEY_MANAGE.equals(folderKey)) {
            boolean noop = selectable(sel) && "noop".equals(entries.get(sel).key);
            Ui.hints(c, 110, Ui.HINT_Y, Ui.K_OK, noop ? null : armed(sel) ? "Remove now" : "Remove", Ui.K_BACK, "Done");
            return;
        }
        if (selectable(sel)) {
            switch (entries.get(sel).type) {
                case Library.VIDEO: case Library.STREAM: ok = "Play"; break;
                case Library.RESUME: ok = "Resume"; break;
                case Library.ACTION: ok = "noop".equals(entries.get(sel).key) ? null : "Allow"; break;
                default: ok = "Open"; break;
            }
        }
        String back = folderKey == null ? (System.currentTimeMillis() < exitArmedUntil ? "Exit now" : "Exit") : "Up a folder";
        Ui.hints(c, 110, Ui.HINT_Y, Ui.K_OK, ok, Ui.K_BACK, back, Ui.K_HOLD_OK, "Recentre");
    }

    /** Continue watching as cards: YouTube thumbnails, or a play card for local files; progress under each. */
    private void paintShelf(Canvas c, int y, boolean selected) {
        for (int i = shelfFirst; i <= shelf.size() && i < shelfFirst + CARDS_VISIBLE; i++) {
            float x = CARD_X0 + (i - shelfFirst) * CARD_STEP;
            boolean s = selected && i == shelfCol;
            if (i == shelf.size()) {
                Ui.editCard(c, x, y + 4, CARD_W, CARD_H, s);
                break;
            }
            Library.Entry e = shelf.get(i);
            rect.set(x, y + 4, x + CARD_W, y + 4 + CARD_H);
            android.graphics.Bitmap b = null;
            boolean isYt = e.uri != null && "youtube".equals(e.uri.getScheme());
            if (isYt && yt != null) {
                b = yt.thumbFor("https://i.ytimg.com/vi/" + e.uri.toString().substring("youtube://".length()) + "/mqdefault.jpg");
            }
            if (b != null) {
                c.drawBitmap(b, null, rect, bmpPaint);
            } else {
                fill.setColor(Color.rgb(34, 42, 54));
                c.drawRoundRect(rect, 10, 10, fill);
                if (isYt) {
                    paintYoutubeIcon(c, x + CARD_W / 2f, y + 4 + CARD_H / 2f);
                } else {
                    fill.setColor(Ui.ACCENT);
                    float cx = x + CARD_W / 2f, cy = y + 4 + CARD_H / 2f;
                    path.reset();
                    path.moveTo(cx - 18, cy - 26);
                    path.lineTo(cx - 18, cy + 26);
                    path.lineTo(cx + 26, cy);
                    path.close();
                    c.drawPath(path, fill);
                }
            }
            if (e.durationMs > 0) {          // progress along the bottom of the picture
                float f = Math.min(1f, e.resumeMs / (float) e.durationMs);
                fill.setColor(Color.argb(150, 0, 0, 0));
                c.drawRect(x, y + CARD_H - 4, x + CARD_W, y + CARD_H + 4, fill);
                fill.setColor(Ui.ACCENT);
                c.drawRect(x, y + CARD_H - 4, x + CARD_W * f, y + CARD_H + 4, fill);
            }
            if (s) {
                stroke.setColor(Ui.ACCENT);
                stroke.setStrokeWidth(6);
                rect.set(x - 5, y - 1, x + CARD_W + 5, y + 9 + CARD_H);
                c.drawRoundRect(rect, 12, 12, stroke);
            }
            text.setColor(s ? Ui.TEXT : Color.rgb(196, 204, 214));
            text.setTextSize(30);
            c.drawText(Ui.ellipsize(e.name, text, CARD_W), x, y + CARD_H + 46, text);
            text.setColor(Ui.TEXT_DIM);
            text.setTextSize(26);
            c.drawText(Ui.ellipsize(e.sub, text, CARD_W), x, y + CARD_H + 80, text);
        }
        text.setTextSize(46);
        text.setColor(Ui.TEXT_DIM);
        if (shelfFirst > 0) c.drawText("\u2039", 76, y + 98, text);
        if (shelfFirst + CARDS_VISIBLE <= shelf.size()) c.drawText("\u203A", 1712, y + 98, text);
    }

    private void paintHeaderRow(Canvas c, String title, int y) {
        text.setColor(Ui.TEXT_DIM);
        text.setTextSize(30);
        text.setFakeBoldText(true);
        c.drawText(title.toUpperCase(Locale.getDefault()), 112, y + 46, text);
        text.setFakeBoldText(false);
    }

    private String headerTitle() {
        if (folderKey == null) return "Home";
        if (Library.KEY_MEDIASTORE.equals(folderKey)) return "All videos";
        if (Library.KEY_WEB.equals(folderKey)) return "Browser";
        if (Library.KEY_MANAGE.equals(folderKey)) return "Edit Continue watching";
        if (folderKey.startsWith(Library.KEY_DIR)) {
            String p = folderKey.substring(Library.KEY_DIR.length());
            if (library.volumeRoots().contains(p)) {
                for (Library.Entry e : library.volumes()) if (p.equals(e.path)) return e.name;
            }
            return new File(p).getName();
        }
        return folderKey;
    }

    private String headerSub() {
        if (folderKey != null && folderKey.startsWith(Library.KEY_DIR)) {
            return folderKey.substring(Library.KEY_DIR.length()).replace("/storage/emulated/0", "Internal storage");
        }
        if (folderKey == null) {
            return host.glassesConnected() ? host.trackingStatus()
                    : "Glasses not connected: no head tracking, the screen stays in front of you.";
        }
        if (Library.KEY_WEB.equals(folderKey)) return "Pages open in the built-in browser";
        if (Library.KEY_MANAGE.equals(folderKey)) return "OK on an item removes it (press OK twice to confirm)";
        return "";
    }

    private void paintRow(Canvas c, Library.Entry e, int y, boolean selected) {
        boolean arm = selected && Library.KEY_MANAGE.equals(folderKey) && armed(sel);
        rect.set(90, y, 1770, y + ROW_H - 10);
        fill.setColor(arm ? Ui.REMOVE : selected ? Ui.ROW_SEL : Ui.PANEL);
        c.drawRoundRect(rect, 18, 18, fill);
        if (arm) {
            e = new Library.Entry(e.type, e.name, "clearall".equals(e.key) ? "Press OK again to clear everything"
                    : "Press OK again to remove", e.uri, e.key, e.path, e.durationMs, e.resumeMs);
        }
        if (selected) {
            fill.setColor(Ui.ACCENT);
            rect.set(90, y, 100, y + ROW_H - 10);
            c.drawRoundRect(rect, 5, 5, fill);
        }
        paintIcon(c, e, y);

        boolean progress = e.resumeMs > 0 && e.durationMs > 0;
        float textW = progress ? 1200 : 1450;
        text.setColor(Ui.TEXT);
        text.setTextSize(42);
        c.drawText(Ui.ellipsize(e.name, text, textW), 230, y + 46, text);
        text.setTextSize(30);
        text.setColor(Ui.TEXT_DIM);
        c.drawText(e.sub == null ? "" : Ui.ellipsize(e.sub, text, textW), 230, y + 82, text);

        if (progress) {
            float f = Math.min(1f, e.resumeMs / (float) e.durationMs);
            fill.setColor(Color.argb(90, 255, 255, 255));
            rect.set(1480, y + 43, 1720, y + 51);
            c.drawRoundRect(rect, 4, 4, fill);
            fill.setColor(Ui.ACCENT);
            rect.set(1480, y + 43, 1480 + 240 * f, y + 51);
            c.drawRoundRect(rect, 4, 4, fill);
        }
        if (e.type == Library.FOLDER || e.type == Library.VOLUME || Library.KEY_WEB.equals(e.key)) {
            text.setTextSize(46);
            text.setColor(Ui.TEXT_DIM);
            c.drawText("›", 1725, y + 58, text);
        }
    }

    private void paintIcon(Canvas c, Library.Entry e, int y) {
        int type = e.type;
        if (type == Library.APP && Library.KEY_YOUTUBE.equals(e.key)) {
            paintYoutubeIcon(c, 165, y + 47);
            return;
        }
        if (type == Library.RESUME && e.uri != null && "youtube".equals(e.uri.getScheme())) {
            paintYoutubeIcon(c, 165, y + 47);
            return;
        }
        switch (type) {
            case Library.FOLDER:
                fill.setColor(Ui.AMBER);
                rect.set(135, y + 30, 195, y + 68);
                c.drawRoundRect(rect, 6, 6, fill);
                rect.set(135, y + 22, 163, y + 36);
                c.drawRoundRect(rect, 4, 4, fill);
                break;
            case Library.VOLUME:        // a drive
                fill.setColor(Ui.GREEN);
                rect.set(130, y + 28, 200, y + 66);
                c.drawRoundRect(rect, 8, 8, fill);
                fill.setColor(Ui.PANEL);
                c.drawCircle(185, y + 47, 5, fill);
                break;
            case Library.SETTINGS:      // three sliders
                fill.setColor(Ui.TEXT_DIM);
                for (int i = 0; i < 3; i++) {
                    rect.set(132, y + 28 + i * 16, 198, y + 32 + i * 16);
                    c.drawRoundRect(rect, 2, 2, fill);
                }
                fill.setColor(Ui.ACCENT);
                c.drawCircle(150, y + 30, 7, fill);
                c.drawCircle(180, y + 46, 7, fill);
                c.drawCircle(160, y + 62, 7, fill);
                break;
            case Library.APP:           // a globe
                stroke.setColor(Color.rgb(186, 156, 255));
                stroke.setStrokeWidth(5);
                c.drawCircle(165, y + 47, 24, stroke);
                rect.set(150, y + 23, 180, y + 71);
                c.drawOval(rect, stroke);
                c.drawLine(141, y + 47, 189, y + 47, stroke);
                break;
            case Library.ACTION:
                fill.setColor(Ui.AMBER);
                c.drawCircle(165, y + 47, 24, fill);
                text.setColor(Ui.BG);
                text.setTextSize(36);
                text.setFakeBoldText(true);
                c.drawText("!", 158, y + 59, text);
                text.setFakeBoldText(false);
                break;
            default:                    // play triangle
                fill.setColor(Ui.ACCENT);
                path.reset();
                path.moveTo(145, y + 24);
                path.lineTo(145, y + 70);
                path.lineTo(188, y + 47);
                path.close();
                c.drawPath(path, fill);
                break;
        }
    }

    private void paintYoutubeIcon(Canvas c, float cx, float cy) {
        fill.setColor(Ui.RED);
        rect.set(cx - 34, cy - 23, cx + 34, cy + 23);
        c.drawRoundRect(rect, 12, 12, fill);
        fill.setColor(Color.WHITE);
        path.reset();
        path.moveTo(cx - 9, cy - 12);
        path.lineTo(cx - 9, cy + 12);
        path.lineTo(cx + 13, cy);
        path.close();
        c.drawPath(path, fill);
    }

    /** Playback overlay parts that sit in the middle of the picture (error / buffering). */
    private void paintPlayerCenter(Canvas c) {
        if (engine.getError() != null) {
            fill.setColor(Color.argb(210, 0, 0, 0));
            rect.set(460, 380, 1460, 640);
            c.drawRoundRect(rect, 24, 24, fill);
            centered(c, "Can't play this video", 470, 52, Ui.TEXT);
            text.setTextSize(34);
            centered(c, Ui.ellipsize(engine.getError(), text, 920), 540, 34, Ui.TEXT_DIM);
            centered(c, "Press BACK to return", 600, 30, Ui.TEXT_DIM);
        } else if (engine.isBuffering()) {
            fill.setColor(Color.argb(170, 0, 0, 0));
            rect.set(760, 470, 1160, 580);
            c.drawRoundRect(rect, 55, 55, fill);
            centered(c, "Buffering…", 542, 44, Ui.TEXT);
        }
    }

    /** Subtitles + playback HUD, in full-panel coordinates (the caller shifts the canvas). */
    private void paintBand(Canvas c, long now) {
        boolean hud = hudVisible(now);
        int w = Overlay.W, h = Overlay.H;

        String cue = engine.getCueText();
        if (cue != null && !cue.isEmpty()) {
            int bottom = hud ? h - 240 : h - 90;
            StaticLayout outline = new StaticLayout(cue, subtitleOutline, 1500, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
            StaticLayout face = new StaticLayout(cue, subtitle, 1500, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
            c.save();
            c.translate((w - 1500) / 2f, bottom - face.getHeight());
            outline.draw(c);
            face.draw(c);
            c.restore();
        }
        if (!hud) return;

        scrim.setShader(new LinearGradient(0, h - 330, 0, h, Color.argb(0, 0, 0, 0), Color.argb(215, 0, 0, 0), Shader.TileMode.CLAMP));
        c.drawRect(0, h - 330, w, h, scrim);

        text.setColor(Ui.TEXT);
        text.setTextSize(46);
        text.setFakeBoldText(true);
        c.drawText(Ui.ellipsize(engine.getTitle(), text, 1250), 100, h - 205, text);
        text.setFakeBoldText(false);

        fill.setColor(Ui.TEXT);
        if (engine.isPlaying()) {
            rect.set(100, h - 150, 114, h - 108);
            c.drawRoundRect(rect, 3, 3, fill);
            rect.set(128, h - 150, 142, h - 108);
            c.drawRoundRect(rect, 3, 3, fill);
        } else {
            path.reset();
            path.moveTo(100, h - 152);
            path.lineTo(100, h - 106);
            path.lineTo(144, h - 129);
            path.close();
            c.drawPath(path, fill);
        }

        float x0 = 180, x1 = w - 330, y = h - 130;
        long dur = engine.getDurationMs();
        fill.setColor(Color.argb(80, 255, 255, 255));
        rect.set(x0, y - 6, x1, y + 6);
        c.drawRoundRect(rect, 6, 6, fill);
        if (dur > 0) {
            float buf = Math.min(1f, engine.getBufferedMs() / (float) dur);
            float pos = Math.min(1f, engine.getPositionMs() / (float) dur);
            fill.setColor(Color.argb(140, 255, 255, 255));
            rect.set(x0, y - 6, x0 + (x1 - x0) * buf, y + 6);
            c.drawRoundRect(rect, 6, 6, fill);
            fill.setColor(Ui.ACCENT);
            rect.set(x0, y - 6, x0 + (x1 - x0) * pos, y + 6);
            c.drawRoundRect(rect, 6, 6, fill);
            c.drawCircle(x0 + (x1 - x0) * pos, y, 15, fill);
        }
        text.setTextSize(36);
        text.setColor(Ui.TEXT);
        String time = Library.fmt(engine.getPositionMs()) + (dur > 0 ? " / " + Library.fmt(dur) : "");
        c.drawText(time, x1 + 40, y + 13, text);

        if (quickOpen) {
            Ui.hints(c, 100, h - 46, Ui.K_UPDOWN, "Choose", Ui.K_LR, "Change", Ui.K_BACK, "Close options");
        } else {
            String back = returnToYoutube ? "Back to YouTube" : "Back to library";
            Ui.hints(c, 100, h - 46, Ui.K_OK, engine.isPlaying() ? "Pause" : "Play", Ui.K_LR, "Seek 10 s",
                    Ui.K_UPDOWN, "Options", Ui.K_BACK, back);
        }
    }

    /** The playback options panel, on the right so the picture stays visible. Painted into both bitmaps. */
    private void paintQuick(Canvas c) {
        List<Integer> rows = quickRows();
        quickSel = Math.max(0, Math.min(rows.size() - 1, quickSel));
        final int x0 = 1120, x1 = 1830, top = 60, rowH = 74;
        int bottom = top + 92 + rows.size() * rowH + 20;
        fill.setColor(Color.argb(236, 20, 24, 31));
        rect.set(x0, top, x1, bottom);
        c.drawRoundRect(rect, 28, 28, fill);
        Ui.outline(c, rect, 28);
        text.setColor(Ui.TEXT);
        text.setTextSize(42);
        text.setFakeBoldText(true);
        c.drawText("Options", x0 + 40, top + 66, text);
        text.setFakeBoldText(false);
        for (int i = 0; i < rows.size(); i++) {
            int id = rows.get(i);
            float y = top + 92 + i * rowH;
            boolean s = i == quickSel;
            if (s) {
                fill.setColor(Ui.ROW_SEL);
                rect.set(x0 + 16, y, x1 - 16, y + rowH - 8);
                c.drawRoundRect(rect, 14, 14, fill);
                fill.setColor(Ui.ACCENT);
                rect.set(x0 + 16, y, x0 + 24, y + rowH - 8);
                c.drawRoundRect(rect, 4, 4, fill);
            }
            text.setTextSize(36);
            text.setColor(s ? Ui.TEXT : Color.rgb(196, 204, 214));
            c.drawText(quickLabel(id), x0 + 44, y + 46, text);
            String v = quickValue(id);
            if (!v.isEmpty()) {
                text.setTextSize(32);
                text.setColor(s ? Ui.ACCENT : Ui.TEXT_DIM);
                boolean arrows = s && quickIsValue(id) && quickAdjustable(id);
                float right = x1 - 44;
                float maxW = 330;
                v = Ui.ellipsize(v, text, maxW);
                float tw = text.measureText(v);
                if (arrows) {
                    c.drawText("›", right - 8, y + 45, text);
                    right -= 34;
                }
                c.drawText(v, right - tw, y + 45, text);
                if (arrows) c.drawText("‹", right - tw - 30, y + 45, text);
            } else if (s || id == Q_SETTINGS) {
                text.setTextSize(40);
                text.setColor(Ui.TEXT_DIM);
                c.drawText("›", x1 - 56, y + 48, text);
            }
        }
    }

    private void paintSettings(Canvas c) {
        fill.setColor(Color.argb(settingsReturn == Mode.PLAYER ? 120 : 170, 0, 0, 0));
        c.drawRect(0, 0, Overlay.W, Overlay.H, fill);
        final int x0 = 380, x1 = 1540;
        rect.set(x0, 40, x1, 1050);
        fill.setColor(Ui.PANEL);
        c.drawRoundRect(rect, 30, 30, fill);
        Ui.outline(c, rect, 30);
        text.setColor(Ui.TEXT);
        text.setTextSize(50);
        text.setFakeBoldText(true);
        c.drawText("Settings", x0 + 60, 118, text);
        text.setFakeBoldText(false);

        for (int r = 0; r < SETTINGS_VISIBLE; r++) {
            int i = settingsScroll + r;
            if (i >= SETTINGS_LINES.length) break;
            int id = SETTINGS_LINES[i];
            float y = SET_TOP + r * SET_LINE_H;
            if (id < 0) {
                text.setTextSize(27);
                text.setFakeBoldText(true);
                text.setColor(Ui.ACCENT);
                c.drawText(SECTION_NAMES[-1 - id].toUpperCase(Locale.getDefault()), x0 + 60, y + 44, text);
                text.setFakeBoldText(false);
                continue;
            }
            boolean s = i == settingsSel;
            if (s) {
                fill.setColor(Ui.ROW_SEL);
                rect.set(x0 + 30, y, x1 - 30, y + SET_LINE_H - 6);
                c.drawRoundRect(rect, 14, 14, fill);
            }
            text.setTextSize(35);
            text.setColor(s ? Ui.TEXT : Color.rgb(190, 200, 212));
            c.drawText(SETTING_NAMES[id], x0 + 60, y + 40, text);
            String v = settingValue(id);
            if (!v.isEmpty()) {
                text.setColor(s ? Ui.ACCENT : Ui.TEXT_DIM);
                float right = x1 - 70;
                float tw = text.measureText(v);
                if (s) {
                    c.drawText("›", right + 14, y + 40, text);
                    c.drawText("‹", right - tw - 30, y + 40, text);
                }
                c.drawText(v, right - tw, y + 40, text);
                if (settingChanged(id)) {       // changed from the default
                    fill.setColor(Ui.AMBER);
                    c.drawCircle(right - tw - (s ? 50 : 22), y + 28, 6, fill);
                }
            }
        }
        if (SETTINGS_LINES.length > SETTINGS_VISIBLE) {      // scrollbar
            float trackH = SETTINGS_VISIBLE * SET_LINE_H;
            float thumbH = trackH * SETTINGS_VISIBLE / SETTINGS_LINES.length;
            float y = SET_TOP + (trackH - thumbH) * settingsScroll / (SETTINGS_LINES.length - SETTINGS_VISIBLE);
            fill.setColor(Color.argb(90, 255, 255, 255));
            rect.set(x1 - 22, y, x1 - 12, y + thumbH);
            c.drawRoundRect(rect, 5, 5, fill);
        }

        // what the focused setting does (CRTXR: help explains the effect, not the label)
        int id = settingId();
        fill.setColor(Color.argb(60, 255, 255, 255));
        c.drawRect(x0 + 40, 885, x1 - 40, 887, fill);
        if (id >= 0) {
            text.setTextSize(30);
            text.setColor(Color.rgb(196, 204, 214));
            StaticLayout help = new StaticLayout(SETTING_HELP[id], text, x1 - x0 - 120,
                    Layout.Alignment.ALIGN_NORMAL, 1.1f, 0f, false);
            c.save();
            c.translate(x0 + 60, 902);
            help.draw(c);
            c.restore();
        }
        String back = settingsReturn == Mode.PLAYER ? "Back to video" : "Close";
        if (isAction(id)) Ui.hints(c, x0 + 60, 1018, Ui.K_OK, id == S_LEGAL ? "Open" : "Recentre now", Ui.K_BACK, back);
        else Ui.hints(c, x0 + 60, 1018, Ui.K_LR, "Change", Ui.K_UPDOWN, "Choose", Ui.K_BACK, back);
    }

    /** A video is being looked up: say which, and that BACK cancels. */
    private void paintLoading(Canvas c, long now) {
        fill.setColor(Color.argb(150, 0, 0, 0));
        c.drawRect(0, 0, Overlay.W, Overlay.H, fill);
        rect.set(460, 380, 1460, 680);
        fill.setColor(Ui.PANEL);
        c.drawRoundRect(rect, 30, 30, fill);
        Ui.outline(c, rect, 30);
        int phase = (int) ((now / 350) % 4);
        String dots = phase == 0 ? "" : phase == 1 ? "." : phase == 2 ? ".." : "...";
        centered(c, "Loading" + dots, 470, 40, Ui.TEXT_DIM);
        text.setTextSize(44);
        text.setFakeBoldText(true);
        String t = Ui.ellipsize(loadingTitle, text, 900);
        centered(c, t, 550, 44, Ui.TEXT);
        text.setFakeBoldText(false);
        // indeterminate bar
        float bx0 = 560, bx1 = 1360, by = 600;
        fill.setColor(Color.argb(70, 255, 255, 255));
        rect.set(bx0, by, bx1, by + 8);
        c.drawRoundRect(rect, 4, 4, fill);
        float f = (now % 1400) / 1400f;
        float seg = 220, sx = bx0 + (bx1 - bx0 + seg) * f - seg;
        fill.setColor(Ui.ACCENT);
        rect.set(Math.max(bx0, sx), by, Math.min(bx1, sx + seg), by + 8);
        if (rect.right > rect.left) c.drawRoundRect(rect, 4, 4, fill);
        Ui.hints(c, 830, 655, Ui.K_BACK, "Cancel");
    }

    private void paintToast(Canvas c) {
        text.setTextSize(40);
        String s = Ui.ellipsize(toast, text, 1500);
        float tw = text.measureText(s);
        float x = (Overlay.W - tw) / 2f;
        fill.setColor(Color.argb(225, 20, 24, 30));
        rect.set(x - 40, 60, x + tw + 40, 140);
        c.drawRoundRect(rect, 40, 40, fill);
        Ui.outline(c, rect, 40);
        text.setColor(Ui.TEXT);
        c.drawText(s, x, 114, text);
    }

    private void centered(Canvas c, String s, float y, float size, int color) {
        text.setTextSize(size);
        text.setColor(color);
        c.drawText(s, (Overlay.W - text.measureText(s)) / 2f, y, text);
    }
}
