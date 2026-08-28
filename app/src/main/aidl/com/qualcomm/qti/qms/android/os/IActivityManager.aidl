package android.app;

import android.content.Intent;
import android.content.ComponentName;
import android.os.IBinder;
import android.os.Bundle;
import android.os.RemoteException;

interface IActivityManager {
    int startActivity(IApplicationThread caller, String callingPackage, Intent intent,
                      String resolvedType, IBinder resultTo, String resultWho,
                      int requestCode, int flags, ProfilerInfo profilerInfo, Bundle options);
}
