package com.portable3dof;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * A WebView rendered off-screen: it lives in a Presentation on an app-owned VirtualDisplay whose output
 * surface is a GL texture (see SceneRenderer), so the page shows up on the floating screen. Mouse and
 * keyboard input is injected as synthetic events. All methods run on the main thread (the GL thread
 * only hands us surfaces, which we post across).
 */
public class BrowserScreen implements SceneRenderer.BrowserHost {

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());

    private VirtualDisplay vd;
    private Presentation presentation;
    private FrameLayout root;
    private WebView web;
    private Surface surface;                 // GL-side surface, main-thread copy
    private boolean wanted = false;          // the browser should be showing
    private String lastUrl = "https://www.google.com";
    private String pendingUrl = null;

    private View customView;                 // HTML5 fullscreen video
    private WebChromeClient.CustomViewCallback customCallback;

    private volatile String title = "";
    private volatile String currentUrl = "";
    private volatile int progress = 100;
    private volatile boolean desktop = true;
    private volatile int textZoom = 110;
    private volatile boolean fullscreenVideo = false;

    /** Told when a text field in the page gains or loses focus (so the on-screen keyboard can follow). */
    public interface EditListener { void onEditableFocus(boolean editable); }

    private EditListener editListener;

    public void setEditListener(EditListener l) { editListener = l; }

    /** Exposed to page scripts as window.P3 (only this one harmless method). */
    private final class JsBridge {
        @JavascriptInterface
        public void editable(final boolean b) {
            main.post(new Runnable() {
                @Override public void run() { if (editListener != null) editListener.onEditableFocus(b); }
            });
        }
    }

    private static final String EDIT_WATCH_JS =
            "(function(){if(window.__p3dof)return;window.__p3dof=1;"
            + "function ed(el){if(!el)return false;var t=el.tagName;if(t==='TEXTAREA')return true;"
            + "if(t==='INPUT'){var ty=(el.type||'text').toLowerCase();"
            + "return ['button','submit','checkbox','radio','range','color','file','image','reset'].indexOf(ty)<0;}"
            + "return !!el.isContentEditable;}"
            + "document.addEventListener('focusin',function(e){P3.editable(ed(e.target));},true);"
            + "document.addEventListener('focusout',function(){setTimeout(function(){P3.editable(ed(document.activeElement));},60);},true);"
            + "})();";

    public BrowserScreen(Activity activity) {
        this.activity = activity;
    }

    // ---- SceneRenderer.BrowserHost (GL thread) ------------------------------------------------

    @Override
    public void onBrowserSurface(final Surface s, int w, int h) {
        main.post(new Runnable() {
            @Override public void run() {
                surface = s;
                if (wanted) build();
            }
        });
    }

    @Override
    public void onBrowserSurfaceDestroyed() {
        main.post(new Runnable() {
            @Override public void run() {
                teardown();
                surface = null;
            }
        });
    }

    // ---- lifecycle (main thread) -------------------------------------------------------------

    public void open(String url) {
        wanted = true;
        if (url != null) { pendingUrl = url; lastUrl = url; }
        if (surface != null) build();
    }

    /** Leave the browser; the page is kept in memory only while it is showing. */
    public void close() {
        wanted = false;
        teardown();
    }

    /** Activity going to the background: drop the display but remember the page. */
    public void suspend() {
        if (web != null && web.getUrl() != null) lastUrl = web.getUrl();
        teardown();
    }

    /** Activity back: the GL surface callback re-creates the page; this only marks it wanted again. */
    public void resume() {
        pendingUrl = lastUrl;
    }

    private void build() {
        if (vd != null) {
            if (pendingUrl != null && web != null) { web.loadUrl(pendingUrl); pendingUrl = null; }
            return;
        }
        if (surface == null || !surface.isValid()) return;
        desktop = Settings.browserDesktop;
        textZoom = Settings.browserZoom;
        DisplayManager dm = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
        vd = dm.createVirtualDisplay("p3dof-browser", SceneRenderer.BROWSER_W, SceneRenderer.BROWSER_H, 160,
                surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                        | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
        if (vd == null) return;

        presentation = new Presentation(activity, vd.getDisplay());
        root = new FrameLayout(presentation.getContext());
        root.setBackgroundColor(0xFF000000);
        web = new WebView(presentation.getContext());
        configure(web);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        presentation.setContentView(root);
        presentation.show();
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.requestFocus();
        web.onResume();
        web.onWindowFocusChanged(true);

        String u = pendingUrl != null ? pendingUrl : lastUrl;
        pendingUrl = null;
        web.loadUrl(u);
    }

    private void teardown() {
        if (customView != null) { exitFullscreenVideo(); }
        try {
            if (presentation != null) presentation.dismiss();
        } catch (RuntimeException ignored) { }
        presentation = null;
        if (web != null) {
            try { web.destroy(); } catch (RuntimeException ignored) { }
            web = null;
        }
        root = null;
        if (vd != null) { vd.release(); vd = null; }
    }

    private void configure(WebView w) {
        WebSettings ws = w.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setSupportMultipleWindows(false);      // target=_blank opens in the same view
        ws.setTextZoom(textZoom);
        applyUserAgent(ws);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, true);
        w.addJavascriptInterface(new JsBridge(), "P3");

        w.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, Bitmap icon) { currentUrl = url; progress = 0; }
            @Override public void onPageFinished(WebView v, String url) {
                currentUrl = url;
                progress = 100;
                v.evaluateJavascript(EDIT_WATCH_JS, null);
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) { progress = p; }
            @Override public void onReceivedTitle(WebView v, String t) { title = t == null ? "" : t; }
            @Override public void onShowCustomView(View view, CustomViewCallback cb) { enterFullscreenVideo(view, cb); }
            @Override public void onHideCustomView() { exitFullscreenVideo(); }
        });
    }

    private void applyUserAgent(WebSettings ws) {
        if (desktop) {
            String def = ws.getUserAgentString();
            String ver = "120.0.0.0";
            int i = def == null ? -1 : def.indexOf("Chrome/");
            if (i >= 0) {
                int e = def.indexOf(' ', i);
                ver = def.substring(i + 7, e < 0 ? def.length() : e);
            }
            ws.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                    + ver + " Safari/537.36");
        } else {
            ws.setUserAgentString(null);   // WebView default (mobile)
        }
    }

    private void enterFullscreenVideo(View v, WebChromeClient.CustomViewCallback cb) {
        if (root == null) { cb.onCustomViewHidden(); return; }
        customView = v;
        customCallback = cb;
        root.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (web != null) web.setVisibility(View.INVISIBLE);
        fullscreenVideo = true;
    }

    private void exitFullscreenVideo() {
        if (customView != null && root != null) root.removeView(customView);
        customView = null;
        if (customCallback != null) { customCallback.onCustomViewHidden(); customCallback = null; }
        if (web != null) web.setVisibility(View.VISIBLE);
        fullscreenVideo = false;
    }

    // ---- navigation --------------------------------------------------------------------------

    public boolean isOpen() { return web != null; }
    public String getTitle() { return title; }
    public String getUrl() { return web != null && web.getUrl() != null ? web.getUrl() : currentUrl; }
    public int getProgress() { return progress; }
    public boolean isDesktop() { return desktop; }
    public int getTextZoom() { return textZoom; }
    public boolean isFullscreenVideo() { return fullscreenVideo; }
    /** Current vertical scroll of the page (0 = at the top). */
    public int scrollY() { return web != null ? web.getScrollY() : 0; }
    public boolean canGoBack() { return web != null && web.canGoBack(); }
    public boolean canGoForward() { return web != null && web.canGoForward(); }

    /** Back: leaves fullscreen video, then walks history. Returns false when there is nothing left to go back to. */
    public boolean back() {
        if (customView != null) { exitFullscreenVideo(); return true; }
        if (web != null && web.canGoBack()) { web.goBack(); return true; }
        return false;
    }

    public void forward() { if (web != null && web.canGoForward()) web.goForward(); }
    public void reload() { if (web != null) web.reload(); }

    public void load(String input) {
        if (web == null) { pendingUrl = normalize(input); lastUrl = pendingUrl; return; }
        web.loadUrl(normalize(input));
    }

    /** Anything with spaces or without a dot is a search; everything else is a URL. */
    public static String normalize(String s) {
        s = s.trim();
        if (s.isEmpty()) return "https://www.google.com";
        if (s.startsWith("http://") || s.startsWith("https://")) return s;
        if (!s.contains(" ") && s.contains(".")) return "https://" + s;
        return "https://www.google.com/search?q=" + android.net.Uri.encode(s);
    }

    public void toggleDesktop() {
        desktop = !desktop;
        Settings.browserDesktop = desktop;
        if (web != null) { applyUserAgent(web.getSettings()); web.reload(); }
    }

    public void setTextZoom(int percent) {
        textZoom = Math.max(60, Math.min(250, percent));
        Settings.browserZoom = textZoom;
        if (web != null) web.getSettings().setTextZoom(textZoom);
    }

    // ---- input injection ---------------------------------------------------------------------

    private static MotionEvent mouse(int action, float x, float y, int buttons, long downTime, float vScroll) {
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        pc.x = x;
        pc.y = y;
        pc.pressure = buttons != 0 ? 1f : 0f;
        pc.size = 1f;
        if (vScroll != 0f) pc.setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);
        return MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                new MotionEvent.PointerProperties[]{pp}, new MotionEvent.PointerCoords[]{pc},
                0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
    }

    public void hover(float x, float y) {
        if (web == null) return;
        MotionEvent e = mouse(MotionEvent.ACTION_HOVER_MOVE, x, y, 0, SystemClock.uptimeMillis(), 0f);
        web.dispatchGenericMotionEvent(e);
        e.recycle();
    }

    /** MotionEvent.setActionButton is hidden API; Chromium wants it on BUTTON_PRESS / BUTTON_RELEASE events. */
    private static void setActionButton(MotionEvent e, int button) {
        try {
            MotionEvent.class.getMethod("setActionButton", int.class).invoke(e, button);
        } catch (Throwable ignored) {
            // not available: the buttonState alone still carries the information
        }
    }

    /** A full left click, as Android's input system sends it: DOWN, BUTTON_PRESS, ...gap..., BUTTON_RELEASE, UP. */
    public void click(final float x, final float y) {
        if (web == null) return;
        hover(x, y);
        final long down = SystemClock.uptimeMillis();
        MotionEvent d = mouse(MotionEvent.ACTION_DOWN, x, y, MotionEvent.BUTTON_PRIMARY, down, 0f);
        web.dispatchTouchEvent(d);
        d.recycle();
        MotionEvent bp = mouse(MotionEvent.ACTION_BUTTON_PRESS, x, y, MotionEvent.BUTTON_PRIMARY, down, 0f);
        setActionButton(bp, MotionEvent.BUTTON_PRIMARY);
        web.dispatchGenericMotionEvent(bp);
        bp.recycle();
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (web == null) return;
                MotionEvent br = mouse(MotionEvent.ACTION_BUTTON_RELEASE, x, y, 0, down, 0f);
                setActionButton(br, MotionEvent.BUTTON_PRIMARY);
                web.dispatchGenericMotionEvent(br);
                br.recycle();
                MotionEvent u = mouse(MotionEvent.ACTION_UP, x, y, 0, down, 0f);
                web.dispatchTouchEvent(u);
                u.recycle();
                // NOTE: no web.requestFocus() here: Chromium answers it by focusing the page's first focusable
                // element, which would steal focus from the field this click just selected.
                web.onWindowFocusChanged(true);
                checkEditableUnderPointer();
            }
        }, 40);
    }

    /** Shrinks the page viewport from the bottom (for the on-screen keyboard): the page scrolls the focused field into view. */
    public void setBottomInset(int px) {
        if (web == null) return;
        ViewGroup.LayoutParams lp = web.getLayoutParams();
        int h = px <= 0 ? ViewGroup.LayoutParams.MATCH_PARENT : SceneRenderer.BROWSER_H - px;
        if (lp != null && lp.height != h) {
            lp.height = h;
            web.setLayoutParams(lp);
            if (px > 0) {   // once the page has re-laid out, centre the focused field in the smaller viewport
                main.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (web != null) web.evaluateJavascript(
                                "var a=document.activeElement;if(a&&a.scrollIntoView)a.scrollIntoView({block:'center'});", null);
                    }
                }, 250);
            }
        }
    }

    /** After a click: is the element under the pointer a text field? (backs up the page-side focus watcher) */
    private void checkEditableUnderPointer() {
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (web == null) return;
                WebView.HitTestResult r = web.getHitTestResult();
                boolean edit = r != null && r.getType() == WebView.HitTestResult.EDIT_TEXT_TYPE;
                if (edit && editListener != null) editListener.onEditableFocus(true);
            }
        }, 150);
    }

    /** Mouse-wheel notches: positive scrolls up (towards the top of the page). */
    public void scroll(float x, float y, float notches) {
        if (web == null) return;
        MotionEvent e = mouse(MotionEvent.ACTION_SCROLL, x, y, 0, SystemClock.uptimeMillis(), notches);
        web.dispatchGenericMotionEvent(e);
        e.recycle();
    }

    /** Types text into whatever page element has focus, as real key events. */
    public void type(String text) {
        if (web == null || text == null) return;
        KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
        if (events == null) return;
        for (KeyEvent ev : events) web.dispatchKeyEvent(ev);
    }

    public void key(int keyCode) {
        if (web == null) return;
        long t = SystemClock.uptimeMillis();
        web.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0));
        web.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0));
    }
}
