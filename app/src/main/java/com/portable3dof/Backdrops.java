package com.portable3dof;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The scene backgrounds (world: Z up, the screen at +Y).
 *
 * Gradients are computed per pixel from the view direction (not per vertex: Gouraud-shaded triangles showed as
 * bands) and dithered by about one 8-bit step, because dark gradients only have a handful of 8-bit levels and the
 * glasses show every step. Everything is still: moving surroundings in a head-tracked display make people queasy.
 */
final class Backdrops {
    private Backdrops() { }

    static final int NIGHT = 0, BLACK = 1, DUSK = 2, GRID = 3, PANORAMA = 4;
    static final String[] NAMES = {"Night sky", "Pure black", "Dusk", "Grid room", "360° photo"};

    /** Fragment precision + interleaved-gradient-noise dither (Jimenez), shared by the background shaders. */
    static final String FS_HEAD = "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n"
            + "float ign(){ return fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))); }\n"
            + "float dith(){ return (ign() - 0.5) * (1.5 / 255.0); }\n";

    static final String VS_DOME = "uniform mat4 uMvp; attribute vec3 aPos; varying vec3 vDir;"
            + "void main(){ gl_Position = uMvp * vec4(aPos, 1.0); vDir = aPos; }";

    /** Sky gradient by elevation, an optional glow towards uGlowDir, and a darker calm patch around the screen. */
    static final String FS_SKY = FS_HEAD
            + "varying vec3 vDir; uniform vec3 uZen, uMid, uHor, uGnd, uGlowDir, uGlowCol, uTint;"
            + "uniform float uMidAt, uGlowPow, uCalm;"
            + "void main(){ vec3 d = normalize(vDir); float z = d.z; vec3 c;"
            + " if (z >= 0.0) { if (z < uMidAt) c = mix(uHor, uMid, smoothstep(0.0, 1.0, z / uMidAt));"
            + "   else c = mix(uMid, uZen, smoothstep(0.0, 1.0, (z - uMidAt) / (1.0 - uMidAt))); }"
            + " else c = mix(uHor, uGnd, smoothstep(0.0, 1.0, clamp(-z * 5.0, 0.0, 1.0)));"
            + " c += uGlowCol * pow(max(dot(d, uGlowDir), 0.0), uGlowPow);"
            + " float s = max(d.y, 0.0) * max(1.0 - abs(z) * 2.0, 0.0); c *= 1.0 - uCalm * s * s * s;"
            + " gl_FragColor = vec4(c * uTint + dith(), 1.0); }";

    /** An equirectangular photo around the viewer; its centre is straight ahead, turned by uTurn (0..1). */
    static final String FS_PANO = FS_HEAD
            + "varying vec3 vDir; uniform sampler2D uPano; uniform float uTurn; uniform vec3 uTint;"
            + "void main(){ vec3 d = normalize(vDir);"
            + " float u = fract(atan(d.x, d.y) / 6.2831853 + 0.5 + uTurn);"
            + " float v = acos(clamp(d.z, -1.0, 1.0)) / 3.14159265;"
            + " gl_FragColor = vec4(texture2D(uPano, vec2(u, v)).rgb * uTint + dith(), 1.0); }";

    /** Gradient colours for FS_SKY: zenith, mid, horizon, ground, glow dir, glow colour; then midAt, glowPow, calm. */
    static float[][] skyColours(int kind) {
        if (kind == DUSK) {
            float gx = -0.80f, gy = -0.20f, gz = 0.05f, gl = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
            return new float[][]{{0.035f, 0.035f, 0.10f}, {0.20f, 0.10f, 0.20f}, {0.48f, 0.24f, 0.16f},
                    {0.035f, 0.030f, 0.045f}, {gx / gl, gy / gl, gz / gl}, {0.45f, 0.25f, 0.11f}, {0.25f, 6f, 0.55f}};
        }
        // night: deep blue overhead, a faint lighter band at the horizon, near-black ground
        return new float[][]{{0.010f, 0.016f, 0.045f}, {0.028f, 0.038f, 0.080f}, {0.050f, 0.065f, 0.115f},
                {0.010f, 0.011f, 0.016f}, {0f, 1f, 0f}, {0f, 0f, 0f}, {0.15f, 1f, 0f}};
    }

    // ---- geometry -------------------------------------------------------------------------------

    private static final float R = 40f;            // dome radius (the screen is at 5)
    private static final int LAT = 24, LON = 48;

    /** A sphere around the viewer, positions only (x, y, z per vertex), triangles. */
    static FloatBuffer dome() {
        FloatBuffer fb = buf(LAT * LON * 6 * 3);
        for (int i = 0; i < LAT; i++) {
            double e0 = -Math.PI / 2 + Math.PI * i / LAT, e1 = -Math.PI / 2 + Math.PI * (i + 1) / LAT;
            for (int j = 0; j < LON; j++) {
                double a0 = 2 * Math.PI * j / LON, a1 = 2 * Math.PI * (j + 1) / LON;
                double[][] q = {{e0, a0}, {e0, a1}, {e1, a1}, {e0, a0}, {e1, a1}, {e1, a0}};
                for (double[] v : q) {
                    fb.put((float) (Math.cos(v[0]) * Math.sin(v[1])) * R)
                            .put((float) (Math.cos(v[0]) * Math.cos(v[1])) * R)
                            .put((float) Math.sin(v[0]) * R);
                }
            }
        }
        fb.position(0);
        return fb;
    }

    static int domeVertices() { return LAT * LON * 6; }

    /** Still stars for the night sky (and a few for dusk), as points. bright = the larger, rarer ones. */
    static FloatBuffer stars(int kind, boolean bright, int[] countOut) {
        Random rnd = new Random(bright ? 11 : 7);
        int n = kind == DUSK ? (bright ? 12 : 120) : (bright ? 70 : 1100);
        FloatBuffer fb = buf(n * 7);
        int made = 0;
        while (made < n) {
            float z = rnd.nextFloat() * 2f - 1f;
            float minZ = kind == DUSK ? 0.45f : -0.05f;
            if (z < minZ) continue;
            double a = rnd.nextDouble() * Math.PI * 2;
            float rr = (float) Math.sqrt(1 - z * z);
            float x = rr * (float) Math.sin(a), y = rr * (float) Math.cos(a);
            float b = bright ? 0.65f + rnd.nextFloat() * 0.35f : 0.15f + (float) Math.pow(rnd.nextFloat(), 3) * 0.6f;
            b *= Math.min(1f, (z + 0.05f) * 6f);    // fade into the horizon haze
            float warm = rnd.nextFloat();
            float r = R * 0.98f;
            fb.put(x * r).put(y * r).put(z * r).put(b * (0.85f + 0.15f * warm)).put(b * 0.92f)
                    .put(b * (1.0f - 0.15f * warm)).put(1f);
            made++;
        }
        fb.position(0);
        countOut[0] = n;
        return fb;
    }

    // ---- 360 photos -----------------------------------------------------------------------------

    /** Where panoramas are looked for (first is the documented one). */
    static File[] panoramaDirs() {
        File root = Environment.getExternalStorageDirectory();
        return new File[]{new File(root, "Pictures/Panoramas"), new File(root, "Download")};
    }

    /** Equirectangular images (about 2:1) in the panorama folders, by name. Reads only image headers. */
    static List<File> findPanoramas() {
        List<File> out = new ArrayList<>();
        for (File dir : panoramaDirs()) {
            File[] fs = dir.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (!(n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))) continue;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                if (o.outWidth <= 0 || o.outHeight <= 0) continue;
                float a = o.outWidth / (float) o.outHeight;
                if (a > 1.85f && a < 2.15f) out.add(f);
            }
        }
        Collections.sort(out, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    /** Decode a panorama no wider than maxW (memory: 4096 x 2048 is 32 MB as a texture). Null on failure. */
    static Bitmap decodePanorama(String path, int maxW) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0) return null;
            int s = 1;
            while (o.outWidth / (s * 2) >= maxW) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap b = BitmapFactory.decodeFile(path, o);
            if (b == null) return null;
            if (b.getWidth() > maxW) {
                Bitmap sc = Bitmap.createScaledBitmap(b, maxW, maxW / 2, true);
                if (sc != b) b.recycle();
                b = sc;
            }
            return b;
        } catch (OutOfMemoryError | RuntimeException e) {
            return null;
        }
    }

    private static FloatBuffer buf(int floats) {
        return ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }
}
