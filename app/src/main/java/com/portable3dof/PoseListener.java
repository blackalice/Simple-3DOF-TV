package com.portable3dof;

public interface PoseListener {
    /** Orientation quaternion from the glasses (w, x, y, z), called on the USB reader thread. */
    void onGlassesPose(float w, float x, float y, float z);
}
