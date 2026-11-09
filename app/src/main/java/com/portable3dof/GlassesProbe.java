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
    private String requestedDeviceName;
    private boolean permissionPending;
    private volatile int trackingMode = 0;
    private String connectedDeviceName;
    private final BroadcastReceiver usbEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            UsbDevice dev = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (dev == null || dev.getVendorId() != VITURE_VID) return;
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())) {
                if (dev.getDeviceName().equals(connectedDeviceName)) closeConnection();
                if (dev.getDeviceName().equals(requestedDeviceName)) {
                    requestedDeviceName = null;
                    permissionPending = false;
                }
                status = "glasses disconnected";
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) {
                checkForGlasses();
            }
        }
    };

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
        IntentFilter events = new IntentFilter();
        events.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        events.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (android.os.Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(usbEvents, events, Context.RECEIVER_EXPORTED);
        else ctx.registerReceiver(usbEvents, events);
        checkForGlasses();
    }

    /** Recheck on resume without repeating a pending or refused permission request. */
    public void checkForGlasses() {
        if (running) return;
        UsbDevice dev = find();
        if (dev == null) { status = "glasses not found on USB"; return; }
        if (usb.hasPermission(dev)) {
            open(dev);
        } else if (!permissionPending && !dev.getDeviceName().equals(requestedDeviceName)) {
            status = "requesting USB permission...";
            requestedDeviceName = dev.getDeviceName();
            permissionPending = true;
            Intent i = new Intent(ACTION).setPackage(ctx.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_MUTABLE);
            usb.requestPermission(dev, pi);
        }
    }

    public void stop() {
        try { ctx.unregisterReceiver(this); } catch (Exception ignored) { }
        try { ctx.unregisterReceiver(usbEvents); } catch (Exception ignored) { }
        closeConnection();
    }

    private synchronized void closeConnection() {
        running = false;
        if (thread != null) { try { thread.join(600); } catch (InterruptedException ignored) { } }
        thread = null;
        try { GlassesBridge.end(); } catch (Throwable ignored) { }
        if (conn != null) { conn.close(); conn = null; }
        connectedDeviceName = null;
        trackingMode = 0;
        lastPoseNs = 0;
    }

    private UsbDevice find() {
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (d.getVendorId() == VITURE_VID) return d;
        }
        return null;
    }

    @Override
    public void onReceive(Context c, Intent i) {
        if (!permissionPending) return;
        permissionPending = false;
        UsbDevice dev = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (dev != null && dev.getDeviceName().equals(requestedDeviceName)
                && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            open(dev);
        } else {
            status = "USB permission denied";
        }
    }

    private void open(UsbDevice dev) {
        if (running) return;
        conn = usb.openDevice(dev);
        if (conn == null) { status = "openDevice failed"; return; }
        connectedDeviceName = dev.getDeviceName();
        int rc;
        try {
            rc = GlassesBridge.begin(dev.getProductId(), conn.getFileDescriptor());
        } catch (Throwable t) {
            status = "SDK load failed: " + t.getMessage();
            conn.close(); conn = null; connectedDeviceName = null;
            return;
        }
        if (rc < 0 || rc > 2) { status = "SDK begin failed: " + rc; conn.close(); conn = null; connectedDeviceName = null; return; }
        trackingMode = rc;
        running = true;
        if (trackingMode == 1) { status = "native 3DOF active"; return; }
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
            if (trackingMode == 2) {
                if (GlassesBridge.getCarinaPose(p)) {
                    lastPoseNs = System.nanoTime();
                    poses++;
                    winCount++;
                    if (listener != null) listener.onGlassesPose(p[3], p[4], p[5], p[6]);
                }
                long now = System.nanoTime();
                if (now - winStart > 1000000000L) {
                    hz = winCount * 1e9f / (now - winStart); winCount = 0; winStart = now;
                    status = now - lastPoseNs < 1000000000L ? "Carina pose" : "waiting for Carina pose";
                }
                try { Thread.sleep(8); } catch (InterruptedException ignored) { }
                continue;
            }
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
        return running && (trackingMode == 1 || System.nanoTime() - lastPoseNs < 500000000L);
    }

    public boolean isNativeTracking() { return running && trackingMode == 1; }

    public synchronized void recenterNative() {
        if (isNativeTracking()) {
            int rc = GlassesBridge.recenterNative();
            if (rc != 0) status = "native recenter failed: " + rc;
        }
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
