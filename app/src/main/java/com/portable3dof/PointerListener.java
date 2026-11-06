package com.portable3dof;

public interface PointerListener {
    /** Air-mouse movement from the Pocket TV's gyro, in browser pixels. May be called from a sensor thread. */
    void onPointerDelta(float dx, float dy);
}
