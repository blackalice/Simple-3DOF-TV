package com.portable3dof;

import android.content.Context;
import android.content.SharedPreferences;

/** Persisted user tunables. Renderer values are copied in/out; tracking and browser values live here. */
public final class Settings {
    /** 0 = off, 1 = low, 2 = medium, 3 = high head-tracking smoothing. */
    public static volatile int smoothing = 2;
    /** Head-pose prediction lookahead in milliseconds (compensates pipeline latency). */
    public static volatile int predictMs = 8;
    /** Request the 120 Hz display mode when the glasses offer it. */
    public static volatile boolean hz120 = true;

    /** Use the Pocket TV's own gyro as an air-mouse pointer in the browser. */
    public static volatile boolean gyroPointer = false;
    /** Gyro pointer speed in browser pixels per radian of rotation. */
    public static volatile int pointerSens = 1400;
    /** Which Pocket TV axis points at the screen: 0..5 = +X, -X, +Y, -Y, +Z, -Z. */
    public static volatile int pointerAxis = 2;

    /** Show an arrow toward the screen when it is out of view. */
    public static volatile boolean lostArrow = true;
    /** When the arrow appears: 0 = screen fully out of view, 1 = mostly (3/4) out, 2 = half out. */
    public static volatile int lostWhen = 0;
    /** Menu sounds: 0 = off, 1 = quiet, 2 = normal. */
    public static volatile int soundLevel = 1;
    /** Scene around the screen (Backdrops.NIGHT, BLACK, DUSK, GRID, PANORAMA). */
    public static volatile int background = Backdrops.NIGHT;
    /** The 360 photo background: file path, and how far it is turned (degrees). */
    public static volatile String panoramaFile = "";
    public static volatile int panoramaTurn = 0;
    /** Leave YouTube Shorts out of search, feeds and channels. */
    public static volatile boolean hideShorts = true;

    /** Max YouTube quality: 0..5 = 360p, 480p, 720p, 1080p, 1440p, 2160p. */
    public static volatile int ytQuality = 3;

    /** Playback volume inside the app, 0..1. */
    public static volatile float volume = 1f;

    /** The EULA version the user accepted (0 = not yet); see LegalUi.EULA_VERSION. */
    public static volatile int eulaAccepted = 0;

    /** Browser page zoom (percent) and desktop-site mode. */
    public static volatile int browserZoom = 110;
    public static volatile boolean browserDesktop = true;

    private Settings() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static void load(Context c, SceneRenderer r) {
        SharedPreferences p = prefs(c);
        r.setFov(p.getFloat("fov", r.getFov()));
        r.setScreenWidthDeg(p.getFloat("screenDeg", r.getScreenWidthDeg()));
        r.setEnvBrightness(p.getFloat("env", r.getEnvBrightness()));
        r.setCurve(p.getFloat("curve", r.getCurve()));
        smoothing = p.getInt("smoothing", smoothing);
        predictMs = p.getInt("predictMs", predictMs);
        hz120 = p.getBoolean("hz120", hz120);
        gyroPointer = p.getBoolean("gyroPointer", gyroPointer);
        pointerSens = p.getInt("pointerSens", pointerSens);
        pointerAxis = p.getInt("pointerAxis", pointerAxis);
        ytQuality = p.getInt("ytQuality", ytQuality);
        lostArrow = p.getBoolean("lostArrow", lostArrow);
        lostWhen = p.getInt("lostWhen", lostWhen);
        soundLevel = p.getInt("soundLevel", p.getBoolean("menuSounds", true) ? soundLevel : 0);
        background = p.getInt("background", background);
        // "Ambient glow" (old index 3) was removed: it falls back to the default, and Grid room / 360 photo moved down one.
        if (background == 3) background = Backdrops.NIGHT; else if (background > 3) background--;
        hideShorts = p.getBoolean("hideShorts", hideShorts);
        panoramaFile = p.getString("panoramaFile", panoramaFile);
        panoramaTurn = p.getInt("panoramaTurn", panoramaTurn);
        eulaAccepted = p.getInt("eulaAccepted", 0);
        volume = p.getFloat("volume", volume);
        browserZoom = p.getInt("browserZoom", browserZoom);
        browserDesktop = p.getBoolean("browserDesktop", browserDesktop);
    }

    public static void save(Context c, SceneRenderer r) {
        prefs(c).edit()
                .putFloat("fov", r.getFov())
                .putFloat("screenDeg", r.getScreenWidthDeg())
                .putFloat("env", r.getEnvBrightness())
                .putFloat("curve", r.getCurve())
                .putInt("smoothing", smoothing)
                .putInt("predictMs", predictMs)
                .putBoolean("hz120", hz120)
                .putBoolean("gyroPointer", gyroPointer)
                .putInt("pointerSens", pointerSens)
                .putInt("pointerAxis", pointerAxis)
                .putInt("ytQuality", ytQuality)
                .putBoolean("lostArrow", lostArrow)
                .putInt("lostWhen", lostWhen)
                .putInt("soundLevel", soundLevel)
                .putInt("background", background)
                .putBoolean("hideShorts", hideShorts)
                .putString("panoramaFile", panoramaFile)
                .putInt("panoramaTurn", panoramaTurn)
                .putInt("eulaAccepted", eulaAccepted)
                .putFloat("volume", volume)
                .putInt("browserZoom", browserZoom)
                .putBoolean("browserDesktop", browserDesktop)
                .apply();
    }
}
