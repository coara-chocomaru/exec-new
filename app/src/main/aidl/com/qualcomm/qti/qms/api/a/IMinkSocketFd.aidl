package com.qualcomm.qti.qms.api.a;

import android.os.ParcelFileDescriptor;

interface IMinkSocketFd {
    ParcelFileDescriptor a(in String str, inout int[] iArr);
}
