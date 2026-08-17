package com.qualcomm.qti.qms.api.minksocket;

import android.os.ParcelFileDescriptor;

interface IMinkSocketFd {
    ParcelFileDescriptor a(in String path, inout int[] handle);
}
