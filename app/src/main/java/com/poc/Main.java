package com.poc;

import android.util.Log;
import java.io.File;

public class Main {
    private static final String TAG = "Poc";

    static {
        try {
            String apkPath = Main.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            File apkFile = new File(apkPath);
            File libBase = new File(apkFile.getParentFile(), "lib");

            String abi = System.getProperty("os.arch").toLowerCase();
            String libDir = "arm64";
            if (abi.contains("v7a")) libDir = "armeabi-v7a";
            
            File libFile = new File(new File(libBase, libDir), "libexploit.so");
            if (!libFile.exists()) {
                System.loadLibrary("exploit");
            } else {
                System.load(libFile.getAbsolutePath());
            }
            Log.i(TAG, "[+] SO loaded successfully from APK!");
        } catch (Throwable t) {
            Log.e(TAG, "[-] Failed to load SO", t);
        }
    }

    public static void main(String[] args) {
        Log.i(TAG, "[+] DEX Main executed!");
        try {
            Thread.sleep(60000);
        } catch (InterruptedException e) {}
    }
}
