package com.qualcomm.qti.qms.connectionsecuritysdk;

interface ITlocService {
    byte[] getTrustedLocation(int[] iArr, int[] iArr2);
    int tlocWarmUp();
}
