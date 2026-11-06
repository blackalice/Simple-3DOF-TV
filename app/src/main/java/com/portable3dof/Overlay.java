package com.portable3dof;

import android.graphics.Bitmap;
import android.graphics.Canvas;

/**
 * The UI overlay as two CPU bitmaps that the renderer uploads into one 1920x1080 GL texture:
 *  - {@link #bitmap}: the whole panel (library, menus, toasts...), uploaded rarely;
 *  - {@link #band}:   the bottom strip (playback HUD + subtitles), which changes about once a second,
 *    so it is uploaded on its own (about 3.7 MB instead of 8.3 MB) to avoid a frame hitch.
 * While a video is playing the band rows of the main bitmap are transparent and the band paints them.
 */
public final class Overlay {
    public static final int W = 1920, H = 1080;
    public static final int BAND_H = 480;

    public final Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    public final Canvas canvas = new Canvas(bitmap);
    public final Bitmap band = Bitmap.createBitmap(W, BAND_H, Bitmap.Config.ARGB_8888);
    public final Canvas bandCanvas = new Canvas(band);
    public final Object lock = new Object();
    public volatile boolean fullDirty = true;
    public volatile boolean bandDirty = false;
    /** True while the band holds the live HUD (playback mode); used to re-upload it after a GL context loss. */
    public volatile boolean bandActive = false;
}
