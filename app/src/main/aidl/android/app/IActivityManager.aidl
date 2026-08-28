package android.app;

import android.app.IApplicationThread;
import android.app.ProfilerInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

interface IActivityManager {
    int startActivity(in IApplicationThread caller, String callingPackage, in Intent intent,
                      String resolvedType, IBinder resultTo, String resultWho,
                      int requestCode, int flags, in ProfilerInfo profilerInfo, in Bundle options) throws RemoteException;
    // 他の必要メソッドは必要に応じて追加
}
