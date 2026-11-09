package com.portable3dof;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Environment;
import android.view.Display;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.Locale;

/**
 * Simple 3DOF TV cinema: a head-tracked floating screen with a media player.
 *
 * Head tracking comes only from the Viture glasses' own IMU (through Viture's SDK). Without glasses the screen
 * stays fixed straight ahead. The Pocket TV's own gyro + accelerometer only drive the browser's air mouse.
 * Playback is ExoPlayer; the UI is a Canvas-painted overlay on the screen driven by remote keys (see {@link PlayerUi}).
 */
public class MainActivity extends Activity implements SensorEventListener, PoseListener, PlayerUi.Host,
        DisplayManager.DisplayListener {

    private SensorManager sensorManager;
    private Sensor gyro, accel;     // Pocket TV sensors, fused (Mahony) for the air mouse only
    private float qw = 1, qx = 0, qy = 0, qz = 0;
    private long lastGyroNs = 0;
    private static final float KP = 2.0f;   // accel correction gain

    private GLSurfaceView glView;
    private SceneRenderer renderer;
    private GlassesProbe probe;
    private ResumeStore resume;
    private Library library;
    private MediaEngine engine;
    private Overlay overlay;
    private PlayerUi playerUi;
    private BrowserScreen browserScreen;
    private BrowserController browserController;
    private YouTubeUi youtubeUi;
    private boolean okLongHandled = false;
    private final PoseFilter poseFilter = new PoseFilter();
    private final float[] filtered = new float[4];
    private BroadcastReceiver storageReceiver;

    private static final String SAMPLE_STREAM =
            "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_5MB.mp4";
    private static final int REQ_VIDEO = 7;

    private final float[] rotMapped = new float[16];
    private volatile boolean recenterRequested = true;
    private float yawOffsetDeg = 0f;

    // sample-rate measurement
    private int sampleCount = 0;
    private long rateWindowStart = System.nanoTime();
    private volatile float sampleHz = 0f;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED);
        if (gyro == null) gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED);
        if (accel == null) accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        resume = new ResumeStore(this);
        library = new Library(this, resume);
        library.addStream("Test video: Big Buck Bunny", SAMPLE_STREAM);
        engine = new MediaEngine(this, resume);
        overlay = new Overlay();

        renderer = new SceneRenderer();
        renderer.setMedia(engine);
        renderer.setOverlay(overlay);
        browserScreen = new BrowserScreen(this);
        renderer.setBrowserHost(browserScreen);
        browserController = new BrowserController(this, browserScreen, renderer);
        pointerListener = browserController;
        Settings.load(this, renderer);
        engine.setVolume(Settings.volume);
        if (BuildConfig.DEBUG) applyDebugExtras();

        playerUi = new PlayerUi(overlay, engine, library, renderer, this);
        playerUi.setBrowser(browserController);
        youtubeUi = new YouTubeUi(this, playerUi);
        playerUi.setYoutube(youtubeUi);
        // The licence agreement appears on first launch. Debug builds skip it unless asked (--ez eula true).
        playerUi.setLegal(new LegalUi(getAssets()), BuildConfig.DEBUG ? getIntent().getBooleanExtra("eula", false) : false);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setEGLConfigChooser(8, 8, 8, 8, 24, 0);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        setContentView(glView);

        handleLaunchIntent();
    }

    /**
     * Developer switches, read from the launch intent in debug builds only (release builds ignore every extra here):
     * <pre>
     *   --es exp perf,noaudio,nopcm,nochunk,nofast   diagnostics / A-B switches (see DEVELOPMENT.md)
     *   --ei ytq 0..5                                YouTube quality for this launch (not saved)
     *   --ef fov 30                                  field of view
     *   --ez debug true                              screen markers + crosshair
     *   --ez eula true                               show the first-launch licence agreement
     *   --es url <path | uri>   --es yt <id | url>   --es browse <url>   start playing / browsing straight away
     * </pre>
     */
    private void applyDebugExtras() {
        String expx = getIntent().getStringExtra("exp");
        MediaEngine.exp = expx == null ? "" : expx;
        int ytq = getIntent().getIntExtra("ytq", -1);
        if (ytq >= 0 && ytq <= 5) Settings.ytQuality = ytq;
        float fovExtra = getIntent().getFloatExtra("fov", 0f);
        if (fovExtra > 0) renderer.setFov(fovExtra);
        if (getIntent().getBooleanExtra("debug", false)) {
            renderer.setShowMarkers(true);
            renderer.setShowCrosshair(true);
        }
    }

    private void handleLaunchIntent() {
        if (!BuildConfig.DEBUG) return;
        String yt = getIntent().getStringExtra("yt");
        if (BuildConfig.YOUTUBE && yt != null && !yt.isEmpty()) {
            playerUi.playYoutube(yt.startsWith("http") ? yt : "https://www.youtube.com/watch?v=" + yt);
            return;
        }
        String browse = getIntent().getStringExtra("browse");
        if (browse != null && !browse.isEmpty()) { playerUi.openBrowser(browse); return; }
        String url = getIntent().getStringExtra("url");
        if (url != null && !url.isEmpty()) {
            Uri u = url.startsWith("/") ? Uri.fromFile(new java.io.File(url)) : Uri.parse(url);
            String name = u.getLastPathSegment() != null ? u.getLastPathSegment() : url;
            playerUi.playNow(u, name);
        }
    }

    private void goImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    protected void onResume() {
        super.onResume();
        goImmersive();
        glView.onResume();
        recenterRequested = true;
        // Air mouse only. 5000us = 200 Hz requested; the platform clamps to what the IMU supports.
        if (gyro != null && accel != null) {
            lastGyroNs = 0;
            qw = 1; qx = 0; qy = 0; qz = 0;
            sensorManager.registerListener(this, gyro, 5000);
            sensorManager.registerListener(this, accel, 5000);
        }
        poseFilter.reset();
        // The system USB permission dialog can pause this activity. Keep its receiver alive
        // across that pause and do not request permission again when the activity resumes.
        if (probe == null) {
            probe = new GlassesProbe(this, this);
            probe.start();
        } else {
            probe.checkForGlasses();
        }
        ((DisplayManager) getSystemService(Context.DISPLAY_SERVICE)).registerDisplayListener(this, null);
        applyDisplaySettings();
        registerStorageReceiver();
        playerUi.start();
        playerUi.resumeApp();
        scanLibrary();
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        ((DisplayManager) getSystemService(Context.DISPLAY_SERVICE)).unregisterDisplayListener(this);
        if (storageReceiver != null) { unregisterReceiver(storageReceiver); storageReceiver = null; }
        playerUi.stop();
        playerUi.pauseApp();           // remembers what was playing, saves the position, frees the decoder
        glView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (probe != null) { probe.stop(); probe = null; }
        browserController.close();
        engine.close();
    }

    private void scanLibrary() {
        new Thread(new Runnable() {
            @Override public void run() {
                library.scan();
                runOnUiThread(new Runnable() {
                    @Override public void run() { playerUi.rescan(); }
                });
            }
        }, "library-scan").start();
    }

    // ---- PlayerUi.Host ----------------------------------------------------------------------

    @Override
    public void recenter() {
        if (probe != null && probe.isNativeTracking()) {
            renderer.clearOrientation();
            probe.recenterNative();
        } else recenterRequested = true;
    }

    @Override
    public void requestAllFilesAccess() {
        if (android.os.Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException e) {
                // No such settings screen: grant it from a PC with
                //   adb shell appops set com.portable3dof MANAGE_EXTERNAL_STORAGE allow
            }
        }
    }

    @Override
    public void saveSettings() {
        Settings.volume = engine.getVolume();
        Settings.save(this, renderer);
    }

    /** Ask for the 120 Hz display mode (or 60 Hz) at the current resolution. 120 is smoother for head tracking. */
    @Override
    public void applyDisplaySettings() {
        Display d = getWindowManager().getDefaultDisplay();
        Display.Mode cur = d.getMode(), best = cur;
        float target = Settings.hz120 ? 120f : 60f;
        for (Display.Mode m : d.getSupportedModes()) {
            if (m.getPhysicalWidth() != cur.getPhysicalWidth() || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
            if (Math.abs(m.getRefreshRate() - target) < Math.abs(best.getRefreshRate() - target)) best = m;
        }
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (lp.preferredDisplayModeId != best.getModeId()) {
            lp.preferredDisplayModeId = best.getModeId();
            getWindow().setAttributes(lp);
        }
    }

    @Override public void onDisplayAdded(int id) { }
    @Override public void onDisplayRemoved(int id) { }
    @Override public void onDisplayChanged(int id) { if (id == Display.DEFAULT_DISPLAY) applyDisplaySettings(); }

    /** USB drive plugged / removed: refresh the storage list. */
    private void registerStorageReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addAction(Intent.ACTION_MEDIA_EJECT);
        f.addAction(Intent.ACTION_MEDIA_REMOVED);
        f.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        f.addDataScheme("file");
        storageReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { scanLibrary(); }
        };
        registerReceiver(storageReceiver, f);
    }

    @Override
    public void requestVideoPermission() {
        requestPermissions(new String[]{Library.readPermission()}, REQ_VIDEO);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_VIDEO) scanLibrary();
    }

    @Override
    public String trackingStatus() {
        if (probe != null && probe.isStreaming()) {
            if (probe.isNativeTracking()) return "Head tracking: VITURE native 3DOF";
            return String.format(Locale.US, "Head tracking: VITURE glasses  \u00b7  %.0f Hz", probe.getRateHz());
        }
        String g = probe != null ? probe.statusLine() : "";
        return "No head tracking" + (g.isEmpty() ? "" : "  (glasses: " + g + ")");
    }

    @Override
    public boolean glassesConnected() { return glassesStreaming(); }

    /** Glasses stopped streaming: put the screen straight ahead again (no other source moves the view). */
    @Override
    public void glassesLost() {
        renderer.clearOrientation();
        refValid = false;
        recenterRequested = true;
    }

    private int batteryPct = -1;
    private long batteryAt = 0;

    /** Cached: the header asks on every repaint check. */
    @Override
    public int batteryPercent() {
        long now = System.currentTimeMillis();
        if (now - batteryAt > 30000) {
            batteryAt = now;
            android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            int v = bm == null ? -1 : bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            batteryPct = v >= 0 && v <= 100 ? v : -1;
        }
        return batteryPct;
    }

    @Override
    public void exitApp() { finish(); }

    // ---- head tracking: Pocket TV sensors (fallback) ----------------------------------------

    // Latest accel (device frame), used by the fusion filter
    private float ax, ay, az;
    private boolean haveAccel = false;

    @Override
    public void onSensorChanged(SensorEvent e) {
        int t = e.sensor.getType();
        if (t == Sensor.TYPE_ACCELEROMETER || t == Sensor.TYPE_ACCELEROMETER_UNCALIBRATED) {
            ax = e.values[0]; ay = e.values[1]; az = e.values[2];
            haveAccel = true;
        } else if (t == Sensor.TYPE_GYROSCOPE || t == Sensor.TYPE_GYROSCOPE_UNCALIBRATED) {
            fuseGyro(e);
        }
    }

    // Mahony complementary filter: integrate gyro, nudge "up" towards the accelerometer.
    private void fuseGyro(SensorEvent e) {
        float gx = e.values[0], gy = e.values[1], gz = e.values[2];
        if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE_UNCALIBRATED && e.values.length >= 6) {
            gx -= e.values[3]; gy -= e.values[4]; gz -= e.values[5];
        }
        final float rgx = gx, rgy = gy, rgz = gz;      // un-corrected rates, for the gyro pointer
        // This device's HAL reports a constant event timestamp, so time samples on arrival instead.
        long nowNs = System.nanoTime();
        if (lastGyroNs == 0) { lastGyroNs = nowNs; return; }
        float dt = (nowNs - lastGyroNs) * 1e-9f;
        lastGyroNs = nowNs;
        if (dt <= 0 || dt > 0.1f) return;

        // gravity direction predicted in device frame = third row of R = R^T * (0,0,1)
        float vx = 2 * (qx * qz - qw * qy);
        float vy = 2 * (qw * qx + qy * qz);
        float vz = qw * qw - qx * qx - qy * qy + qz * qz;
        if (haveAccel) {
            float n = (float) Math.sqrt(ax * ax + ay * ay + az * az);
            if (n > 1e-3f) {
                float nx = ax / n, ny = ay / n, nz = az / n;
                // error = measured x predicted
                gx += KP * (ny * vz - nz * vy);
                gy += KP * (nz * vx - nx * vz);
                gz += KP * (nx * vy - ny * vx);
            }
        }
        // q = q * exp(w*dt/2)
        float hx = gx * dt * 0.5f, hy = gy * dt * 0.5f, hz = gz * dt * 0.5f;
        float nw = qw - qx * hx - qy * hy - qz * hz;
        float nx2 = qx + qw * hx + qy * hz - qz * hy;
        float ny2 = qy + qw * hy - qx * hz + qz * hx;
        float nz2 = qz + qw * hz + qx * hy - qy * hx;
        float inv = (float) (1.0 / Math.sqrt(nw * nw + nx2 * nx2 + ny2 * ny2 + nz2 * nz2));
        qw = nw * inv; qx = nx2 * inv; qy = ny2 * inv; qz = nz2 * inv;
        // The Pocket TV's orientation is never used as the head pose: only the glasses move the view.
        if (Settings.gyroPointer && pointerListener != null) pointerFromGyro(rgx, rgy, rgz, dt);
    }

    private boolean glassesStreaming() { return probe != null && probe.isStreaming(); }

    // ---- gyro pointer: the Pocket TV in your hand as an air mouse ---------------------------

    private PointerListener pointerListener;
    private static final float[][] POINTER_AXES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /**
     * Cursor motion from the device's rotation, whatever way it is gripped: yaw is the rotation rate about the
     * world vertical; pitch is the rate about the horizontal axis perpendicular to the pointing axis
     * (Settings.pointerAxis = which device axis points at the screen).
     */
    private void pointerFromGyro(float gx, float gy, float gz, float dt) {
        float r00 = 1 - 2 * (qy * qy + qz * qz), r01 = 2 * (qx * qy - qw * qz), r02 = 2 * (qx * qz + qw * qy);
        float r10 = 2 * (qx * qy + qw * qz), r11 = 1 - 2 * (qx * qx + qz * qz), r12 = 2 * (qy * qz - qw * qx);
        float r20 = 2 * (qx * qz - qw * qy), r21 = 2 * (qy * qz + qw * qx), r22 = 1 - 2 * (qx * qx + qy * qy);
        float wx = r00 * gx + r01 * gy + r02 * gz, wy = r10 * gx + r11 * gy + r12 * gz, wz = r20 * gx + r21 * gy + r22 * gz;
        float[] a = POINTER_AXES[Settings.pointerAxis];
        float px = r00 * a[0] + r01 * a[1] + r02 * a[2], py = r10 * a[0] + r11 * a[1] + r12 * a[2];
        float hl = (float) Math.sqrt(px * px + py * py);
        float pitchRate = 0;
        if (hl > 0.3f) pitchRate = wx * (py / hl) + wy * (-px / hl);   // about right = pointing x up
        float dead = 0.03f;
        float yaw = Math.abs(wz) < dead ? 0 : wz, pitch = Math.abs(pitchRate) < dead ? 0 : pitchRate;
        if (yaw == 0 && pitch == 0) return;
        float gain = Settings.pointerSens;
        pointerListener.onPointerDelta(-yaw * dt * gain, -pitch * dt * gain);
    }

    // ---- head tracking: glasses pose ---------------------------------------------------------

    private final float[] rotRef = new float[9];
    private final float[] rotK1 = new float[9];
    private final float[] rotK2 = new float[9];
    private boolean refValid = false;
    // scratch for recentreAndPublish (called ~120x/s: no per-call allocation)
    private final float[] sCur = new float[9], sRzn = new float[9], sRt = new float[9];
    private final float[] sT1 = new float[9], sT2 = new float[9], sM = new float[9];
    private static final float[] S_M0 = {1, 0, 0, 0, 0, -1, 0, 1, 0};

    /** out = a * b for 3x3 row-major matrices (out must not alias a or b). */
    private static void mul3(float[] a, float[] b, float[] out) {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                out[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
            }
        }
    }

    /**
     * Glasses pose quaternion -> scene rotation, then render. USB reader thread.
     *
     * Measured on the Pro 2: the quaternion's X component tracks the SDK "pitch", Y tracks "yaw" and
     * Z tracks "roll", i.e. the quaternion is in an OpenGL-style frame (x right, y up, z back), not
     * the NWU frame the docs describe. So it is already camera->world in GL axes; we only need to
     * re-express the GL world in our Z-up scene: x_s = x_gl, y_s = -z_gl, z_s = y_gl.
     */
    @Override
    public void onGlassesPose(float rawW, float rawX, float rawY, float rawZ) {
        poseFilter.update(rawW, rawX, rawY, rawZ, System.nanoTime(), filtered);
        float w = filtered[0], x = filtered[1], y = filtered[2], z = filtered[3];
        float r00 = 1 - 2 * (y * y + z * z), r01 = 2 * (x * y - w * z), r02 = 2 * (x * z + w * y);
        float r10 = 2 * (x * y + w * z), r11 = 1 - 2 * (x * x + z * z), r12 = 2 * (y * z - w * x);
        float r20 = 2 * (x * z - w * y), r21 = 2 * (y * z + w * x), r22 = 1 - 2 * (x * x + y * y);
        synchronized (rotMapped) {
            // M = T * R, T = [[1,0,0],[0,0,-1],[0,1,0]]
            rotMapped[0] = r00;  rotMapped[1] = r01;  rotMapped[2] = r02;  rotMapped[3] = 0;
            rotMapped[4] = -r20; rotMapped[5] = -r21; rotMapped[6] = -r22; rotMapped[7] = 0;
            rotMapped[8] = r10;  rotMapped[9] = r11;  rotMapped[10] = r12; rotMapped[11] = 0;
            rotMapped[12] = 0; rotMapped[13] = 0; rotMapped[14] = 0; rotMapped[15] = 1;
            recentreAndPublish();
        }
    }

    /** rotMapped holds the current device->scene rotation: apply the world-space recentre and publish. Caller holds the rotMapped lock. */
    private void recentreAndPublish() {
            // Full 3-axis recentre: remember the pose at reset time and show everything relative to it,
            // so "straight ahead" is wherever the head was pointing (pitch and roll included).
            float[] cur = sCur;
            cur[0] = rotMapped[0]; cur[1] = rotMapped[1]; cur[2] = rotMapped[2];
            cur[3] = rotMapped[4]; cur[4] = rotMapped[5]; cur[5] = rotMapped[6];
            cur[6] = rotMapped[8]; cur[7] = rotMapped[9]; cur[8] = rotMapped[10];
            if (recenterRequested || !refValid) {
                // Reference pose = Rref (device->scene). Its heading psi0 is taken from the forward vector.
                System.arraycopy(cur, 0, rotRef, 0, 9);
                float psi = (float) Math.atan2(-cur[2], -cur[5]);
                float c = (float) Math.cos(psi), s = (float) Math.sin(psi);
                // K1 = Rz(psi0), K2 = Rz(-psi0) * M0, with M0 = [[1,0,0],[0,0,-1],[0,1,0]] (level, facing +Y)
                rotK1[0] = c;  rotK1[1] = -s; rotK1[2] = 0;
                rotK1[3] = s;  rotK1[4] = c;  rotK1[5] = 0;
                rotK1[6] = 0;  rotK1[7] = 0;  rotK1[8] = 1;
                float[] rzn = sRzn, m0 = S_M0;
                rzn[0] = c; rzn[1] = s; rzn[2] = 0; rzn[3] = -s; rzn[4] = c; rzn[5] = 0; rzn[6] = 0; rzn[7] = 0; rzn[8] = 1;
                mul3(rzn, m0, rotK2);
                refValid = true;
                recenterRequested = false;
            }
            // World-space relative rotation since the reference, applied to the level pose:
            //   M = K1 * (R * Rref^T) * K2   -> yaw stays about world vertical, pitch about the horizontal.
            float[] rt = sRt, t1 = sT1, t2 = sT2, m = sM;
            rt[0] = rotRef[0]; rt[1] = rotRef[3]; rt[2] = rotRef[6];
            rt[3] = rotRef[1]; rt[4] = rotRef[4]; rt[5] = rotRef[7];
            rt[6] = rotRef[2]; rt[7] = rotRef[5]; rt[8] = rotRef[8];
            mul3(cur, rt, t1);
            mul3(rotK1, t1, t2);
            mul3(t2, rotK2, m);
            rotMapped[0] = m[0]; rotMapped[1] = m[1]; rotMapped[2] = m[2];
            rotMapped[4] = m[3]; rotMapped[5] = m[4]; rotMapped[6] = m[5];
            rotMapped[8] = m[6]; rotMapped[9] = m[7]; rotMapped[10] = m[8];
            yawOffsetDeg = 0f;
            publishMapped();
    }

    private void publishMapped() {
        // rotMapped is row-major device->world. Forward (into the screen) = -column 2.
        float fx = -rotMapped[2], fy = -rotMapped[6];
        float headingDeg = (float) Math.toDegrees(Math.atan2(fx, fy));

        if (recenterRequested && (fx * fx + fy * fy) > 0.05f) {
            // Make the current heading the new "north".
            yawOffsetDeg = headingDeg;
            recenterRequested = false;
        }

        // GL view = (Rz(psi) * R)^T = R^T * Rz(-psi). Row-major R read as column-major == R^T.
        renderer.setOrientation(rotMapped, yawOffsetDeg);

        sampleCount++;
        long now = System.nanoTime();
        if (now - rateWindowStart > 1000000000L) {
            sampleHz = sampleCount * 1e9f / (now - rateWindowStart);
            sampleCount = 0;
            rateWindowStart = now;
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int accuracy) { }

    // ---- input ------------------------------------------------------------------------------

    private static boolean isOk(int k) {
        return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isOk(keyCode)) {
            // Short press acts on key-up; holding OK recentres the view.
            if (event.getRepeatCount() == 0) { okLongHandled = false; event.startTracking(); }
            return true;
        }
        if (playerUi.onKey(keyCode, event.getRepeatCount())) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (isOk(keyCode)) {
            okLongHandled = true;
            playerUi.onLongPressOk();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isOk(keyCode)) {
            if (!okLongHandled && !event.isCanceled()) playerUi.onKey(keyCode, 0);
            okLongHandled = false;
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
}
