package com.example.dpmpoc;

import android.util.Log;

public class NativeHelper {

    static {
        try {
            System.loadLibrary("dpm_hook");
        } catch (UnsatisfiedLinkError e) {
            Log.w("NativeHelper", "ライブラリロード失敗: " + e.getMessage());
        }
    }

    public static native String execCommand(String cmd);

    public static String execCommandFallback(String cmd) {
        return "Fallback: cannot execute " + cmd;
    }
}
