package com.qualcomm.qti.qms.api.minksocket;

import android.os.ParcelFileDescriptor;

interface IMinkSocketFd {
    ParcelFileDescriptor openSocket(in String path, inout int[] handle);
}
