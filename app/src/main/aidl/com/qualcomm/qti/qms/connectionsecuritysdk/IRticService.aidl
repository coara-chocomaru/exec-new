package com.qualcomm.qti.qms.connectionsecuritysdk;

interface IRticService {
    byte[] getRticData(in long j, out int[] iArr, out int[] iArr2, in boolean z);
}
