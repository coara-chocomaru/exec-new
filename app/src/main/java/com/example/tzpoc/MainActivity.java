package com.example.tzpoc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.qualcomm.qti.qms.api.minksocket.IMinkSocketFd;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private static final String TARGET_PKG = "com.qualcomm.qti.qms.service.trustzoneaccess";
    private static final String TARGET_CLS = "com.qualcomm.qti.qms.service.trustzoneaccess.TZAccessService";

    private TextView tvStatus, tvLog;
    private Button btnStart, btnStop;
    private Handler handler = new Handler(Looper.getMainLooper());
    private StringBuilder logBuilder = new StringBuilder();
    private IMinkSocketFd tzService;
    private boolean isBound = false;
    private AtomicBoolean isTesting = new AtomicBoolean(false);
    private AtomicBoolean stopRequested = new AtomicBoolean(false);
    private Thread testThread;

    static {
        System.loadLibrary("pocjni");
    }

    // 必要最小限のネイティブメソッド
    public static native int nativeOpenDevice(String path);
    public static native String nativeTestFd(int fd);
    public static native byte[] nativeBinderTransaction(int fd, int handle, int code, int flags, byte[] data);
    public static native byte[] nativeBinderGetService(int fd, String serviceName);
    public static native byte[] nativeBinderPing(int fd);

    private ServiceConnection tzConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            tzService = IMinkSocketFd.Stub.asInterface(service);
            if (tzService != null) {
                appendLog("[TZ] Service bound");
                updateStatus("Bound - starting");
                enableButtons(false, true);
                stopRequested.set(false);
                testThread = new Thread(() -> executeExploit());
                testThread.start();
            } else {
                appendLog("[TZ] Failed to cast");
                enableButtons(true, false);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            tzService = null;
            isBound = false;
            enableButtons(true, false);
            updateStatus("Disconnected");
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

        requestPermissions();

        btnStart.setOnClickListener(v -> {
            if (!isTesting.get()) {
                isTesting.set(true);
                enableButtons(false, true);
                stopRequested.set(false);
                bindService();
            }
        });
        btnStop.setOnClickListener(v -> {
            if (isTesting.get()) {
                stopRequested.set(true);
                if (testThread != null) testThread.interrupt();
                enableButtons(false, false);
                updateStatus("Stopping...");
                appendLog("--- Stop requested ---");
                saveLog();
                enableButtons(true, false);
                isTesting.set(false);
            }
        });
        appendLog("App started. Press 'Start'.");
    }

    private void requestPermissions() {
        String[] perms = {
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        };
        List<String> toRequest = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(p);
            }
        }
        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), 100);
        }
    }

    private void bindService() {
        try {
            Intent intent = new Intent();
            intent.setClassName(TARGET_PKG, TARGET_CLS);
            boolean ret = bindService(intent, tzConnection, Context.BIND_AUTO_CREATE);
            if (ret) {
                appendLog("Binding service...");
                updateStatus("Binding...");
                isBound = true;
            } else {
                appendLog("bindService failed");
                updateStatus("Bind failed");
                enableButtons(true, false);
                isTesting.set(false);
            }
        } catch (Exception e) {
            appendLog("Bind exception: " + e.toString());
            updateStatus("Exception");
            enableButtons(true, false);
            isTesting.set(false);
        }
    }

    private void enableButtons(boolean startEnabled, boolean stopEnabled) {
        handler.post(() -> {
            btnStart.setEnabled(startEnabled);
            btnStop.setEnabled(stopEnabled);
        });
    }

    private void executeExploit() {
        appendLog("========================================");
        appendLog("========== BINDER EXPLORATION ==========");

        int hwbinderFd = nativeOpenDevice("/dev/hwbinder");
        int binderFd = nativeOpenDevice("/dev/binder");

        if (hwbinderFd < 0 && binderFd < 0) {
            appendLog("[!] No binder device available");
            return;
        }

        // 優先して hwbinder を使う
        int fd = (hwbinderFd >= 0) ? hwbinderFd : binderFd;
        String devName = (hwbinderFd >= 0) ? "/dev/hwbinder" : "/dev/binder";
        appendLog("[+] Using " + devName + " fd=" + fd);

        // 1. Ping テスト
        appendLog("[*] Sending PING transaction (code=0xFFFFFFFE)");
        byte[] pingReply = nativeBinderPing(fd);
        if (pingReply != null) {
            appendLog("[PING] Reply len=" + pingReply.length);
            dumpToFile(pingReply, "binder_ping_reply.bin");
        } else {
            appendLog("[PING] No reply or error");
        }

        // 2. サービス取得テスト (surfaceflinger)
        appendLog("[*] Getting service 'surfaceflinger'");
        byte[] svcReply = nativeBinderGetService(fd, "surfaceflinger");
        if (svcReply != null) {
            appendLog("[GETSVC] Reply len=" + svcReply.length);
            dumpToFile(svcReply, "binder_getsvc_surfaceflinger.bin");
            // 応答の最初の4バイトがハンドル（32bit）と仮定
            if (svcReply.length >= 4) {
                int handle = ((svcReply[0] & 0xFF) |
                              ((svcReply[1] & 0xFF) << 8) |
                              ((svcReply[2] & 0xFF) << 16) |
                              ((svcReply[3] & 0xFF) << 24));
                appendLog("[GETSVC] Parsed handle = " + handle + " (0x" + Integer.toHexString(handle) + ")");
                if (handle != 0) {
                    // そのハンドルに対して空トランザクションを送信
                    appendLog("[*] Sending empty transaction to handle " + handle);
                    byte[] txReply = nativeBinderTransaction(fd, handle, 0, 0, null);
                    if (txReply != null) {
                        appendLog("[TX] Reply len=" + txReply.length);
                        dumpToFile(txReply, "binder_tx_handle_" + handle + ".bin");
                    } else {
                        appendLog("[TX] No reply or error");
                    }
                }
            }
        } else {
            appendLog("[GETSVC] Failed or no reply");
        }

        // 3. その他、いくつかのハンドル（0〜9）に空トランザクションを送信（既に前回成功しているので再確認）
        for (int h = 0; h <= 9; h++) {
            appendLog("[*] Empty transaction to handle " + h);
            byte[] reply = nativeBinderTransaction(fd, h, 0, 0, null);
            if (reply != null) {
                appendLog("[TX] handle=" + h + " reply len=" + reply.length);
                dumpToFile(reply, "binder_tx_handle_" + h + ".bin");
            } else {
                appendLog("[TX] handle=" + h + " no reply");
            }
        }

        // 4. いくつかのコード（0x01, 0x02, 0x03）も試す（handle=0）
        for (int code = 1; code <= 3; code++) {
            appendLog("[*] Transaction handle=0 code=" + code);
            byte[] reply = nativeBinderTransaction(fd, 0, code, 0, null);
            if (reply != null) {
                appendLog("[TX] code=" + code + " reply len=" + reply.length);
                dumpToFile(reply, "binder_tx_handle0_code" + code + ".bin");
            } else {
                appendLog("[TX] code=" + code + " no reply");
            }
        }

        // 5. 最後に、以前ダンプした16バイトの応答を解析（コード内では特に使わないが、ログに表示）
        // これは Step3 でダンプしたものと同じく、別途保存される

        if (hwbinderFd >= 0) try { ParcelFileDescriptor.adoptFd(hwbinderFd).close(); } catch (Exception e) {}
        if (binderFd >= 0) try { ParcelFileDescriptor.adoptFd(binderFd).close(); } catch (Exception e) {}

        appendLog("========== EXPLORATION COMPLETED ==========");
        appendLog("========================================");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
        finishTest();
    }

    private void dumpToFile(byte[] data, String filename) {
        File dir = getDumpDir();
        if (dir == null || data == null) return;
        File file = new File(dir, filename);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
            appendLog("[DUMP] Saved " + data.length + " bytes to " + file.getAbsolutePath());
        } catch (Exception e) {
            appendLog("[DUMP] Failed to save " + filename + ": " + e.getMessage());
        }
    }

    private File getDumpDir() {
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && (dir.exists() || dir.mkdirs())) return dir;
        }
        return getFilesDir();
    }

    private void appendLog(final String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        final String line = "[" + ts + "] " + msg + "\n";
        logBuilder.append(line);
        handler.post(() -> {
            tvLog.append(line);
            View parent = (View) tvLog.getParent();
            if (parent instanceof ScrollView) ((ScrollView) parent).fullScroll(View.FOCUS_DOWN);
        });
    }

    private void updateStatus(final String status) {
        handler.post(() -> tvStatus.setText(status));
    }

    private void saveLog() {
        try {
            File dir = getDumpDir();
            File file = new File(dir, "binder_explore_log.txt");
            try (PrintWriter pw = new PrintWriter(new FileOutputStream(file), false, StandardCharsets.UTF_8)) {
                pw.println("=== Binder Exploration Log ===");
                pw.println("Timestamp: " + new Date().toString());
                pw.println("===================================");
                pw.print(logBuilder.toString());
                pw.flush();
            }
            appendLog("Log saved to " + file.getAbsolutePath());
        } catch (Exception e) {
            appendLog("Save failed: " + e.getMessage());
        }
    }

    private void finishTest() {
        handler.post(() -> {
            Toast.makeText(MainActivity.this, "Exploration completed", Toast.LENGTH_LONG).show();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                finishAffinity();
                System.exit(0);
            }, 3000);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRequested.set(true);
        if (testThread != null) testThread.interrupt();
        if (isBound) unbindService(tzConnection);
        saveLog();
    }
}
