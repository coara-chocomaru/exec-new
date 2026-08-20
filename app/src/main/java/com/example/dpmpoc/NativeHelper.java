package com.example.dpmpoc;

public class NativeHelper {

    static {
        try {
            System.loadLibrary("dpm_hook");
        } catch (UnsatisfiedLinkError e) {
            Log.w("NativeHelper", "ライブラリロード失敗: " + e.getMessage());
        }
    }

    // ネイティブメソッド宣言 (実際には .so に実装が必要)
    public static native String execCommand(String cmd);

    // フォールバック: Java で代替 (実際には exec できないが、デモ)
    public static String execCommandFallback(String cmd) {
        return "Fallback: cannot execute " + cmd;
    }
}
