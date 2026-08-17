package com.qualcomm.qti.qms.service.connectionsecurity.core;

public class WifiAuditorServiceImpl {
    public native long nativeCreate(String path);
    public native void nativeDestroy(long handle);
    public native void nativeStartScan();
    public native void nativeStartClientScan(String clientId);
    public native void nativeStartAssociationScan();
    public native void nativeOnFeedbackReceived(String a, String b, String c, byte d);
    public native void nativeUpdateModel(String model);
    public native void nativeGetTrustedAps(String clientId, byte[] data, int len);
    public native void nativeRemoveTrustedAp(String clientId, String bssid, String ssid, byte[] data, int len);
    public native void nativeRegisterClient(String clientId);
}
