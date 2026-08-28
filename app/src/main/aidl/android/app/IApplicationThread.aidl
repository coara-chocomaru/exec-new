package android.app;

import android.app.IInstrumentationWatcher;
import android.app.IUiAutomationConnection;
import android.app.servertransaction.ClientTransaction;
import android.content.ComponentName;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ParceledListSlice;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import com.android.internal.app.IVoiceInteractor;
import java.util.List;
import java.util.Map;

interface IApplicationThread {
    void scheduleReceiver(in Intent intent, in ActivityInfo activityInfo, in CompatibilityInfo compatibilityInfo,
                          int userId, String reason, in Bundle bundle, boolean z, int i2, int i3) throws RemoteException;
    void scheduleCreateService(IBinder token, in ServiceInfo info, in CompatibilityInfo compatInfo, int processState) throws RemoteException;
    void scheduleStopService(IBinder token) throws RemoteException;
    void bindApplication(String packageName, in ApplicationInfo info, in List<ProviderInfo> providers,
                         in ComponentName componentName, in ProfilerInfo profilerInfo, in Bundle bundle,
                         in IInstrumentationWatcher instrumentationWatcher, in IUiAutomationConnection uiAutomationConnection,
                         int debugMode, boolean enableOpenGlTrace, boolean isRestrictedBackupMode, boolean persistent,
                         boolean allowApplicationInfoUpdates, in Configuration config, in CompatibilityInfo compatInfo,
                         in Map<String, Object> services, in Bundle coreSettings, String buildSerial, boolean autofillCompatibilityEnabled) throws RemoteException;
    void runIsolatedEntryPoint(String entryPoint, String[] entryPointArgs) throws RemoteException;
    void scheduleExit() throws RemoteException;
    void scheduleServiceArgs(IBinder token, in ParceledListSlice args) throws RemoteException;
    void updateTimeZone() throws RemoteException;
    void processInBackground() throws RemoteException;
    void scheduleBindService(IBinder token, in Intent intent, boolean rebind, int processState) throws RemoteException;
    void scheduleUnbindService(IBinder token, in Intent intent) throws RemoteException;
    void dumpService(in ParcelFileDescriptor fd, IBinder servicetoken, String[] args) throws RemoteException;
    void scheduleRegisteredReceiver(in IIntentReceiver receiver, in Intent intent, int resultCode, String data, in Bundle extras, boolean ordered, boolean sticky, int sendingUser, int flags) throws RemoteException;
    void scheduleLowMemory() throws RemoteException;
    void scheduleSleeping(IBinder token, boolean sleeping) throws RemoteException;
    void profilerControl(boolean start, in ProfilerInfo profilerInfo, int profileType) throws RemoteException;
    void setSchedulingGroup(int group) throws RemoteException;
    void scheduleCreateBackupAgent(in ApplicationInfo app, in CompatibilityInfo compatInfo, int backupMode) throws RemoteException;
    void scheduleDestroyBackupAgent(in ApplicationInfo app, in CompatibilityInfo compatInfo) throws RemoteException;
    void scheduleOnNewActivityOptions(IBinder token, in Bundle options) throws RemoteException;
    void scheduleSuicide() throws RemoteException;
    void dispatchPackageBroadcast(int cmd, String[] packages) throws RemoteException;
    void scheduleCrash(String msg) throws RemoteException;
    void dumpHeap(boolean managed, boolean mallocInfo, boolean runGc, String path, in ParcelFileDescriptor fd) throws RemoteException;
    void dumpActivity(in ParcelFileDescriptor fd, IBinder servicetoken, String prefix, String[] args) throws RemoteException;
    void clearDnsCache() throws RemoteException;
    void setHttpProxy(String proxy, String port, String exclList, in Uri pacFileUrl) throws RemoteException;
    void setCoreSettings(in Bundle coreSettings) throws RemoteException;
    void updatePackageCompatibilityInfo(String pkg, in CompatibilityInfo info) throws RemoteException;
    void scheduleTrimMemory(int level) throws RemoteException;
    void dumpMemInfo(in ParcelFileDescriptor fd, in Debug.MemoryInfo memInfo, boolean checkin, boolean dumpPss, boolean dumpDalvik, boolean dumpSummary, boolean dumpUnreachable, String[] args) throws RemoteException;
    void dumpMemInfoProto(in ParcelFileDescriptor fd, in Debug.MemoryInfo memInfo, boolean dumpPss, boolean dumpDalvik, boolean dumpSummary, boolean dumpUnreachable, String[] args) throws RemoteException;
    void dumpGfxInfo(in ParcelFileDescriptor fd, String[] args) throws RemoteException;
    void dumpProvider(in ParcelFileDescriptor fd, IBinder servicetoken, String[] args) throws RemoteException;
    void dumpDbInfo(in ParcelFileDescriptor fd, String[] args) throws RemoteException;
    void unstableProviderDied(IBinder provider) throws RemoteException;
    void requestAssistContextExtras(IBinder activityToken, IBinder requestToken, int requestType, int sessionId, int flags) throws RemoteException;
    void scheduleTranslucentConversionComplete(IBinder token, boolean drawComplete) throws RemoteException;
    void setProcessState(int state) throws RemoteException;
    void scheduleInstallProvider(in ProviderInfo provider) throws RemoteException;
    void updateTimePrefs(int timeFormatPreference) throws RemoteException;
    void scheduleEnterAnimationComplete(IBinder token) throws RemoteException;
    void notifyCleartextNetwork(byte[] firstPacket) throws RemoteException;
    void startBinderTracking() throws RemoteException;
    void stopBinderTrackingAndDump(in ParcelFileDescriptor fd) throws RemoteException;
    void scheduleLocalVoiceInteractionStarted(IBinder token, in IVoiceInteractor voiceInteractor) throws RemoteException;
    void handleTrustStorageUpdate() throws RemoteException;
    void attachAgent(String path) throws RemoteException;
    void scheduleApplicationInfoChanged(in ApplicationInfo ai) throws RemoteException;
    void setNetworkBlockSeq(long procStateSeq) throws RemoteException;
    void scheduleTransaction(in ClientTransaction transaction) throws RemoteException;
}
