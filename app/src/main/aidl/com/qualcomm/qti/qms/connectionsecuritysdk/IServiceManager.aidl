package com.qualcomm.qti.qms.connectionsecuritysdk;

import android.os.IBinder;

interface IServiceManager {
    IBinder getService(String str, byte[] bArr, int[] iArr);
}
