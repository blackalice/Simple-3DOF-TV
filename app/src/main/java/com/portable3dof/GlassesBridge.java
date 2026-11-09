package com.portable3dof;

/** JNI bridge to Viture's glasses SDK (see src/main/cpp/p3dof_bridge.cpp). */
public final class GlassesBridge {
    static {
        System.loadLibrary("glasses");
        System.loadLibrary("p3dof_bridge");
    }

    private GlassesBridge() { }

    /** Start tracking: 0 = pose callback, 1 = glasses' native 3DOF, 2 = Carina; negative = error. */
    public static native int begin(int productId, int usbFd);

    public static native void end();

    public static native int recenterNative();
    public static native boolean getCarinaPose(float[] out);

    /** Copies [roll, pitch, yaw, qw, qx, qy, qz] into out and returns a sequence number (0 = none yet). */
    public static native long getPose(float[] out);

    /** Blocks until a pose newer than lastSeq arrives (or the timeout passes); same output as getPose. */
    public static native long waitPose(float[] out, long lastSeq, int timeoutMs);
}
