package com.qualcomm.qti.xrvd.service;

interface XRVDInterfaceCallback {
    void onEvent(int displayID, int event, String info);
}
