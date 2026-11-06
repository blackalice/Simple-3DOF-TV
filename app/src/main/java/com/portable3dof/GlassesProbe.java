package com.portable3dof;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import java.util.Locale;

/**
 * Finds the Viture glasses on USB, gets permission, opens the device and hands its file descriptor
 * to Viture's SDK (through GlassesBridge). A reader thread then polls the latest pose.
 */
public class GlassesProbe extends BroadcastReceiver implements Runnable {
    public static final int VITURE_VID = 0x35CA;
    private static final String ACTION = "com.portable3dof.USB_PERMISSION";

    private final Context ctx;
    private final UsbManager usb;
    private final PoseListener listener;

    private volatile String status = "starting";
    private volatile long poses = 0;
    private volatile float hz = 0;
    private volatile float roll, pitch, yaw;
    private volatile boolean running = false;
    private volatile long lastPoseNs = 0;

    private Thread thread;
    private UsbDeviceConnection conn;

    public GlassesProbe(Context ctx, PoseListener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.usb = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
    }

    public void start() {
        IntentFilter f = new IntentFilter(ACTION);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(this, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(this, f);
        }
        UsbDevice dev = find();
        if (dev == null) { status = "glasses not found on USB"; return; }
        if (usb.hasPermission(dev)) {
            open(dev);
        } else {
            status = "requesting USB permission...";
            Intent i = new Intent(ACTION).setPackage(ctx.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_MUTABLE);
            usb.requestPermission(dev, pi);
        }
    }

    public void stop() {
        running = false;
        try { ctx.unregisterReceiver(this); } catch (Exception ignored) { }
        if (thread != null) { try { thread.join(600); } catch (InterruptedException ignored) { } }
        thread = null;
        try { GlassesBridge.end(); } catch (Throwable ignored) { }
        if (conn != null) { conn.close(); conn = null; }
    }

    private UsbDevice find() {
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (d.getVendorId() == VITURE_VID) return d;
        }
        return null;
    }

    @Override
    public void onReceive(Context c, Intent i) {
        UsbDevice dev = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (dev != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            open(dev);
        } else {
            status = "USB permission denied";
        }
    }

    private void open(UsbDevice dev) {
        if (running) return;
        conn = usb.openDevice(dev);
        if (conn == null) { status = "openDevice failed"; return; }
        int rc;
        try {
            rc = GlassesBridge.begin(dev.getProductId(), conn.getFileDescriptor());
        } catch (Throwable t) {
            status = "SDK load failed: " + t.getMessage();
            return;
        }
        if (rc != 0) { status = "SDK begin failed: " + rc; return; }
        running = true;
        status = "SDK started, waiting for pose";
        thread = new Thread(this, "glasses-pose");
        thread.start();
    }

    @Override
    public void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
        float[] p = new float[7];
        long lastSeq = 0;
        long winStart = System.nanoTime();
        int winCount = 0;
        while (running) {
            // Blocks inside the SDK bridge until the next sample is delivered (no polling jitter).
            long seq = GlassesBridge.waitPose(p, lastSeq, 50);
            long now = System.nanoTime();
            if (seq != lastSeq && seq != 0) {
                winCount += (int) Math.max(1, seq - lastSeq);
                lastSeq = seq;
                poses = seq;
                roll = p[0]; pitch = p[1]; yaw = p[2];
                lastPoseNs = now;
                if (listener != null) listener.onGlassesPose(p[3], p[4], p[5], p[6]);
            }
            if (now - winStart > 1000000000L) {
                hz = winCount * 1e9f / (now - winStart);
                winCount = 0;
                winStart = now;
                status = (now - lastPoseNs < 1000000000L) ? "streaming pose" : "waiting for pose";
            }
        }
    }

    public boolean isStreaming() {
        return System.nanoTime() - lastPoseNs < 500000000L;
    }

    public float getRateHz() { return hz; }

    /** Short status for the UI, e.g. "requesting USB permission...". */
    public String statusLine() { return status; }

    public String describe() {
        return String.format(Locale.US,
                "glasses: %s  %.0f Hz\n  poses=%d\n  rpy: %.1f %.1f %.1f",
                status, hz, poses, roll, pitch, yaw);
    }
}
