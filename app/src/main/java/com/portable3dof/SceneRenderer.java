package com.portable3dof;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Head-tracked cinema scene. World is Z-up with +Y forward (after recentre):
 *   - a dim room (floor grid + horizon ring) for spatial reference
 *   - a floating 16:9 screen at SCREEN_DIST, optionally curved around the viewer, carrying
 *       1. a black backing panel,
 *       2. the video (ExoPlayer -> SurfaceTexture -> external texture, letterboxed),
 *       3. a translucent UI overlay (Canvas-painted bitmap: library, HUD, subtitles, menus)
 */
public class SceneRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private static final String VS =
            "uniform mat4 uMvp; uniform vec3 uTint; uniform float uPt; attribute vec3 aPos; attribute vec4 aCol;"
            + " varying vec4 vCol;"
            + "void main(){ gl_Position = uMvp * vec4(aPos,1.0); gl_PointSize = uPt; vCol = vec4(aCol.rgb * uTint, aCol.a); }";
    private static final String FS = Backdrops.FS_HEAD
            + "varying vec4 vCol; void main(){ gl_FragColor = vCol + dith(); }";

    private static final String VS_TEX =
            "uniform mat4 uMvp; uniform mat4 uTexM; attribute vec3 aPos; attribute vec2 aUv; varying vec2 vUv;"
            + "void main(){ gl_Position = uMvp * vec4(aPos,1.0); vUv = (uTexM * vec4(aUv,0.0,1.0)).xy; }";
    private static final String FS_VIDEO =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float; uniform samplerExternalOES uTex; varying vec2 vUv;"
            + "void main(){ gl_FragColor = texture2D(uTex, vUv); }";
    private static final String FS_OVERLAY =
            "precision mediump float; uniform sampler2D uTex; varying vec2 vUv;"
            + "void main(){ gl_FragColor = texture2D(uTex, vUv); }";   // premultiplied alpha

    private static final float SCREEN_DIST = 5f;
    private static final int SEGMENTS = 48;

    // ---- orientation ------------------------------------------------------------------------
    private final Object lock = new Object();
    private final float[] rot = new float[16];      // row-major device->world (== GL column-major R^T)
    private float yawOffsetDeg;
    private boolean haveOrientation;

    private final float[] proj = new float[16];
    private final float[] view = new float[16];
    private final float[] rz = new float[16];
    private final float[] mvp = new float[16];
    private final float[] ident = new float[16];

    // ---- tunables (UI thread writes, GL thread reads) -----------------------------------------
    private volatile float fovY = 23.5f;            // vertical field of view (Pro 2: 46 deg diagonal)
    private volatile float screenWidthDeg = 32f;
    private volatile float envBrightness = 0.35f;
    private volatile float curve = 0f;              // 0 flat .. 1 wraps on a circle around the viewer
    /** True while a menu / list / page is up: the screen eases flat so text is not bent (curve is for video). */
    private volatile boolean uiFlat = false;
    private float curveEff = -1f;                   // curve actually drawn (GL thread), eases toward the target
    private long lastFrameNs = 0;
    private int vpW = 1920, vpH = 1080;
    /** The screen's centre is outside the view: an arrow pinned to the view points back to it. */
    private volatile boolean screenLost = false;
    private int hintTex;
    private static final int HINT_W = 720, HINT_H = 104;
    private final float[] eye = new float[4];
    private final float[] centreWorld = {0f, SCREEN_DIST, 0f, 1f};
    private final float[] probeWorld = {0f, 0f, 0f, 1f};
    private volatile boolean tracked = false;
    private static final int PROBE_COLS = 13, PROBE_ROWS = 7;
    private final FloatBuffer arrowBuf = buf(6 * 7);
    private final FloatBuffer hintBuf = buf(4 * 5);
    private volatile boolean showMarkers = false;
    private volatile boolean showCrosshair = false;
    private float aspect = 16f / 9f;

    // ---- GL objects -------------------------------------------------------------------------
    private int prog, aPos, aCol, uMvp, uTint, uPt;
    private int vProg, vPos, vUv, vMvp, vTexM, vSampler;       // external (video) texture program
    private int oProg, oPos, oUv, oMvp, oTexM, oSampler;       // overlay (2D texture) program
    private int oesTex, ovTex;
    private SurfaceTexture surfaceTexture;
    private volatile boolean frameAvailable = false;
    private boolean haveFrame = false;
    private boolean overlayAllocated = false;
    private final float[] texM = new float[16];

    private FloatBuffer triBuf, lineBuf, crossBuf;
    private int triCount, lineCount;
    private final FloatBuffer backBuf = buf((SEGMENTS + 1) * 2 * 7);
    private final FloatBuffer videoBuf = buf((SEGMENTS + 1) * 2 * 5);
    private final FloatBuffer overBuf = buf((SEGMENTS + 1) * 2 * 5);

    private MediaEngine media;
    private Overlay overlay;

    // ---- browser surface (WebView -> VirtualDisplay -> SurfaceTexture -> external texture) ----
    public static final int BROWSER_W = 1280, BROWSER_H = 720;

    /** Receives the Surface the browser's virtual display should render into (called on the GL thread). */
    public interface BrowserHost {
        void onBrowserSurface(Surface surface, int width, int height);
        void onBrowserSurfaceDestroyed();
    }

    private BrowserHost browserHost;
    private SurfaceTexture browserTexture;
    private Surface browserSurface;
    private int browserOes;
    private volatile boolean browserFrameAvailable = false;
    private boolean browserHaveFrame = false;
    private volatile boolean browserActive = false;
    private final float[] texMBrowser = new float[16];
    private volatile float cursorX, cursorY;          // overlay pixel coordinates (1920 x 1080)
    private volatile boolean cursorVisible = false;
    private final FloatBuffer cursorBuf = buf(6 * 7 * 2);

    public void setBrowserHost(BrowserHost h) { browserHost = h; }
    public void setBrowserActive(boolean a) { browserActive = a; if (!a) browserHaveFrame = false; }
    public void setCursor(float xPx, float yPx, boolean visible) { cursorX = xPx; cursorY = yPx; cursorVisible = visible; }

    // ---- API --------------------------------------------------------------------------------
    public void setMedia(MediaEngine m) { media = m; }
    public void setOverlay(Overlay o) { overlay = o; }

    public void setFov(float d) { fovY = Math.max(8f, Math.min(90f, d)); }
    public float getFov() { return fovY; }
    public void setScreenWidthDeg(float d) { screenWidthDeg = Math.max(10f, Math.min(90f, d)); }
    public float getScreenWidthDeg() { return screenWidthDeg; }
    public void setEnvBrightness(float b) { envBrightness = Math.max(0f, Math.min(1f, b)); }
    public float getEnvBrightness() { return envBrightness; }
    public void setCurve(float c) { curve = Math.max(0f, Math.min(1f, c)); }
    public float getCurve() { return curve; }
    public void setUiFlat(boolean flat) { uiFlat = flat; }
    public boolean isScreenLost() { return screenLost; }
    public void setShowMarkers(boolean b) { showMarkers = b; }
    public void setShowCrosshair(boolean b) { showCrosshair = b; }

    /** No head pose (glasses not streaming): look straight at the screen. */
    public void clearOrientation() {
        synchronized (lock) { haveOrientation = false; }
    }

    public void setOrientation(float[] rowMajorRot, float yawOffsetDeg) {
        synchronized (lock) {
            System.arraycopy(rowMajorRot, 0, rot, 0, 16);
            this.yawOffsetDeg = yawOffsetDeg;
            haveOrientation = true;
        }
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        if (st == browserTexture) browserFrameAvailable = true; else { frameAvailable = true; perfArrived++; }
    }

    // ---- GL lifecycle -----------------------------------------------------------------------

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.02f, 0.025f, 0.035f, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        prog = link(compile(GLES20.GL_VERTEX_SHADER, VS), compile(GLES20.GL_FRAGMENT_SHADER, FS));
        aPos = GLES20.glGetAttribLocation(prog, "aPos");
        aCol = GLES20.glGetAttribLocation(prog, "aCol");
        uMvp = GLES20.glGetUniformLocation(prog, "uMvp");
        uTint = GLES20.glGetUniformLocation(prog, "uTint");
        uPt = GLES20.glGetUniformLocation(prog, "uPt");

        vProg = link(compile(GLES20.GL_VERTEX_SHADER, VS_TEX), compile(GLES20.GL_FRAGMENT_SHADER, FS_VIDEO));
        vPos = GLES20.glGetAttribLocation(vProg, "aPos");
        vUv = GLES20.glGetAttribLocation(vProg, "aUv");
        vMvp = GLES20.glGetUniformLocation(vProg, "uMvp");
        vTexM = GLES20.glGetUniformLocation(vProg, "uTexM");
        vSampler = GLES20.glGetUniformLocation(vProg, "uTex");

        oProg = link(compile(GLES20.GL_VERTEX_SHADER, VS_TEX), compile(GLES20.GL_FRAGMENT_SHADER, FS_OVERLAY));
        initBackgroundPrograms();
        oPos = GLES20.glGetAttribLocation(oProg, "aPos");
        oUv = GLES20.glGetAttribLocation(oProg, "aUv");
        oMvp = GLES20.glGetUniformLocation(oProg, "uMvp");
        oTexM = GLES20.glGetUniformLocation(oProg, "uTexM");
        oSampler = GLES20.glGetUniformLocation(oProg, "uTex");

        Matrix.setIdentityM(ident, 0);
        buildGeometry();

        int[] t = new int[4];
        GLES20.glGenTextures(4, t, 0);
        oesTex = t[0];
        ovTex = t[1];
        browserOes = t[2];
        hintTex = t[3];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hintTex);
        texParams(GLES20.GL_TEXTURE_2D);
        Bitmap hb = recentreHintBitmap();
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, hb, 0);
        hb.recycle();
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        texParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ovTex);
        texParams(GLES20.GL_TEXTURE_2D);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, browserOes);
        texParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
        overlayAllocated = false;
        bgBuilt = -1;
        panoTex = 0;
        panoLoaded = null;
        panoLoading = null;
        if (overlay != null) { overlay.fullDirty = true; overlay.bandDirty = overlay.bandActive; }

        if (surfaceTexture != null) surfaceTexture.release();
        surfaceTexture = new SurfaceTexture(oesTex);
        surfaceTexture.setOnFrameAvailableListener(this);
        haveFrame = false;
        frameAvailable = false;
        if (media != null) media.attachSurface(new Surface(surfaceTexture));

        // browser surface: a fresh texture on every GL context, handed to the browser's virtual display
        if (browserTexture != null) {
            if (browserHost != null) browserHost.onBrowserSurfaceDestroyed();
            if (browserSurface != null) browserSurface.release();
            browserTexture.release();
        }
        browserTexture = new SurfaceTexture(browserOes);
        browserTexture.setDefaultBufferSize(BROWSER_W, BROWSER_H);
        browserTexture.setOnFrameAvailableListener(this);
        browserSurface = new Surface(browserTexture);
        browserHaveFrame = false;
        browserFrameAvailable = false;
        if (browserHost != null) browserHost.onBrowserSurface(browserSurface, BROWSER_W, BROWSER_H);
    }

    private static void texParams(int target) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        GLES20.glViewport(0, 0, w, h);
        aspect = (float) w / h;
        vpW = w;
        vpH = h;
    }

    // ---- frame pacing diagnostics (logcat tag P3D_PERF, once per second while video is playing) ----
    private long perfWinStartNs, perfMaxDtNs, perfMaxUpdateNs;
    private int perfFrames, perfLong, perfVideoFrames;
    private volatile int perfArrived;
    private int hiWin, loWin;
    private volatile boolean autoFast;

    private void perfFrame(long nowNs, long lastNs) {
        if (lastNs != 0) {
            long d = nowNs - lastNs;
            if (d > perfMaxDtNs) perfMaxDtNs = d;
            if (d > 12_500_000L) perfLong++;       // longer than 1.5 frames at 120 Hz
            perfFrames++;
        }
        if (perfWinStartNs == 0) perfWinStartNs = nowNs;
        if (nowNs - perfWinStartNs >= 1_000_000_000L) {
            // Auto fast loop: on when the video delivers 38+ frames/s (50/60 fps content) for two windows, off after three below.
            if (media != null && media.hasMedia() && perfArrived >= 38) { hiWin++; loWin = 0; if (hiWin >= 2) autoFast = true; }
            else { loWin++; hiWin = 0; if (loWin >= 3) autoFast = false; }
            if (MediaEngine.exp.contains("perf") && media != null && media.hasMedia()) {
                android.util.Log.i("P3D_PERF", "gl=" + perfFrames + "fps maxdt=" + perfMaxDtNs / 1000000 + "ms long>12.5ms="
                        + perfLong + " fast=" + fastLoop + " arrived=" + perfArrived + " videoFrames=" + perfVideoFrames + " maxUpdateTex=" + perfMaxUpdateNs / 1000000 + "ms");
            }
            perfWinStartNs = nowNs; perfFrames = 0; perfLong = 0; perfMaxDtNs = 0; perfMaxUpdateNs = 0; perfVideoFrames = 0; perfArrived = 0;
        }
    }

    // ---- loop pacing ----
    // Normally the loop is locked to the display (swap interval 1). With 60 fps video on a 60 Hz display that loses
    // frames: ExoPlayer releases frames up to 50 ms early, two can land between vsync-locked latches, and the
    // SurfaceTexture queue replaces the older one. Running the loop at ~120 Hz unlocked from vsync latches each frame.
    private boolean fastLoop;
    private long nextTickNs;

    private void paceLoop() {
        boolean want = (autoFast || MediaEngine.exp.contains("fast120")) && !MediaEngine.exp.contains("nofast");
        if (want != fastLoop) {
            fastLoop = want;
            android.opengl.EGL14.eglSwapInterval(android.opengl.EGL14.eglGetCurrentDisplay(), want ? 0 : 1);
            nextTickNs = 0;
        }
        if (!fastLoop) return;
        long now = System.nanoTime();
        if (nextTickNs == 0 || now - nextTickNs > 50_000_000L) nextTickNs = now;
        nextTickNs += 8_333_333L;
        if (nextTickNs > now) java.util.concurrent.locks.LockSupport.parkNanos(nextTickNs - now);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        paceLoop();
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        long nowNs = System.nanoTime();
        float dt = lastFrameNs == 0 ? 0f : Math.min(0.1f, (nowNs - lastFrameNs) * 1e-9f);
        perfFrame(nowNs, lastFrameNs);
        lastFrameNs = nowNs;
        float target = uiFlat ? 0f : curve;
        if (curveEff < 0f) curveEff = target;
        float step = dt * 4f;                       // a full flat <-> curved change takes about 0.25 s
        curveEff = Math.abs(target - curveEff) <= step ? target : curveEff + Math.signum(target - curveEff) * step;
        Matrix.perspectiveM(proj, 0, fovY, aspect, 0.1f, 100f);

        synchronized (lock) {
            if (haveOrientation) {
                Matrix.setRotateM(rz, 0, -yawOffsetDeg, 0, 0, 1);
                Matrix.multiplyMM(view, 0, rot, 0, rz, 0);   // R^T * Rz(-psi)
            } else {
                // level, facing +Y (the screen), Z up
                Matrix.setLookAtM(view, 0, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f);
            }
        }
        // Camera axes are the device axes (x right, y up, -z forward), so no extra basis change.
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0);

        // ---- background ----
        GLES20.glUseProgram(prog);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        drawBackground();
        if (showMarkers) {
            tint(1f);
            draw(triBuf, triCount, GLES20.GL_TRIANGLES);
        }

        // ---- screen ----
        if (surfaceTexture != null && frameAvailable) {
            frameAvailable = false;
            long u0 = System.nanoTime();
            surfaceTexture.updateTexImage();
            long ud = System.nanoTime() - u0;
            if (ud > perfMaxUpdateNs) perfMaxUpdateNs = ud;
            perfVideoFrames++;
            surfaceTexture.getTransformMatrix(texM);
            haveFrame = true;
        }
        if (media == null || !media.hasMedia()) haveFrame = false;
        if (browserTexture != null && browserFrameAvailable) {
            browserFrameAvailable = false;
            browserTexture.updateTexImage();
            browserTexture.getTransformMatrix(texMBrowser);
            browserHaveFrame = true;
        }
        drawScreen();

        if (showCrosshair) {
            GLES20.glUseProgram(prog);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glUniformMatrix4fv(uMvp, 1, false, ident, 0);
            tint(1f);
            draw(crossBuf, 4, GLES20.GL_LINES);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        }
        drawLostIndicator();
    }

    // ---- backgrounds --------------------------------------------------------------------------

    private int bgBuilt = -1;
    private FloatBuffer domeBuf, starsBuf, brightStarsBuf;
    private int starsN, brightStarsN;
    private int skyProg, skyPos, skyMvp, skyZen, skyMid, skyHor, skyGnd, skyGlowDir, skyGlowCol, skyTint, skyMidAt,
            skyGlowPow, skyCalm;
    private int panoProg, panoPos, panoMvp, panoSampler, panoTurn, panoTint;
    private int maxTex = 4096;

    private void tint(float v) { GLES20.glUniform3f(uTint, v, v, v); }

    private void initBackgroundPrograms() {
        int vs = compile(GLES20.GL_VERTEX_SHADER, Backdrops.VS_DOME);
        skyProg = link(vs, compile(GLES20.GL_FRAGMENT_SHADER, Backdrops.FS_SKY));
        skyPos = GLES20.glGetAttribLocation(skyProg, "aPos");
        skyMvp = GLES20.glGetUniformLocation(skyProg, "uMvp");
        skyZen = GLES20.glGetUniformLocation(skyProg, "uZen");
        skyMid = GLES20.glGetUniformLocation(skyProg, "uMid");
        skyHor = GLES20.glGetUniformLocation(skyProg, "uHor");
        skyGnd = GLES20.glGetUniformLocation(skyProg, "uGnd");
        skyGlowDir = GLES20.glGetUniformLocation(skyProg, "uGlowDir");
        skyGlowCol = GLES20.glGetUniformLocation(skyProg, "uGlowCol");
        skyTint = GLES20.glGetUniformLocation(skyProg, "uTint");
        skyMidAt = GLES20.glGetUniformLocation(skyProg, "uMidAt");
        skyGlowPow = GLES20.glGetUniformLocation(skyProg, "uGlowPow");
        skyCalm = GLES20.glGetUniformLocation(skyProg, "uCalm");
        panoProg = link(compile(GLES20.GL_VERTEX_SHADER, Backdrops.VS_DOME),
                compile(GLES20.GL_FRAGMENT_SHADER, Backdrops.FS_PANO));
        panoPos = GLES20.glGetAttribLocation(panoProg, "aPos");
        panoMvp = GLES20.glGetUniformLocation(panoProg, "uMvp");
        panoSampler = GLES20.glGetUniformLocation(panoProg, "uPano");
        panoTurn = GLES20.glGetUniformLocation(panoProg, "uTurn");
        panoTint = GLES20.glGetUniformLocation(panoProg, "uTint");
        int[] mt = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, mt, 0);
        maxTex = Math.max(1024, Math.min(4096, mt[0]));
        if (domeBuf == null) domeBuf = Backdrops.dome();
    }

    private void drawDome(int posLoc) {
        domeBuf.position(0);
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 3 * 4, domeBuf);
        GLES20.glEnableVertexAttribArray(posLoc);
        GLES20.glDepthMask(false);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, Backdrops.domeVertices());
        GLES20.glDepthMask(true);
    }

    private void drawBackground() {
        int kind = Settings.background;
        if (kind != bgBuilt) {
            bgBuilt = kind;
            starsBuf = brightStarsBuf = null;
            if (kind == Backdrops.NIGHT || kind == Backdrops.DUSK) {
                int[] n = new int[1];
                starsBuf = Backdrops.stars(kind, false, n);
                starsN = n[0];
                brightStarsBuf = Backdrops.stars(kind, true, n);
                brightStarsN = n[0];
            }
            if (kind != Backdrops.PANORAMA) freePanorama();
            float g = kind == Backdrops.GRID ? 1f : 0f;
            GLES20.glClearColor(0.02f * g, 0.025f * g, 0.035f * g, 1f);
        }
        float k = envBrightness * 2f;               // brightness setting: 50% = as designed
        switch (kind) {
            case Backdrops.BLACK:
                return;                             // nothing: on the glasses black is see-through
            case Backdrops.GRID:
                tint(envBrightness);
                draw(lineBuf, lineCount, GLES20.GL_LINES);
                return;
            case Backdrops.PANORAMA: {
                if (!ensurePanorama()) return;      // black until the photo is loaded
                GLES20.glUseProgram(panoProg);
                GLES20.glUniformMatrix4fv(panoMvp, 1, false, mvp, 0);
                GLES20.glUniform1f(panoTurn, Settings.panoramaTurn / 360f);
                float pk = envBrightness * 1.3f;    // photos are bright: dimmer than the skies by default
                GLES20.glUniform3f(panoTint, pk, pk, pk);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, panoTex);
                GLES20.glUniform1i(panoSampler, 0);
                drawDome(panoPos);
                GLES20.glUseProgram(prog);
                return;
            }
            default: {
                float[][] c = Backdrops.skyColours(kind);
                GLES20.glUseProgram(skyProg);
                GLES20.glUniformMatrix4fv(skyMvp, 1, false, mvp, 0);
                GLES20.glUniform3fv(skyZen, 1, c[0], 0);
                GLES20.glUniform3fv(skyMid, 1, c[1], 0);
                GLES20.glUniform3fv(skyHor, 1, c[2], 0);
                GLES20.glUniform3fv(skyGnd, 1, c[3], 0);
                GLES20.glUniform3fv(skyGlowDir, 1, c[4], 0);
                GLES20.glUniform3fv(skyGlowCol, 1, c[5], 0);
                GLES20.glUniform1f(skyMidAt, c[6][0]);
                GLES20.glUniform1f(skyGlowPow, c[6][1]);
                GLES20.glUniform1f(skyCalm, c[6][2]);
                GLES20.glUniform3f(skyTint, k, k, k);
                drawDome(skyPos);
                GLES20.glUseProgram(prog);
                GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
                tint(k);
                GLES20.glDepthMask(false);
                GLES20.glUniform1f(uPt, 2f);
                draw(starsBuf, starsN, GLES20.GL_POINTS);
                GLES20.glUniform1f(uPt, 3.5f);
                draw(brightStarsBuf, brightStarsN, GLES20.GL_POINTS);
                GLES20.glUniform1f(uPt, 1f);
                GLES20.glDepthMask(true);
            }
        }
    }

    // 360 photo: decoded off the GL thread, uploaded here. The previous photo stays up until the new one is ready.
    private int panoTex = 0;
    private String panoLoaded = null, panoLoading = null;
    private volatile Bitmap panoPending;
    private volatile String panoPendingPath;

    private boolean ensurePanorama() {
        Bitmap pend = panoPending;
        if (pend != null) {
            panoPending = null;
            if (panoTex == 0) {
                int[] t = new int[1];
                GLES20.glGenTextures(1, t, 0);
                panoTex = t[0];
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, panoTex);
            texParams(GLES20.GL_TEXTURE_2D);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, pend, 0);
            pend.recycle();
            panoLoaded = panoPendingPath;
        }
        final String want = Settings.panoramaFile;
        if (want != null && !want.isEmpty() && !want.equals(panoLoaded) && !want.equals(panoLoading)) {
            panoLoading = want;
            final int maxW = maxTex;
            new Thread(new Runnable() {
                @Override public void run() {
                    Bitmap b = Backdrops.decodePanorama(want, maxW);
                    if (b != null) { panoPendingPath = want; panoPending = b; }
                }
            }, "panorama-decode").start();
        }
        return panoTex != 0 && panoLoaded != null;
    }

    private void freePanorama() {
        if (panoTex != 0) GLES20.glDeleteTextures(1, new int[]{panoTex}, 0);
        panoTex = 0;
        panoLoaded = null;
        panoLoading = null;
        Bitmap pend = panoPending;
        panoPending = null;
        if (pend != null) pend.recycle();
    }

    // ---- "where is the screen?" indicator (pinned to the view) ------------------------------

    /**
     * When the screen's centre leaves the view (the glasses drifted, or tracking follows the Pocket TV in your hand)
     * there is nothing to see but the room. Draw an arrow at the edge of the view pointing back to the screen and a
     * "Hold OK to recentre" label.
     */
    private void drawLostIndicator() {
        boolean on;
        synchronized (lock) { on = haveOrientation; }
        if (!on || !Settings.lostArrow) { screenLost = false; return; }
        float ty = (float) Math.tan(Math.toRadians(fovY / 2f)), tx = ty * aspect;

        // How much of the screen is in view: sample points over its whole (possibly curved) surface.
        float w = 2f * SCREEN_DIST * (float) Math.tan(Math.toRadians(screenWidthDeg / 2f)), h = w * 9f / 16f;
        int inView = 0;
        for (int i = 0; i < PROBE_COLS; i++) {
            for (int j = 0; j < PROBE_ROWS; j++) {
                surfacePoint(-w / 2 + w * i / (PROBE_COLS - 1), -h / 2 + h * j / (PROBE_ROWS - 1), 0f, p);
                probeWorld[0] = p[0]; probeWorld[1] = p[1]; probeWorld[2] = p[2];
                Matrix.multiplyMV(eye, 0, view, 0, probeWorld, 0);
                if (eye[2] < -0.01f && Math.abs(eye[0] / -eye[2]) <= tx && Math.abs(eye[1] / -eye[2]) <= ty) inView++;
            }
        }
        float visible = inView / (float) (PROBE_COLS * PROBE_ROWS);
        // 0: only when none of the screen is in view; 1: when under a quarter is; 2: when under half is.
        // A little hysteresis on the way back so it does not flicker at the threshold.
        float show = Settings.lostWhen == 2 ? 0.5f : Settings.lostWhen == 1 ? 0.25f : 0f;
        boolean lost = screenLost ? visible <= show + (show > 0 ? 0.1f : 0f) : (show == 0 ? visible == 0 : visible < show);
        screenLost = lost;
        if (!lost) return;

        Matrix.multiplyMV(eye, 0, view, 0, centreWorld, 0);
        float ex = eye[0], ey = eye[1];

        // direction to the screen in normalised view coordinates
        float dx = ex / tx, dy = ey / ty;
        if (Math.abs(dx) < 1e-3f && Math.abs(dy) < 1e-3f) dx = 1f;   // straight behind: point right
        float k = 0.82f / Math.max(Math.abs(dx), Math.abs(dy));
        float px = dx * k, py = dy * k;
        // arrow shape in "y-NDC" units; x offsets are divided by the aspect so it is not stretched
        float ux = px * aspect, uy = py;
        float ul = (float) Math.sqrt(ux * ux + uy * uy);
        ux /= ul;
        uy /= ul;
        float vx = -uy, vy = ux;
        float s = 0.07f;
        float[][] tri = {
                {ux * s * 1.3f, uy * s * 1.3f},
                {-ux * s * 0.7f + vx * s, -uy * s * 0.7f + vy * s},
                {-ux * s * 0.7f - vx * s, -uy * s * 0.7f - vy * s}};
        arrowBuf.position(0);
        for (int pass = 0; pass < 2; pass++) {       // dark outline, then the accent arrow
            float g = pass == 0 ? 1.35f : 1f;
            for (float[] o : tri) {
                arrowBuf.put(px + o[0] * g / aspect).put(py + o[1] * g).put(0f);
                if (pass == 0) arrowBuf.put(0f).put(0f).put(0f).put(1f);
                else arrowBuf.put(0.31f).put(0.76f).put(0.97f).put(1f);
            }
        }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(prog);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, ident, 0);
        tint(1f);
        draw(arrowBuf, 6, GLES20.GL_TRIANGLES);

        // label in the middle of the view, at its native pixel size
        float hw = (float) HINT_W / vpW, hh = (float) HINT_H / vpH;
        float cy = -0.12f;
        hintBuf.position(0);
        hintBuf.put(-hw).put(cy + hh).put(0f).put(0f).put(0f);
        hintBuf.put(-hw).put(cy - hh).put(0f).put(0f).put(1f);
        hintBuf.put(hw).put(cy + hh).put(0f).put(1f).put(0f);
        hintBuf.put(hw).put(cy - hh).put(0f).put(1f).put(1f);
        GLES20.glUseProgram(oProg);
        GLES20.glUniformMatrix4fv(oMvp, 1, false, ident, 0);
        GLES20.glUniformMatrix4fv(oTexM, 1, false, ident, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hintTex);
        GLES20.glUniform1i(oSampler, 0);
        GLES20.glEnable(GLES20.GL_BLEND);
        drawTex(hintBuf, oPos, oUv, 4);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(prog);
    }

    private static Bitmap recentreHintBitmap() {
        Bitmap b = Bitmap.createBitmap(HINT_W, HINT_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setColor(Color.argb(225, 16, 20, 26));
        c.drawRoundRect(new RectF(0, 0, HINT_W, HINT_H), HINT_H / 2f, HINT_H / 2f, f);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        t.setTextSize(44);
        t.setColor(Color.rgb(236, 240, 244));
        String s = "Hold OK to recentre";
        c.drawText(s, (HINT_W - t.measureText(s)) / 2f, HINT_H / 2f + 15, t);
        return b;
    }

    // ---- screen geometry --------------------------------------------------------------------

    /** Point on the (possibly curved) screen surface for flat offset x (right) and z (up); dr pulls toward the viewer. */
    private void surfacePoint(float x, float z, float dr, float[] out) {
        float c = curveEff < 0f ? curve : curveEff;
        if (c < 0.01f) {
            out[0] = x; out[1] = SCREEN_DIST - dr; out[2] = z;
            return;
        }
        float r = SCREEN_DIST / c;                 // c = 1 -> radius = distance (wraps around the viewer)
        float phi = x / r;
        out[0] = (r - dr) * (float) Math.sin(phi);
        out[1] = (SCREEN_DIST - r) + (r - dr) * (float) Math.cos(phi);
        out[2] = z;
    }

    private final float[] p = new float[3];

    private void drawScreen() {
        boolean hasMedia = (media != null && media.hasMedia()) || browserActive;
        float w = 2f * SCREEN_DIST * (float) Math.tan(Math.toRadians(screenWidthDeg / 2f));
        float h = w * 9f / 16f;

        // 1. backing panel (only while a video is loaded; the library paints its own opaque background)
        if (hasMedia) {
            backBuf.position(0);
            for (int i = 0; i <= SEGMENTS; i++) {
                float x = -w / 2 + w * i / SEGMENTS;
                for (int k = 0; k < 2; k++) {
                    surfacePoint(x, k == 0 ? h / 2 : -h / 2, 0f, p);
                    backBuf.put(p[0]).put(p[1]).put(p[2]).put(0.02f).put(0.02f).put(0.02f).put(1f);
                }
            }
            GLES20.glUseProgram(prog);
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
            tint(1f);
            draw(backBuf, (SEGMENTS + 1) * 2, GLES20.GL_TRIANGLE_STRIP);
        }

        // 2. video, letterboxed inside the 16:9 panel
        if (!browserActive && hasMedia && haveFrame) {
            float a = (float) media.getVideoWidth() / Math.max(1, media.getVideoHeight());
            float vw = w, vh = h;
            if (a > 16f / 9f) vh = w / a; else vw = h * a;
            videoBuf.position(0);
            for (int i = 0; i <= SEGMENTS; i++) {
                float f = (float) i / SEGMENTS;
                float x = -vw / 2 + vw * f;
                for (int k = 0; k < 2; k++) {
                    surfacePoint(x, k == 0 ? vh / 2 : -vh / 2, 0.01f, p);
                    videoBuf.put(p[0]).put(p[1]).put(p[2]).put(f).put(k == 0 ? 1f : 0f);
                }
            }
            GLES20.glUseProgram(vProg);
            GLES20.glUniformMatrix4fv(vMvp, 1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(vTexM, 1, false, texM, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
            GLES20.glUniform1i(vSampler, 0);
            drawTex(videoBuf, vPos, vUv, (SEGMENTS + 1) * 2);
        }

        // 2b. browser page (full 16:9 panel)
        if (browserActive && browserHaveFrame) {
            videoBuf.position(0);
            for (int i = 0; i <= SEGMENTS; i++) {
                float f = (float) i / SEGMENTS;
                float x = -w / 2 + w * f;
                for (int k = 0; k < 2; k++) {
                    surfacePoint(x, k == 0 ? h / 2 : -h / 2, 0.01f, p);
                    videoBuf.put(p[0]).put(p[1]).put(p[2]).put(f).put(k == 0 ? 1f : 0f);
                }
            }
            GLES20.glUseProgram(vProg);
            GLES20.glUniformMatrix4fv(vMvp, 1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(vTexM, 1, false, texMBrowser, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, browserOes);
            GLES20.glUniform1i(vSampler, 0);
            drawTex(videoBuf, vPos, vUv, (SEGMENTS + 1) * 2);
        }

        // 3. UI overlay (library / HUD / subtitles / menus), always the full panel
        if (overlay != null) {
            uploadOverlay();
            overBuf.position(0);
            for (int i = 0; i <= SEGMENTS; i++) {
                float f = (float) i / SEGMENTS;
                float x = -w / 2 + w * f;
                for (int k = 0; k < 2; k++) {
                    surfacePoint(x, k == 0 ? h / 2 : -h / 2, 0.02f, p);
                    overBuf.put(p[0]).put(p[1]).put(p[2]).put(f).put(k == 0 ? 0f : 1f);
                }
            }
            GLES20.glUseProgram(oProg);
            GLES20.glUniformMatrix4fv(oMvp, 1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(oTexM, 1, false, ident, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ovTex);
            GLES20.glUniform1i(oSampler, 0);
            GLES20.glEnable(GLES20.GL_BLEND);
            drawTex(overBuf, oPos, oUv, (SEGMENTS + 1) * 2);
            GLES20.glDisable(GLES20.GL_BLEND);
        }
        if (browserActive && cursorVisible) drawCursor(w, h);
        GLES20.glUseProgram(prog);
    }

    /** A small arrow cursor on the screen surface; position is in overlay pixels (1920 x 1080). */
    private void drawCursor(float w, float h) {
        float cx = cursorX, cy = cursorY;
        // outline first (black, slightly larger), then the white arrow; both in overlay pixels relative to the tip
        float[][] tris = {
                {-3, -3, -3, 40, 29, 27},
                {0, 0, 0, 32, 22, 23},
        };
        cursorBuf.position(0);
        for (int t = 0; t < 2; t++) {
            float c = t == 0 ? 0f : 1f;
            for (int v = 0; v < 3; v++) {
                float px = cx + tris[t][v * 2], py = cy + tris[t][v * 2 + 1];
                surfacePoint((px / Overlay.W - 0.5f) * w, (0.5f - py / Overlay.H) * h, 0.03f + t * 0.005f, p);
                cursorBuf.put(p[0]).put(p[1]).put(p[2]).put(c).put(c).put(c).put(1f);
            }
        }
        GLES20.glUseProgram(prog);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        tint(1f);
        draw(cursorBuf, 6, GLES20.GL_TRIANGLES);
    }

    private void uploadOverlay() {
        if (overlayAllocated && !overlay.fullDirty && !overlay.bandDirty) return;
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ovTex);
        synchronized (overlay.lock) {
            if (!overlayAllocated) {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlay.bitmap, 0);
                overlayAllocated = true;
                overlay.fullDirty = false;
            } else if (overlay.fullDirty) {
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, overlay.bitmap);
                overlay.fullDirty = false;
            }
            if (overlay.bandDirty) {   // bottom strip, after any full upload so it wins
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, Overlay.H - Overlay.BAND_H, overlay.band);
                overlay.bandDirty = false;
            }
        }
    }

    private void drawTex(FloatBuffer fb, int posLoc, int uvLoc, int count) {
        fb.position(0);
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 5 * 4, fb);
        GLES20.glEnableVertexAttribArray(posLoc);
        fb.position(3);
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 5 * 4, fb);
        GLES20.glEnableVertexAttribArray(uvLoc);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, count);
    }

    private void draw(FloatBuffer fb, int vertexCount, int mode) {
        fb.position(0);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 7 * 4, fb);
        GLES20.glEnableVertexAttribArray(aPos);
        fb.position(3);
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, 7 * 4, fb);
        GLES20.glEnableVertexAttribArray(aCol);
        GLES20.glDrawArrays(mode, 0, vertexCount);
    }

    // ---- static geometry: room + markers ----------------------------------------------------

    private static final float[][] FACE_N = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final float[] FACE_SHADE = {0.85f, 0.55f, 1.0f, 0.5f, 0.75f, 0.4f};

    private void buildGeometry() {
        ArrayList<Float> tri = new ArrayList<Float>();
        cube(tri, 0, 6, 0, 0.8f, 1.0f, 0.2f, 0.2f);   // +Y forward  red
        cube(tri, 6, 0, 0, 0.8f, 0.2f, 0.9f, 0.3f);   // +X right    green
        cube(tri, 0, -6, 0, 0.8f, 0.25f, 0.4f, 1.0f); // -Y back     blue
        cube(tri, -6, 0, 0, 0.8f, 1.0f, 0.9f, 0.2f);  // -X left     yellow
        cube(tri, 0, 0, 6, 0.8f, 1.0f, 1.0f, 1.0f);   // +Z up       white
        cube(tri, 0, 0, -6, 0.8f, 0.5f, 0.5f, 0.55f); // -Z down     grey
        triBuf = toBuffer(tri);
        triCount = tri.size() / 7;

        ArrayList<Float> ln = new ArrayList<Float>();
        for (int i = -10; i <= 10; i++) {          // floor grid at z = -2
            float c = (i == 0) ? 0.7f : 0.25f;
            line(ln, i, -10, -2, i, 10, -2, c, c, c + 0.1f);
            line(ln, -10, i, -2, 10, i, -2, c, c, c + 0.1f);
        }
        for (int deg = 0; deg < 360; deg += 10) {  // horizon ring ticks
            double a = Math.toRadians(deg);
            float x = (float) Math.sin(a), y = (float) Math.cos(a);
            float len = (deg % 30 == 0) ? 0.5f : 0.2f;
            float r = 8f;
            line(ln, x * r, y * r, -len, x * r, y * r, len, 0.9f, 0.9f, 0.3f);
        }
        for (int deg = 0; deg < 360; deg += 5) {   // horizon circle
            double a = Math.toRadians(deg), b = Math.toRadians(deg + 5);
            line(ln, (float) Math.sin(a) * 8, (float) Math.cos(a) * 8, 0,
                    (float) Math.sin(b) * 8, (float) Math.cos(b) * 8, 0, 0.9f, 0.9f, 0.3f);
        }
        lineBuf = toBuffer(ln);
        lineCount = ln.size() / 7;

        ArrayList<Float> cr = new ArrayList<Float>();
        line(cr, -0.03f, 0, 0, 0.03f, 0, 0, 1, 1, 1);
        line(cr, 0, -0.05f, 0, 0, 0.05f, 0, 1, 1, 1);
        crossBuf = toBuffer(cr);
    }

    private static void v(ArrayList<Float> l, float x, float y, float z, float r, float g, float b) {
        l.add(x); l.add(y); l.add(z); l.add(r); l.add(g); l.add(b); l.add(1f);
    }

    private static void line(ArrayList<Float> l, float x0, float y0, float z0,
                             float x1, float y1, float z1, float r, float g, float b) {
        v(l, x0, y0, z0, r, g, b);
        v(l, x1, y1, z1, r, g, b);
    }

    private static void cube(ArrayList<Float> l, float cx, float cy, float cz, float s,
                             float r, float g, float b) {
        float h = s / 2f;
        for (int f = 0; f < 6; f++) {
            float[] n = FACE_N[f];
            float[] u = (n[0] != 0) ? new float[]{0, 1, 0} : new float[]{1, 0, 0};
            float[] w = {n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0]};
            float k = FACE_SHADE[f];
            float[][] c = new float[4][3];
            int[][] sg = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
            float[] ctr = {cx, cy, cz};
            for (int i = 0; i < 4; i++) {
                for (int a = 0; a < 3; a++) {
                    c[i][a] = ctr[a] + (n[a] + u[a] * sg[i][0] + w[a] * sg[i][1]) * h;
                }
            }
            int[] idx = {0, 1, 2, 0, 2, 3};
            for (int i : idx) v(l, c[i][0], c[i][1], c[i][2], r * k, g * k, b * k);
        }
    }

    private static FloatBuffer buf(int floats) {
        return ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private static FloatBuffer toBuffer(ArrayList<Float> l) {
        FloatBuffer fb = buf(l.size());
        for (float f : l) fb.put(f);
        fb.position(0);
        return fb;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    private static int link(int vs, int fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        return p;
    }
}
