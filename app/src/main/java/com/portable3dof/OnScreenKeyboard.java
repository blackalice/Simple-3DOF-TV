package com.portable3dof;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;

import java.util.ArrayList;
import java.util.List;

/**
 * An on-screen keyboard drawn into the overlay's bottom band (overlay pixel coordinates). It only keeps the layout and
 * the highlighted key; owners drive it with D-pad moves or pointer hovers and receive text through {@link Listener}.
 *
 * Two layouts: QWERTY with symbols (the browser: URLs, forms) and an alphabetical grid for searching with a D-pad
 * (letters in order are quicker to find than QWERTY when you cannot touch-type; rows wrap left/right).
 */
public final class OnScreenKeyboard {

    public interface Listener {
        void onText(String s);
        void onBackspace();
        void onEnter();
        void onClose();
        default void onClear() { }
    }

    /** Overlay pixel y where the keyboard panel starts. */
    public static final int PANEL_TOP = 624;
    private static final int KEYS_TOP = 640, ROW_H = 76, GAP = 8, X0 = 60, X1 = 1860;
    private static final int T_CHAR = 0, T_BKSP = 1, T_ENTER = 2, T_SHIFT = 3, T_CLOSE = 4, T_SPACE = 5, T_CLEAR = 6;

    private static final int ACCENT = Ui.ACCENT;
    private static final int PANEL = Color.rgb(34, 40, 50);
    private static final int PANEL_SEL = Color.rgb(48, 74, 98);
    private static final int BAR = Color.argb(238, 16, 20, 26);
    private static final int TEXT = Ui.TEXT;

    private static final class Key {
        final String label, text;
        final int type;
        final float weight;
        float x0, x1;

        Key(String label, String text, int type, float weight) {
            this.label = label;
            this.text = text;
            this.type = type;
            this.weight = weight;
        }
    }

    private final Key[][] rows;
    private final boolean abc;
    private boolean open = false, shift = false;
    private int r = 1, c = 0;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    /** QWERTY keyboard (browser). */
    public OnScreenKeyboard() { this(false); }

    /** abc = alphabetical search keyboard. */
    public OnScreenKeyboard(boolean abc) {
        this.abc = abc;
        text.setTypeface(Typeface.SANS_SERIF);
        stroke.setStyle(Paint.Style.STROKE);
        List<Key[]> l = new ArrayList<>();
        if (abc) {
            l.add(keys("a b c d e f g h i j", null));
            l.add(keys("k l m n o p q r s t", null));
            l.add(keys("u v w x y z ' - & ?", null));
            l.add(keys("1 2 3 4 5 6 7 8 9 0", null));
            l.add(new Key[]{new Key("Space", " ", T_SPACE, 4f), new Key("Delete", null, T_BKSP, 2f),
                    new Key("Clear", null, T_CLEAR, 2f), new Key("Search", null, T_ENTER, 2f)});
        } else {
            l.add(keys("1 2 3 4 5 6 7 8 9 0 - =", new Key("Del", null, T_BKSP, 2f)));
            l.add(keys("q w e r t y u i o p [ ]", null));
            l.add(keys("a s d f g h j k l ; '", new Key("Enter", null, T_ENTER, 2f)));
            l.add(keys("z x c v b n m , . / ?", new Key("Shift", null, T_SHIFT, 2f)));
            l.add(new Key[]{new Key("Close", null, T_CLOSE, 2f), new Key(".com", ".com", T_CHAR, 2f),
                    new Key("Space", " ", T_SPACE, 5f), new Key("Go", null, T_ENTER, 2f)});
        }
        rows = l.toArray(new Key[0][]);
        for (Key[] row : rows) layout(row);
    }

    private static Key[] keys(String chars, Key extra) {
        List<Key> l = new ArrayList<>();
        for (String s : chars.split(" ")) l.add(new Key(s, s, T_CHAR, 1f));
        if (extra != null) l.add(extra);
        return l.toArray(new Key[0]);
    }

    private static void layout(Key[] row) {
        float total = 0;
        for (Key k : row) total += k.weight;
        float unit = (X1 - X0 - GAP * (row.length - 1)) / total;
        float x = X0;
        for (Key k : row) {
            k.x0 = x;
            k.x1 = x + unit * k.weight;
            x = k.x1 + GAP;
        }
    }

    // ---- state ------------------------------------------------------------------------------

    public boolean isOpen() { return open; }

    public void open() { open = true; r = abc ? 0 : 1; c = 0; shift = false; }

    public void close() { open = false; shift = false; }

    /** True when the overlay y coordinate is over the keyboard panel. */
    public boolean contains(float oy) { return open && oy >= PANEL_TOP; }

    /** The highlight is on the top row (owners can move focus above the keyboard). */
    public boolean atTop() { return r == 0; }

    public void left() {
        if (abc) c = (c - 1 + rows[r].length) % rows[r].length;
        else c = Math.max(0, c - 1);
    }

    public void right() {
        if (abc) c = (c + 1) % rows[r].length;
        else c = Math.min(rows[r].length - 1, c + 1);
    }

    public void up() { moveRow(-1); }

    public void down() { moveRow(1); }

    private void moveRow(int d) {
        int nr = r + d;
        if (nr < 0 || nr >= rows.length) return;
        float mid = (rows[r][c].x0 + rows[r][c].x1) / 2f;
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < rows[nr].length; i++) {
            float m = (rows[nr][i].x0 + rows[nr][i].x1) / 2f;
            if (Math.abs(m - mid) < bestDist) { bestDist = Math.abs(m - mid); best = i; }
        }
        r = nr;
        c = best;
    }

    /** Pointer hover: highlight the key under the pointer (overlay pixel coordinates). */
    public void hover(float ox, float oy) {
        if (!contains(oy)) return;
        for (int ri = 0; ri < rows.length; ri++) {
            float y0 = KEYS_TOP + ri * (ROW_H + GAP);
            if (oy >= y0 - 4 && oy <= y0 + ROW_H + 4) {
                for (int ci = 0; ci < rows[ri].length; ci++) {
                    if (ox >= rows[ri][ci].x0 - 4 && ox <= rows[ri][ci].x1 + 4) { r = ri; c = ci; return; }
                }
            }
        }
    }

    public void pressSelected(Listener l) {
        Key k = rows[r][c];
        switch (k.type) {
            case T_CHAR:
            case T_SPACE:
                l.onText(shift ? k.text.toUpperCase() : k.text);
                shift = false;
                break;
            case T_BKSP: l.onBackspace(); break;
            case T_ENTER: l.onEnter(); break;
            case T_SHIFT: shift = !shift; break;
            case T_CLOSE: l.onClose(); break;
            case T_CLEAR: l.onClear(); break;
            default: break;
        }
    }

    public int hash() {
        return open ? 1 + r * 16 + c * 256 + (shift ? 65536 : 0) : 0;
    }

    // ---- painting ---------------------------------------------------------------------------

    /** Draws into a canvas that uses full-panel overlay coordinates (the caller shifts it for the band). */
    public void paint(Canvas cv) { paint(cv, true); }

    /** focused = false: draw the keyboard without its highlight (focus is somewhere above it). */
    public void paint(Canvas cv, boolean focused) {
        if (!open) return;
        fill.setColor(BAR);
        rect.set(30, PANEL_TOP - 2, Overlay.W - 30, Overlay.H - 8);
        cv.drawRoundRect(rect, 24, 24, fill);
        Ui.outline(cv, rect, 24);
        for (int ri = 0; ri < rows.length; ri++) {
            float y = KEYS_TOP + ri * (ROW_H + GAP);
            for (int ci = 0; ci < rows[ri].length; ci++) {
                Key key = rows[ri][ci];
                boolean sel = focused && ri == r && ci == c;
                fill.setColor(sel ? PANEL_SEL : PANEL);
                rect.set(key.x0, y, key.x1, y + ROW_H);
                cv.drawRoundRect(rect, 12, 12, fill);
                if (sel) {
                    stroke.setColor(ACCENT);
                    stroke.setStrokeWidth(4);
                    cv.drawRoundRect(rect, 12, 12, stroke);
                }
                String label = key.label;
                if (key.type == T_CHAR && key.label.length() == 1 && shift) label = label.toUpperCase();
                if (key.type == T_SHIFT && shift) label = "SHIFT";
                text.setColor(key.type == T_CHAR || key.type == T_SPACE ? TEXT : ACCENT);
                text.setTextSize(key.label.length() > 2 ? 30 : 38);
                float tw = text.measureText(label);
                cv.drawText(label, (key.x0 + key.x1 - tw) / 2f, y + ROW_H / 2f + 13, text);
            }
        }
    }
}
