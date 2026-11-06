package com.portable3dof;

/** JNI bridge to Viture's glasses SDK (see src/main/cpp/p3dof_bridge.cpp). */
public final class GlassesBridge {
    static {
        System.loadLibrary("glasses");
        System.loadLibrary("p3dof_bridge");
    }

    private GlassesBridge() { }

    /** Create provider from the USB product id + file descriptor, start it, and open the IMU (pose mode). 0 = ok. */
    public static native int begin(int productId, int usbFd);

    public static native void end();

    /** Copies [roll, pitch, yaw, qw, qx, qy, qz] into out and returns a sequence number (0 = none yet). */
    public static native long getPose(float[] out);

    /** Blocks until a pose newer than lastSeq arrives (or the timeout passes); same output as getPose. */
    public static native long waitPose(float[] out, long lastSeq, int timeoutMs);
}
