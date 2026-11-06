package com.portable3dof;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.KeyEvent;

/**
 * Browser input and UI state.
 *
 * Cursor: lives in browser pixels (1280 x 720). The D-pad and the Pocket TV gyro both move it; the renderer
 * draws it on the screen and mouse events are injected into the WebView at the same position.
 *
 * Toolbar: opens when you push Up at the top of a page (or dwell the pointer at the top edge).
 * Keyboard ({@link OnScreenKeyboard}): opens by itself when a text field gets focus (or from the toolbar).
 * Both work with either the D-pad (move the highlight) or the pointer (hover selects, OK presses).
 */
public class BrowserController implements PointerListener, BrowserScreen.EditListener, OnScreenKeyboard.Listener {
    private static final int VW = SceneRenderer.BROWSER_W, VH = SceneRenderer.BROWSER_H;
    private static final float K = (float) Overlay.W / VW;          // browser px -> overlay px (1.5)

    private static final int ACCENT = Color.rgb(79, 195, 247);
    private static final int PANEL = Color.rgb(34, 40, 50);
    private static final int PANEL_SEL = Color.rgb(48, 74, 98);
    private static final int BAR = Color.argb(238, 16, 20, 26);
    private static final int TEXT = Color.rgb(236, 240, 244);
    private static final int TEXT_DIM = Color.rgb(140, 152, 166);

    // ---- toolbar layout (overlay pixels) ----
    private static final int TB_H = 100;
    private static final String[] TB_LABELS = {"Back", "Fwd", "Reload", "Library", "", "A-", "A+", "", "Keys"};
    private static final int[] TB_W = {130, 130, 140, 150, 680, 80, 80, 170, 130};
    private static final int TB_BACK = 0, TB_FWD = 1, TB_RELOAD = 2, TB_LIB = 3, TB_URL = 4,
            TB_ZOOM_OUT = 5, TB_ZOOM_IN = 6, TB_SITE = 7, TB_KEYS = 8;
    private final int[] tbX = new int[TB_W.length];

    private final OnScreenKeyboard kb = new OnScreenKeyboard();

    private final Context ctx;
    private final BrowserScreen screen;
    private final SceneRenderer renderer;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable exitHandler;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float cx = VW / 2f, cy = VH / 2f;
    private boolean active = false;

    private boolean toolbarOpen = false, tbByDpad = false;
    private int tbFocus = TB_URL;
    private boolean kbManual = false, urlMode = false;
    private String urlBuf = "";
    private boolean modalDpad = false;                  // last modal input came from the D-pad (not the pointer)
    private long edgeSince = 0, lastScrollMs = 0;
    private boolean kbInsetApplied = false;

    // pointer deltas arrive on a sensor thread; apply them on the main thread at ~60 Hz
    private final Object pointerLock = new Object();
    private float pendingDx, pendingDy;
    private boolean flushScheduled = false;
    private final Runnable flush = new Runnable() {
        @Override public void run() {
            float dx, dy;
            synchronized (pointerLock) { dx = pendingDx; dy = pendingDy; pendingDx = pendingDy = 0; flushScheduled = false; }
            if (active && (dx != 0 || dy != 0)) { modalDpad = false; move(dx, dy); }
        }
    };

    public BrowserController(Context ctx, BrowserScreen screen, SceneRenderer renderer) {
        this.ctx = ctx;
        this.screen = screen;
        this.renderer = renderer;
        text.setTypeface(Typeface.SANS_SERIF);
        stroke.setStyle(Paint.Style.STROKE);
        screen.setEditListener(this);
        int x = 20;
        for (int i = 0; i < TB_W.length; i++) { tbX[i] = x; x += TB_W[i] + 10; }
    }

    public void setExitHandler(Runnable r) { exitHandler = r; }

    public BrowserScreen screen() { return screen; }

    public boolean isActive() { return active; }

    // ---- lifecycle --------------------------------------------------------------------------

    public void open(String url) {
        active = true;
        toolbarOpen = urlMode = false;
        kb.close();
        renderer.setBrowserActive(true);
        screen.open(url);
        pushCursor();
    }

    public void close() {
        active = false;
        toolbarOpen = urlMode = false;
        kb.close();
        kbInsetApplied = false;
        renderer.setBrowserActive(false);
        renderer.setCursor(0, 0, false);
        screen.close();
    }

    public void suspend() { if (active) screen.suspend(); }

    public void resume() { if (active) { screen.resume(); screen.open(null); } }

    private void pushCursor() { renderer.setCursor(cx * K, cy * K, active); }

    private void saveSettings() { Settings.save(ctx, renderer); }

    /** Keep the page viewport in step with the keyboard: shrink it above the keyboard while it is open. */
    private void syncKbInset() {
        if (kb.isOpen() == kbInsetApplied) return;
        kbInsetApplied = kb.isOpen();
        screen.setBottomInset(kb.isOpen() ? (int) ((Overlay.H - OnScreenKeyboard.PANEL_TOP) / K) : 0);
    }

    // ---- keyboard callbacks -----------------------------------------------------------------

    @Override
    public void onText(String s) {
        if (urlMode) urlBuf += s; else screen.type(s);
    }

    @Override
    public void onBackspace() {
        if (urlMode) { if (!urlBuf.isEmpty()) urlBuf = urlBuf.substring(0, urlBuf.length() - 1); }
        else screen.key(KeyEvent.KEYCODE_DEL);
    }

    @Override
    public void onEnter() {
        if (urlMode) {
            String u = urlBuf;
            closeKeyboard();
            closeToolbar();
            if (!u.trim().isEmpty()) screen.load(u);
        } else {
            screen.key(KeyEvent.KEYCODE_ENTER);
        }
    }

    @Override
    public void onClose() { closeKeyboard(); }

    // ---- page text focus --------------------------------------------------------------------

    @Override
    public void onEditableFocus(boolean editable) {
        if (!active) return;
        if (editable && !kb.isOpen()) {
            kb.open();
            kbManual = false;
            urlMode = false;
        } else if (!editable && kb.isOpen() && !kbManual && !urlMode) {
            kb.close();
        }
        syncKbInset();
    }

    // ---- cursor -----------------------------------------------------------------------------

    /** Move the cursor by (dx, dy) browser pixels; pushing against the top/bottom edge scrolls (or opens the toolbar). */
    public void move(float dx, float dy) {
        float nx = Math.max(0, Math.min(VW - 1, cx + dx));
        float ny = Math.max(0, Math.min(VH - 1, cy + dy));
        long now = SystemClock.uptimeMillis();

        if (!toolbarOpen && !kb.isOpen()) {
            if (cy <= 1 && ny <= 1 && dy < 0) {
                if (screen.scrollY() > 0) throttledScroll(1f, now);
                else if (!modalDpad) {
                    if (edgeSince == 0) edgeSince = now;
                    else if (now - edgeSince > 600) openToolbar(false);
                }
            } else if (cy >= VH - 2 && ny >= VH - 2 && dy > 0) {
                throttledScroll(-1f, now);
            } else {
                edgeSince = 0;
            }
        }
        cx = nx;
        cy = ny;
        pushCursor();

        float ox = cx * K, oy = cy * K;
        if (toolbarOpen && !tbByDpad && oy > TB_H + 90) closeToolbar();
        updateHover(ox, oy);
        if (!(toolbarOpen && oy < TB_H) && !kb.contains(oy)) screen.hover(cx, cy);
    }

    private void throttledScroll(float notches, long now) {
        if (now - lastScrollMs > 70) { lastScrollMs = now; screen.scroll(cx, cy, notches); }
    }

    /** Pointer hover over the toolbar / keyboard moves the highlight to that button / key. */
    private void updateHover(float ox, float oy) {
        if (toolbarOpen && oy < TB_H) {
            for (int i = 0; i < TB_W.length; i++) {
                if (ox >= tbX[i] && ox <= tbX[i] + TB_W[i]) { tbFocus = i; break; }
            }
        }
        kb.hover(ox, oy);
    }

    @Override
    public void onPointerDelta(float dx, float dy) {
        synchronized (pointerLock) {
            pendingDx += dx;
            pendingDy += dy;
            if (!flushScheduled) { flushScheduled = true; main.postDelayed(flush, 12); }
        }
    }

    // ---- toolbar / keyboard state -----------------------------------------------------------

    private void openToolbar(boolean byDpad) {
        toolbarOpen = true;
        tbByDpad = byDpad;
        modalDpad = byDpad;
        edgeSince = 0;
        if (byDpad) tbFocus = TB_URL;
    }

    private void closeToolbar() { toolbarOpen = false; modalDpad = false; }

    private void closeKeyboard() { kb.close(); urlMode = false; kbManual = false; }

    private boolean cursorInModal() {
        float oy = cy * K;
        return kb.contains(oy) || (toolbarOpen && oy < TB_H);
    }

    // ---- keys -------------------------------------------------------------------------------

    /** Returns true if the key was consumed; BACK returns false when there is nothing left to close / go back to. */
    public boolean onKey(int k, int repeat) {
        boolean r = kb.isOpen() ? keyboardKey(k) : toolbarOpen ? toolbarKey(k) : cursorKey(k, repeat);
        syncKbInset();
        return r;
    }

    private boolean cursorKey(int k, int repeat) {
        float step = 12 + Math.min(repeat, 25) * 4;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (cy <= 1) {
                    if (screen.scrollY() > 0) screen.scroll(cx, cy, 2f);
                    else openToolbar(true);
                } else {
                    modalDpad = false;
                    move(0, -step);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                modalDpad = false;
                if (cy >= VH - 2) screen.scroll(cx, cy, -2f); else move(0, step);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: modalDpad = false; move(-step, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: modalDpad = false; move(step, 0); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                screen.click(cx, cy);
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN: screen.scroll(cx, cy, -4f); return true;
            case KeyEvent.KEYCODE_PAGE_UP: screen.scroll(cx, cy, 4f); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                screen.key(KeyEvent.KEYCODE_SPACE);          // play / pause in most web players
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
                openToolbar(true);
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                return screen.back();
            default:
                return false;
        }
    }

    private boolean toolbarKey(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: modalDpad = true; tbFocus = Math.max(0, tbFocus - 1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: modalDpad = true; tbFocus = Math.min(TB_W.length - 1, tbFocus + 1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: closeToolbar(); return true;
            case KeyEvent.KEYCODE_DPAD_UP: return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (modalDpad || cursorInModal()) activateToolbar(tbFocus);
                else screen.click(cx, cy);
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_MENU:
                closeToolbar();
                return true;
            default:
                return false;
        }
    }

    private void activateToolbar(int i) {
        switch (i) {
            case TB_BACK: screen.back(); break;
            case TB_FWD: screen.forward(); break;
            case TB_RELOAD: screen.reload(); break;
            case TB_LIB: closeToolbar(); if (exitHandler != null) exitHandler.run(); break;
            case TB_URL:
                urlMode = true;
                urlBuf = "";
                kb.open();
                kbManual = true;
                break;
            case TB_ZOOM_OUT: screen.setTextZoom(screen.getTextZoom() - 10); saveSettings(); break;
            case TB_ZOOM_IN: screen.setTextZoom(screen.getTextZoom() + 10); saveSettings(); break;
            case TB_SITE: screen.toggleDesktop(); saveSettings(); break;
            case TB_KEYS:
                if (kb.isOpen()) kb.close(); else kb.open();
                kbManual = kb.isOpen();
                urlMode = false;
                break;
            default: break;
        }
    }

    private boolean keyboardKey(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: modalDpad = true; kb.left(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: modalDpad = true; kb.right(); return true;
            case KeyEvent.KEYCODE_DPAD_UP: modalDpad = true; kb.up(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: modalDpad = true; kb.down(); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (modalDpad || cursorInModal()) kb.pressSelected(this);
                else screen.click(cx, cy);                // pointer is over the page, not the keyboard
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                closeKeyboard();
                return true;
            default:
                return false;
        }
    }

    // ---- painting ---------------------------------------------------------------------------

    /** Progress line + toolbar (main overlay canvas, full-panel coordinates). */
    public void paintTop(Canvas c) {
        int prog = screen.getProgress();
        if (prog < 100) {
            fill.setColor(ACCENT);
            rect.set(0, 0, Overlay.W * prog / 100f, 6);
            c.drawRect(rect, fill);
        }
        if (!toolbarOpen) return;

        fill.setColor(BAR);
        rect.set(0, 0, Overlay.W, TB_H);
        c.drawRect(rect, fill);
        for (int i = 0; i < TB_W.length; i++) {
            boolean enabled = !(i == TB_BACK && !screen.canGoBack()) && !(i == TB_FWD && !screen.canGoForward());
            boolean focus = i == tbFocus;
            fill.setColor(focus ? PANEL_SEL : PANEL);
            rect.set(tbX[i], 14, tbX[i] + TB_W[i], TB_H - 14);
            c.drawRoundRect(rect, 14, 14, fill);
            if (focus) {
                stroke.setColor(ACCENT);
                stroke.setStrokeWidth(3);
                c.drawRoundRect(rect, 14, 14, stroke);
            }
            String label = TB_LABELS[i];
            if (i == TB_SITE) label = screen.isDesktop() ? "Desktop" : "Mobile";
            if (i == TB_KEYS && kb.isOpen()) label = "Keys ✓";
            if (i == TB_URL) {
                label = urlMode ? urlBuf + "_" : shortUrl(screen.getUrl());
                text.setColor(urlMode ? TEXT : TEXT_DIM);
                text.setTextSize(30);
                c.drawText(ellipsizeStart(label, 640), tbX[i] + 18, 64, text);
            } else {
                text.setColor(enabled ? TEXT : Color.argb(90, 236, 240, 244));
                text.setTextSize(30);
                float tw = text.measureText(label);
                c.drawText(label, tbX[i] + (TB_W[i] - tw) / 2f, 64, text);
            }
        }
        text.setColor(TEXT_DIM);
        text.setTextSize(24);
        c.drawText("Zoom " + screen.getTextZoom() + "%", 1790, 56, text);
    }

    /** Keyboard (bottom band; coordinates are full-panel, the caller shifts the canvas). */
    public void paintBottom(Canvas c) { kb.paint(c); }

    public int stateHash() {      // top overlay
        int h = (toolbarOpen ? 1 : 0) + (urlMode ? 2 : 0) + screen.getProgress() / 25 * 4;
        if (toolbarOpen) {
            h = h * 31 + tbFocus + (screen.canGoBack() ? 8 : 0) + (screen.canGoForward() ? 16 : 0)
                    + (screen.isDesktop() ? 32 : 0) + screen.getTextZoom() * 3 + (kb.isOpen() ? 64 : 0);
            h = h * 31 + urlBuf.hashCode();
            if (!urlMode) h = h * 31 + screen.getUrl().hashCode();
        }
        return h;
    }

    public int bottomHash() { return kb.hash(); }

    private static String shortUrl(String u) {
        if (u == null) return "";
        return u.replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
    }

    private String ellipsizeStart(String s, float maxW) {
        if (text.measureText(s) <= maxW) return s;
        int n = 0;
        while (n < s.length() - 1 && text.measureText(s, n, s.length()) + text.measureText("…") > maxW) n++;
        return "…" + s.substring(n);
    }
}
