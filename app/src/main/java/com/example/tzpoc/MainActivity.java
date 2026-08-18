package com.example.tzpoc;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.example.tzpoc.credential.CredentialHelper;

import java.security.cert.CertificateException;

public class MainActivity extends Activity {
    private TextView tvStatus, tvLog;
    private Button btnStart, btnStop;

    static {
        System.loadLibrary("tzpoc");
    }

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
                appendLog("資格情報を生成中...");
                byte[] creds = buildCredentials();
                if (creds == null) {
                    appendLog("資格情報の生成に失敗しました");
                    tvStatus.setText("資格情報生成失敗");
                    return;
                }
                appendLog("資格情報生成成功 (サイズ: " + creds.length + " バイト)");
                int result = callSystemNativeMethods(creds);
                appendLog("ネイティブ呼び出し結果: " + result);
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

    private byte[] buildCredentials() {
        try {
            return CredentialHelper.getCredentials(this, Process.myPid(), Process.myUid());
        } catch (PackageManager.NameNotFoundException | CertificateException e) {
            appendLog("資格情報生成エラー: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private void appendLog(String msg) {
        tvLog.append(msg + "\n");
        android.util.Log.d("TZPoC", msg);
    }
}
