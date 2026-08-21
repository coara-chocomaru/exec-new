package com.android.net;

import android.os.IBinder;

interface IProxyCallback {
    void getProxyPort(IBinder callback);
}
