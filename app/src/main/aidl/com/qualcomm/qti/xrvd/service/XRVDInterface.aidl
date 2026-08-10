package com.qualcomm.qti.xrvd.service;

import android.content.Intent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import com.qualcomm.qti.xrvd.service.XRVDInterfaceCallback;

interface XRVDInterface {
    int createXRVirtualDisplay(String name, int width, int height, int density, in Surface surface, int flags);
    void resizeXRVirtualDisplay(String name, int width, int height, int density);
    void setSurfaceXRVirtualDisplay(String name, in Surface surface);
    void releaseXRVirtualDisplay(String name);
    void startActivityOnXRVirtualDisplay(in Intent intent, int displayID);
    boolean injectMotionEvent(in MotionEvent event, int displayID);
    boolean injectKeyEvent(in KeyEvent event, int displayID);
    boolean installLicense(String license);
    void releaseAllXRVirtualDisplays();
    boolean setParam(int param, String val);
    String getParam(int param);
    void registerCallback(XRVDInterfaceCallback cb);
    void unregisterCallback(XRVDInterfaceCallback cb);
}
