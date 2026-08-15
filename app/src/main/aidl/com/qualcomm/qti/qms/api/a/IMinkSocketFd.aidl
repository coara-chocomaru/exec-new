package com.qualcomm.qti.qms.api.a;

import android.os.ParcelFileDescriptor;

interface IMinkSocketFd {
    ParcelFileDescriptor a(String str, int[] iArr);
}
