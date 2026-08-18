package com.example.tzpoc;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private TextView tvStatus, tvLog;
    private Button btnStart, btnStop;
    private static final String TAG = "TZPoC";

    static {
        System.loadLibrary("tzpoc");
    }

    public native int testNative();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        tvLog = findViewById(R.id.tv_log);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testBroadcastReceiver();
                testNativeLibrary();
            }
        });

        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnStop.setEnabled(false);
                tvStatus.setText("停止しました");
                appendLog("ユーザーにより停止");
            }
        });
    }

    private void testBroadcastReceiver() {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    "com.qualcomm.qti.qms.service.connectionsecurity",
                    "com.qualcomm.qti.qms.service.connectionsecurity.ConnSecBroadcastReceiver"
            ));
            sendBroadcast(intent);
            tvStatus.setText("Broadcast 送信済み");
            appendLog("ConnSecBroadcastReceiver にインテントを送信しました");
        } catch (Exception e) {
            tvStatus.setText("Broadcast 送信失敗");
            appendLog("エラー: " + e.getMessage());
        }
    }

    private void testNativeLibrary() {
        int result = testNative();
        appendLog("JNI testNative() 戻り値: " + result);
        if (result == 0) {
            tvStatus.setText("ネイティブライブラリアクセス成功");
        } else {
            tvStatus.setText("ネイティブライブラリアクセス失敗 (コード " + result + ")");
        }
    }

    private void appendLog(String msg) {
        tvLog.append(msg + "\n");
    }
}
