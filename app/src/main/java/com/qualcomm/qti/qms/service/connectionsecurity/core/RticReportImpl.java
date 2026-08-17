package com.qualcomm.qti.qms.service.connectionsecurity.core;

public class RticReportImpl {
    public native int getRticData(byte[] data, int len, long id, int[] result, Object callback, int flags, int[] outLen, int mode);
}
