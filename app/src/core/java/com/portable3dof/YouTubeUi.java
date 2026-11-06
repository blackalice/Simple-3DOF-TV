package com.portable3dof;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;

/** Stand-in for the "core" edition: no YouTube UI. The shared code only calls these when the feature exists. */
public final class YouTubeUi {
    public interface Host { void playYoutube(String url, String title); }

    public YouTubeUi(Context ctx, Host host) { }

    public void open() { }
    public void reshow() { }
    public void close() { }
    public boolean keyboardOpen() { return false; }
    public void showChannel(String url, String name) { }
    public void recordPlayed(YouTubeClient.Playable p) { }
    public Bitmap thumbFor(String url) { return null; }
    public int thumbVersion() { return 0; }
    public boolean onKey(int k, int repeat) { return false; }
    public YouTubeClient.Item focusedVideo() { return null; }
    public void paintTop(Canvas c) { }
    public void paintBottom(Canvas c) { }
    public int topHash() { return 0; }
    public int bottomHash() { return 0; }
}
