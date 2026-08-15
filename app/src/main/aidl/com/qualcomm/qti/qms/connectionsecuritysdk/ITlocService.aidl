package com.qualcomm.qti.qms.connectionsecuritysdk;

interface ITlocService {
    byte[] getTrustedLocation(out int[] iArr, out int[] iArr2);
    int tlocWarmUp();
}
