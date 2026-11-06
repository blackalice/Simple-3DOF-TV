package com.portable3dof;

import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.KeyEvent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The licence agreement (shown once, on first launch, with Accept / Exit) and the "About and legal" pages
 * (Settings): EULA, privacy policy, third-party notices and VITURE's own licence text. The documents are plain text
 * or light Markdown read from the APK's assets (see legal/ in the repository; VITURE's two files are copied from the
 * SDK at build time), wrapped to the screen and scrolled with the D-pad.
 */
final class LegalUi {
    /** Bump when the EULA changes in a way people must accept again. */
    static final int EULA_VERSION = 1;

    enum Result { NONE, CLOSE, ACCEPT, EXIT }

    private static final class Doc {
        final String title, asset;
        final boolean markdown;
        Doc(String title, String asset, boolean markdown) { this.title = title; this.asset = asset; this.markdown = markdown; }
    }

    private static final Doc[] DOCS = {
            new Doc("End user licence agreement", "EULA.md", true),
            new Doc("Privacy policy", "PRIVACY.md", true),
            new Doc("Third-party notices", "THIRD_PARTY_NOTICES.md", true),
            new Doc("VITURE licence", "viture_LICENSE.txt", false),
            new Doc("VITURE open-source notice", "viture_NOTICE.txt", false),
            new Doc("Apache License 2.0", "licenses/Apache-2.0.txt", false),
            new Doc("GNU LGPL 2.1 (FFmpeg)", "licenses/LGPL-2.1.txt", false),
    };

    private static final int X0 = 120, TEXT_W = 1560, TOP = 170, BOTTOM = 985;
    private static final int BODY = 0, H1 = 1, H2 = 2, BULLET = 3, GAP = 4, CONT = 5;   // CONT: wrapped bullet line

    private static final class Line {
        final String s;
        final int style;
        Line(String s, int style) { this.s = s; this.style = style; }
    }

    private final AssetManager assets;
    private final TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint h1 = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint h2 = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final List<Line>[] lines;
    private final int[] scroll = new int[DOCS.length];
    private boolean firstRun, reading;
    private int doc, listSel;

    @SuppressWarnings("unchecked")
    LegalUi(AssetManager assets) {
        this.assets = assets;
        body.setTypeface(Typeface.SANS_SERIF);
        body.setTextSize(32);
        body.setColor(Ui.TEXT);
        h1.setTypeface(Typeface.SANS_SERIF);
        h1.setFakeBoldText(true);
        h1.setTextSize(48);
        h1.setColor(Ui.TEXT);
        h2.setTypeface(Typeface.SANS_SERIF);
        h2.setFakeBoldText(true);
        h2.setTextSize(36);
        h2.setColor(Ui.ACCENT);
        small.setTypeface(Typeface.SANS_SERIF);
        small.setTextSize(30);
        small.setColor(Ui.TEXT_DIM);
        lines = new List[DOCS.length];
    }

    /** First launch: the EULA, with Accept / Exit. Otherwise the list of documents. */
    void open(boolean firstRun) {
        this.firstRun = firstRun;
        reading = firstRun;
        doc = 0;
        listSel = 0;
        for (int i = 0; i < scroll.length; i++) scroll[i] = 0;
    }

    int hash() {
        return (firstRun ? 1 : 0) + (reading ? 2 : 0) + doc * 5 + listSel * 31 + scroll[doc] * 131;
    }

    // ---- keys -------------------------------------------------------------------------------

    Result onKey(int k) {
        boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
        boolean back = k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE;
        if (!reading) {                         // the list of documents
            if (back) return Result.CLOSE;
            if (ok) { reading = true; doc = listSel; return Result.NONE; }
            if (k == KeyEvent.KEYCODE_DPAD_UP) listSel = Math.max(0, listSel - 1);
            else if (k == KeyEvent.KEYCODE_DPAD_DOWN) listSel = Math.min(DOCS.length - 1, listSel + 1);
            return Result.NONE;
        }
        if (firstRun) {
            if (ok) return Result.ACCEPT;
            if (back) return Result.EXIT;
        } else if (back || ok) {
            reading = false;
            return Result.NONE;
        }
        int page = Math.max(3, visibleLines() - 2);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: scrollBy(-3); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: scrollBy(3); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_PAGE_UP: scrollBy(-page); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_PAGE_DOWN: scrollBy(page); break;
            default: break;
        }
        return Result.NONE;
    }

    private void scrollBy(int d) {
        int max = Math.max(0, lines(doc).size() - visibleLines());
        scroll[doc] = Math.max(0, Math.min(max, scroll[doc] + d));
    }

    // ---- text -------------------------------------------------------------------------------

    private List<Line> lines(int i) {
        if (lines[i] == null) lines[i] = wrap(read(DOCS[i].asset), DOCS[i].markdown);
        return lines[i];
    }

    private String read(String asset) {
        try (InputStream in = assets.open(asset)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "This document is not available in this build of the app.";
        }
    }

    private List<Line> wrap(String raw, boolean md) {
        List<Line> out = new ArrayList<>();
        for (String para : raw.replace("\r", "").split("\n", -1)) {
            String t = para;
            int style = BODY;
            TextPaint p = body;
            float width = TEXT_W;
            if (md) {
                t = t.replace("**", "");
                if (t.startsWith("# ")) { t = t.substring(2); style = H1; p = h1; }
                else if (t.startsWith("## ")) { t = t.substring(3); style = H2; p = h2; out.add(new Line("", GAP)); }
                else if (t.startsWith("- ")) { t = t.substring(2); style = BULLET; width = TEXT_W - 50; }
            }
            if (t.trim().isEmpty()) { out.add(new Line("", GAP)); continue; }
            wrapParagraph(out, t, p, width, style);
        }
        return out;
    }

    private static void wrapParagraph(List<Line> out, String t, TextPaint p, float width, int style) {
        StringBuilder cur = new StringBuilder();
        boolean first = true;
        for (String w : t.split(" ", -1)) {
            String cand = cur.length() == 0 ? w : cur + " " + w;
            if (p.measureText(cand) <= width) { cur.setLength(0); cur.append(cand); continue; }
            if (cur.length() > 0) { out.add(new Line(cur.toString(), first || style != BULLET ? style : CONT)); first = false; cur.setLength(0); }
            while (p.measureText(w) > width) {      // a long unbroken word (a URL)
                int n = p.breakText(w, true, width, null);
                if (n <= 0) break;
                out.add(new Line(w.substring(0, n), first || style != BULLET ? style : CONT));
                first = false;
                w = w.substring(n);
            }
            cur.append(w);
        }
        if (cur.length() > 0) out.add(new Line(cur.toString(), first || style != BULLET ? style : CONT));
    }

    private static int height(Line l) {
        switch (l.style) {
            case H1: return 66;
            case H2: return 52;
            case GAP: return 22;
            default: return 45;
        }
    }

    private int visibleLines() {
        List<Line> l = lines(doc);
        int h = 0, n = 0;
        for (int i = scroll[doc]; i < l.size(); i++) {
            h += height(l.get(i));
            if (h > BOTTOM - TOP) break;
            n++;
        }
        return Math.max(1, n);
    }

    // ---- painting ---------------------------------------------------------------------------

    void paint(Canvas c) {
        Ui.page(c);
        if (!reading) { paintList(c); return; }
        Doc d = DOCS[doc];
        List<Line> l = lines(doc);

        h1.setTextSize(48);
        c.drawText(firstRun ? "Welcome to Simple 3DOF TV" : d.title, X0, 105, h1);
        if (firstRun) {
            c.drawText("Please read and accept this agreement to continue.", X0, 150, small);
        }
        float y = TOP;
        for (int i = scroll[doc]; i < l.size(); i++) {
            Line ln = l.get(i);
            int h = height(ln);
            if (y + h > BOTTOM + 2) break;
            switch (ln.style) {
                case H1: c.drawText(ln.s, X0, y + 46, h1); break;
                case H2: c.drawText(ln.s, X0, y + 40, h2); break;
                case BULLET:
                    c.drawText("•", X0 + 8, y + 33, body);
                    c.drawText(ln.s, X0 + 50, y + 33, body);
                    break;
                case GAP: break;
                default:
                    c.drawText(ln.s, ln.style == CONT ? X0 + 50 : X0, y + 33, body);
                    break;
            }
            y += h;
        }
        int total = l.size(), vis = visibleLines();
        if (total > vis) {                      // scrollbar
            float trackH = BOTTOM - TOP;
            float thumbH = Math.max(40, trackH * vis / total);
            float ty = TOP + (trackH - thumbH) * scroll[doc] / (total - vis);
            fill.setColor(Color.argb(90, 255, 255, 255));
            rect.set(1790, ty, 1802, ty + thumbH);
            c.drawRoundRect(rect, 6, 6, fill);
        }
        if (firstRun) {
            Ui.hints(c, X0, 1030, Ui.K_OK, "Accept", Ui.K_BACK, "Exit", Ui.K_UPDOWN, "Scroll", Ui.K_LR, "Page");
        } else {
            Ui.hints(c, X0, 1030, Ui.K_BACK, "Back to list", Ui.K_UPDOWN, "Scroll", Ui.K_LR, "Page");
        }
    }

    private void paintList(Canvas c) {
        h1.setTextSize(52);
        c.drawText("About and legal", X0, 115, h1);
        c.drawText("Simple 3DOF TV " + BuildConfig.VERSION_NAME + (BuildConfig.YOUTUBE ? "  ·  YouTube edition" : "  ·  core edition"), X0, 165, small);
        float y = 230;
        for (int i = 0; i < DOCS.length; i++) {
            boolean s = i == listSel;
            if (s) {
                fill.setColor(Ui.ROW_SEL);
                rect.set(X0 - 20, y, 1800, y + 86);
                c.drawRoundRect(rect, 16, 16, fill);
            }
            body.setTextSize(38);
            body.setColor(s ? Ui.TEXT : Color.rgb(190, 200, 212));
            c.drawText(DOCS[i].title, X0 + 10, y + 56, body);
            if (s) c.drawText("›", 1760, y + 56, body);
            y += 100;
        }
        body.setTextSize(32);
        body.setColor(Ui.TEXT);
        small.setTextSize(30);
        c.drawText("Not affiliated with or endorsed by VITURE, Google or YouTube.", X0, y + 50, small);
        Ui.hints(c, X0, 1030, Ui.K_OK, "Read", Ui.K_UPDOWN, "Choose", Ui.K_BACK, "Back");
    }
}
