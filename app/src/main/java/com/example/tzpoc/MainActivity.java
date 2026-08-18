package com.example.tzpoc;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.lang.reflect.Method;

public class MainActivity extends Activity {
    private TextView tvStatus, tvLog;
    private Button btnStart, btnStop;

    static {
        System.loadLibrary("tzpoc");
    }

    // Native methods that call system libraries
    public native int callSystemNativeMethods(byte[] credentials);

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
                tvStatus.setText("実行中...");
                appendLog("Service binding skipped (no cert) – using native direct calls instead.");
                // 1. 資格情報を取得 (CredentialHelper をリフレクションで呼び出す)
                byte[] creds = getCredentials();
                if (creds == null) {
                    appendLog("Failed to obtain credentials");
                    tvStatus.setText("資格情報取得失敗");
                    return;
                }
                appendLog("Credentials obtained, length: " + creds.length);
                // 2. ネイティブメソッドを呼び出し
                int result = callSystemNativeMethods(creds);
                appendLog("Native call returned: " + result);
                tvStatus.setText("ネイティブ呼び出し完了 (戻り値: " + result + ")");
                btnStop.setEnabled(true);
            }
        });

        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tvStatus.setText("停止しました");
                appendLog("ユーザーにより停止");
                btnStop.setEnabled(false);
            }
        });
    }

    // リフレクションで CredentialHelper.getCredentials を呼び出す
    private byte[] getCredentials() {
        try {
            Class<?> clazz = Class.forName("com.qualcomm.qti.qms.credential.utils.CredentialHelper");
            Method method = clazz.getMethod("getCredentials", Context.class, int.class, int.class);
            // 自分の PID と UID を渡す
            int pid = android.os.Process.myPid();
            int uid = android.os.Process.myUid();
            return (byte[]) method.invoke(null, this, pid, uid);
        } catch (Exception e) {
            appendLog("Reflection error: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private void appendLog(String msg) {
        tvLog.append(msg + "\n");
        android.util.Log.d("TZPoC", msg);
    }
}
