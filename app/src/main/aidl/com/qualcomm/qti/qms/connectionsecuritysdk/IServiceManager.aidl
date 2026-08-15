package com.qualcomm.qti.qms.connectionsecuritysdk;

import android.os.IBinder;

interface IServiceManager {
    IBinder getService(in String str, in byte[] bArr, out int[] iArr);
}
