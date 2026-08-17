package com.qualcomm.qti.qms.service.connectionsecurity.core;

public class TlocServiceImpl {
    public native int tlocWarmUp(String str, int len, int[] result);
    public native int getTrustedLocation(byte[] data, int len, int[] result, Object callback, int flags, int[] outLen);
}
