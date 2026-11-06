package com.portable3dof;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;

/**
 * Shared look and small painting helpers for the Canvas-drawn UI (library, YouTube, settings, playback panel).
 *
 * Sizes are overlay pixels (1920 x 1080 across a ~32-34 degree screen, about 56-60 px per degree). Through the
 * glasses ~30 px is about 0.5 degrees, which is the smallest text we use; body text is 36-44 px.
 */
public final class Ui {
    private Ui() { }

    public static final int ACCENT = Color.rgb(79, 195, 247);
    public static final int BG = Color.rgb(14, 17, 22);
    public static final int PANEL = Color.rgb(24, 29, 37);
    public static final int ROW_SEL = Color.rgb(36, 52, 68);
    public static final int TEXT = Color.rgb(236, 240, 244);
    public static final int TEXT_DIM = Color.rgb(140, 152, 166);
    public static final int AMBER = Color.rgb(255, 202, 87);
    public static final int RED = Color.rgb(255, 64, 64);
    public static final int GREEN = Color.rgb(129, 199, 132);
    public static final int CHIP = Color.rgb(46, 54, 66);

    /** Outline of full-screen pages and floating panels, so they stand out from any background. */
    public static final int OUTLINE = Color.argb(150, 150, 178, 210);
    public static final int REMOVE = Color.rgb(120, 36, 40);

    private static final Paint frameFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint frameStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    static {
        frameStroke.setStyle(Paint.Style.STROKE);
    }

    /** A full-screen page: rounded dark panel with a light outline (instead of a hard-edged rectangle). */
    public static void page(Canvas c) {
        frameFill.setColor(BG);
        r.set(2, 2, 1918, 1078);
        c.drawRoundRect(r, 44, 44, frameFill);
        frameStroke.setColor(OUTLINE);
        frameStroke.setStrokeWidth(4);
        r.set(4, 4, 1916, 1076);
        c.drawRoundRect(r, 42, 42, frameStroke);
    }

    /** Outline for a floating panel / card drawn over video or another page. */
    public static void outline(Canvas c, RectF rect, float radius) {
        frameStroke.setColor(OUTLINE);
        frameStroke.setStrokeWidth(3);
        c.drawRoundRect(rect, radius, radius, frameStroke);
    }

    /** The "Edit list" card at the end of a row of cards. */
    public static void editCard(Canvas c, float x, float y, float w, float h, boolean selected) {
        r.set(x, y, x + w, y + h);
        frameFill.setColor(selected ? ROW_SEL : PANEL);
        c.drawRoundRect(r, 12, 12, frameFill);
        frameStroke.setColor(selected ? ACCENT : OUTLINE);
        frameStroke.setStrokeWidth(selected ? 6 : 3);
        c.drawRoundRect(r, 12, 12, frameStroke);
        float cx = x + w / 2, cy = y + h / 2;
        frameStroke.setColor(selected ? TEXT : TEXT_DIM);
        frameStroke.setStrokeWidth(6);
        c.drawLine(cx - 18, cy - 18, cx + 18, cy + 18, frameStroke);
        c.drawLine(cx - 18, cy + 18, cx + 18, cy - 18, frameStroke);
        hintText.setColor(selected ? TEXT : TEXT_DIM);
        hintText.setTextSize(30);
        c.drawText("Edit list", x, y + h + 42, hintText);
        hintText.setTextSize(26);
        hintText.setColor(TEXT_DIM);
        c.drawText("Remove items", x, y + h + 76, hintText);
        hintText.setTextSize(30);
    }

    /** Footer baseline: every screen puts its key hints here. */
    public static final int HINT_Y = 1030;

    private static final Paint chipFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final TextPaint chipText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private static final TextPaint hintText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF r = new RectF();

    static {
        chipText.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        chipText.setTextSize(24);
        chipText.setColor(TEXT);
        hintText.setTypeface(Typeface.SANS_SERIF);
        hintText.setTextSize(30);
        hintText.setColor(TEXT_DIM);
        chipFill.setColor(CHIP);
    }

    /** Key glyphs for {@link #hints}. */
    public static final String K_OK = "OK", K_BACK = "BACK", K_UPDOWN = "▲▼", K_LR = "◀▶",
            K_LEFT = "◀", K_RIGHT = "▶", K_UP = "▲", K_DOWN = "▼", K_HOLD_OK = "hold OK";

    /**
     * Paints key hints as boxed key chips followed by what they do: hints(c, x, y, K_OK, "Play", K_BACK, "Back").
     * Pass only the keys that do something on the focused item.
     */
    public static void hints(Canvas c, float x, float y, String... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String key = pairs[i], what = pairs[i + 1];
            if (key == null || what == null) continue;
            float kw = chipText.measureText(key);
            r.set(x, y - 31, x + kw + 26, y + 9);
            c.drawRoundRect(r, 9, 9, chipFill);
            c.drawText(key, x + 13, y - 2, chipText);
            x += kw + 38;
            c.drawText(what, x, y, hintText);
            x += hintText.measureText(what) + 44;
        }
    }

    public static String ellipsize(String s, TextPaint p, float maxW) {
        if (s == null) return "";
        if (p.measureText(s) <= maxW) return s;
        int n = s.length();
        while (n > 1 && p.measureText(s, 0, n) + p.measureText("…") > maxW) n--;
        return s.substring(0, n) + "…";
    }

    /** Keeps the END of a long string ("…/Videos/Movies"). */
    public static String ellipsizeStart(String s, TextPaint p, float maxW) {
        if (s == null) return "";
        if (p.measureText(s) <= maxW) return s;
        int n = 0;
        while (n < s.length() - 1 && p.measureText(s, n, s.length()) + p.measureText("…") > maxW) n++;
        return "…" + s.substring(n);
    }

    /** Clock + battery + glasses state, right-aligned in a screen header (CRTXR: the device's own status bar is not visible in the glasses). */
    public static void status(Canvas c, float right, float y, String clock, int batteryPct, boolean glasses) {
        hintText.setTextSize(32);
        StringBuilder sb = new StringBuilder(clock);
        if (batteryPct >= 0) sb.append("   ").append(batteryPct).append('%');
        String s = sb.toString();
        float w = hintText.measureText(s);
        hintText.setColor(TEXT_DIM);
        c.drawText(s, right - w, y, hintText);
        // glasses chip
        String g = glasses ? "Glasses" : "No glasses";
        chipText.setTextSize(26);
        float gw = chipText.measureText(g);
        float x1 = right - w - 30, x0 = x1 - gw - 54;
        r.set(x0, y - 34, x1, y + 8);
        chipFill.setColor(glasses ? Color.rgb(32, 64, 44) : Color.rgb(74, 52, 24));
        c.drawRoundRect(r, 21, 21, chipFill);
        chipFill.setColor(glasses ? GREEN : AMBER);
        c.drawCircle(x0 + 22, y - 13, 7, chipFill);
        chipText.setColor(TEXT);
        c.drawText(g, x0 + 38, y - 4, chipText);
        chipText.setTextSize(24);
        chipFill.setColor(CHIP);
        hintText.setTextSize(30);
    }
}
