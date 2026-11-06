package com.portable3dof;

/**
 * Orientation filter for head tracking.
 *
 * 1) Adaptive low-pass (the "1 euro" idea): the cutoff frequency rises with angular speed, so the
 *    view is heavily smoothed while your head is still (kills sensor jitter) and almost unfiltered
 *    while it moves (no added lag).
 * 2) Prediction: the filtered angular velocity is extrapolated forward by a few milliseconds to
 *    cancel the pose -> render -> display latency.
 *
 * Quaternions are (w, x, y, z), body -> world, same convention as the matrices built from them.
 * No allocation per call (called at 120 Hz).
 */
public final class PoseFilter {
    // cutoff = FC_MIN + BETA * speed(rad/s); level 0 = bypass
    private static final float[] FC_MIN = {0f, 3.0f, 1.5f, 0.8f};
    private static final float[] BETA = {0f, 40f, 25f, 12f};

    private float fw = 1, fx, fy, fz;          // filtered orientation
    private float rw = 1, rx, ry, rz;          // previous raw orientation
    private float speed;                       // filtered angular speed (rad/s)
    private float ox, oy, oz;                  // filtered body-frame angular velocity (rad/s)
    private long lastNs;
    private boolean init;

    public void reset() { init = false; }

    private static float alpha(float dt, float cutoffHz) {
        float tau = 1f / (2f * (float) Math.PI * cutoffHz);
        return dt / (dt + tau);
    }

    /** Feed a raw pose; out[0..3] receives the filtered + predicted quaternion (w, x, y, z). */
    public void update(float w, float x, float y, float z, long nowNs, float[] out) {
        int level = Settings.smoothing;
        float predict = Settings.predictMs * 1e-3f;

        if (!init) {
            fw = w; fx = x; fy = y; fz = z;
            rw = w; rx = x; ry = y; rz = z;
            speed = 0; ox = oy = oz = 0;
            lastNs = nowNs;
            init = true;
            out[0] = w; out[1] = x; out[2] = y; out[3] = z;
            return;
        }
        float dt = (nowNs - lastNs) * 1e-9f;
        lastNs = nowNs;
        if (dt < 0.001f) dt = 0.001f;
        if (dt > 0.1f) dt = 0.1f;

        // keep the raw quaternion in the same hemisphere as the previous one
        if (w * rw + x * rx + y * ry + z * rz < 0) { w = -w; x = -x; y = -y; z = -z; }

        // raw angular speed from the rotation between consecutive raw samples: conj(r) * q
        float dw = rw * w + rx * x + ry * y + rz * z;
        float dx = rw * x - rx * w - ry * z + rz * y;
        float dy = rw * y + rx * z - ry * w - rz * x;
        float dz = rw * z - rx * y + ry * x - rz * w;
        float vlen = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float angle = 2f * (float) Math.atan2(vlen, Math.abs(dw));
        speed += alpha(dt, 1.0f) * (angle / dt - speed);
        rw = w; rx = x; ry = y; rz = z;

        // adaptive low-pass on the orientation (normalised lerp, fine for the tiny steps involved)
        float a = level <= 0 ? 1f : alpha(dt, FC_MIN[level] + BETA[level] * speed);
        float pw = fw, px = fx, py = fy, pz = fz;
        float nw = pw + a * (w - pw), nx = px + a * (x - px), ny = py + a * (y - py), nz = pz + a * (z - pz);
        float inv = 1f / (float) Math.sqrt(nw * nw + nx * nx + ny * ny + nz * nz);
        fw = nw * inv; fx = nx * inv; fy = ny * inv; fz = nz * inv;

        // body-frame angular velocity of the filtered orientation: 2 * vec(conj(prev) * new) / dt
        float ew = pw * fw + px * fx + py * fy + pz * fz;
        float exx = pw * fx - px * fw - py * fz + pz * fy;
        float eyy = pw * fy + px * fz - py * fw - pz * fx;
        float ezz = pw * fz - px * fy + py * fx - pz * fw;
        float sgn = ew < 0 ? -1f : 1f;
        float k = 2f * sgn / dt;
        float av = alpha(dt, 8f);
        ox += av * (exx * k - ox);
        oy += av * (eyy * k - oy);
        oz += av * (ezz * k - oz);

        if (predict <= 0f) {
            out[0] = fw; out[1] = fx; out[2] = fy; out[3] = fz;
            return;
        }
        // q_pred = q_f * exp(0.5 * omega * T)   (body-frame right multiplication)
        float hx = 0.5f * ox * predict, hy = 0.5f * oy * predict, hz = 0.5f * oz * predict;
        float qw = 1f, qx = hx, qy = hy, qz = hz;
        float qn = 1f / (float) Math.sqrt(1f + hx * hx + hy * hy + hz * hz);
        qw *= qn; qx *= qn; qy *= qn; qz *= qn;
        out[0] = fw * qw - fx * qx - fy * qy - fz * qz;
        out[1] = fw * qx + fx * qw + fy * qz - fz * qy;
        out[2] = fw * qy - fx * qz + fy * qw + fz * qx;
        out[3] = fw * qz + fx * qy - fy * qx + fz * qw;
    }
}
