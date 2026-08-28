package android.os;

import android.os.IBinder;
import android.os.WorkSource;
import android.os.PowerSaveState;

interface IPowerManager {
    void acquireWakeLock(IBinder lock, int flags, String tag, String packageName, WorkSource ws, String historyTag);
    void acquireWakeLockWithUid(IBinder lock, int flags, String tag, String packageName, int uidtoblame);
    void releaseWakeLock(IBinder lock, int flags);
    void updateWakeLockUids(IBinder lock, int[] uids);
    void powerHint(int hintId, int data);
    void updateWakeLockWorkSource(IBinder lock, WorkSource ws, String historyTag);
    boolean isWakeLockLevelSupported(int level);
    void userActivity(long time, int event, int flags);
    void wakeUp(long time, String reason, String opPackageName);
    void goToSleep(long time, int reason, int flags);
    void nap(long time);
    boolean isInteractive();
    boolean isPowerSaveMode();
    PowerSaveState getPowerSaveState(int serviceType);
    boolean setPowerSaveMode(boolean mode);
    boolean isDeviceIdleMode();
    boolean isLightDeviceIdleMode();
    void reboot(boolean confirm, String reason, boolean wait);
    void rebootSafeMode(boolean confirm, boolean wait);
    void shutdown(boolean confirm, String reason, boolean wait);
    void crash(String message);
    int getLastShutdownReason();
    void setStayOnSetting(int val);
    void boostScreenBrightness(long time);
    boolean isScreenBrightnessBoosted();
    void setAttentionLight(boolean on, int color);
    void setDozeAfterScreenOff(boolean on);
}
