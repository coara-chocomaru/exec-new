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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private static final String TARGET_PKG_TZ = "com.qualcomm.qti.qms.service.trustzoneaccess";
    private static final String TARGET_CLS_TZ = "com.qualcomm.qti.qms.service.trustzoneaccess.TZAccessService";

    private TextView tvStatus, tvLog;
    private Button btnStart, btnStop;
    private Handler handler = new Handler(Looper.getMainLooper());
    private StringBuilder logBuilder = new StringBuilder();
    private IMinkSocketFd mTZService;
    private boolean isBound = false;
    private AtomicBoolean isTesting = new AtomicBoolean(false);
    private AtomicBoolean stopRequested = new AtomicBoolean(false);
    private Thread testThread;

    static {
        System.loadLibrary("pocjni");
    }

    public static native ParcelFileDescriptor nativeConnectSocket(IMinkSocketFd tzService, String path, int[] handleArr);

    private ServiceConnection tzConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mTZService = IMinkSocketFd.Stub.asInterface(service);
            appendLog("[TZ] Service bound");
            updateStatus("Bound - starting dump");
            enableButtons(false, true);
            stopRequested.set(false);
            testThread = new Thread(() -> executeDump());
            testThread.start();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mTZService = null;
            isBound = false;
            enableButtons(true, false);
            updateStatus("TZ disconnected");
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
        appendLog("App started. Press 'Start' to begin.");
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
            intent.setClassName(TARGET_PKG_TZ, TARGET_CLS_TZ);
            boolean ret = bindService(intent, tzConnection, Context.BIND_AUTO_CREATE);
            if (ret) {
                appendLog("Binding service...");
                updateStatus("Binding...");
                isBound = true;
            } else {
                appendLog("bindService returned false");
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

    private void executeDump() {
        appendLog("========================================");
        appendLog("========== Socket Information Dump ==========");

        // 読み取り対象のソケットと、送信するコマンド（プロトコルが分からないものはダミー）
        String[][] socketCommands = {
            {"/dev/socket/mdnsd", "help\n", "status\n", "version\n", "list\n"},
            {"/dev/socket/tcm", "help\n", "status\n", "version\n", "list\n"},
            {"/dev/socket/fwmarkd", "\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00"}, // 16バイトゼロ（ダミー）
            {"/dev/socket/dnsproxyd", "help\n", "status\n", "version\n", "list\n"},
            {"/dev/socket/logd", "help\n", "status\n", "version\n", "list\n"},
            {"/dev/socket/netd", "help\n", "status\n", "version\n", "list\n"},
            {"/dev/socket/location", "help\n", "status\n", "version\n", "list\n"}
        };

        for (String[] entry : socketCommands) {
            if (stopRequested.get()) break;
            String path = entry[0];
            appendLog("[DUMP] Dumping socket: " + path);
            ParcelFileDescriptor pfd = null;
            try {
                int[] iArr = new int[1];
                pfd = mTZService.a(path, iArr);
                if (pfd == null) {
                    appendLog("  [FAIL] Could not open socket");
                    continue;
                }
                java.io.FileDescriptor fdesc = pfd.getFileDescriptor();
                if (fdesc == null || !fdesc.valid()) {
                    appendLog("  [FAIL] Invalid FD");
                    pfd.close();
                    continue;
                }

                OutputStream os = new FileOutputStream(fdesc);
                InputStream is = new FileInputStream(fdesc);

                // 各コマンドを送信して応答を読み取る
                for (int i = 1; i < entry.length; i++) {
                    if (stopRequested.get()) break;
                    String cmd = entry[i];
                    appendLog("  CMD[" + cmd.trim() + "]");
                    try {
                        os.write(cmd.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        // 応答を最大 2KB まで読み取り
                        byte[] buffer = new byte[2048];
                        int totalRead = 0;
                        long startTime = System.currentTimeMillis();
                        while (totalRead < buffer.length && (System.currentTimeMillis() - startTime) < 1000) {
                            if (is.available() > 0) {
                                int n = is.read(buffer, totalRead, buffer.length - totalRead);
                                if (n <= 0) break;
                                totalRead += n;
                            } else {
                                Thread.sleep(30);
                            }
                        }
                        if (totalRead > 0) {
                            String response = new String(buffer, 0, totalRead, StandardCharsets.UTF_8);
                            appendLog("    -> " + response.replace("\n", "\\n").replace("\r", "\\r"));
                        } else {
                            appendLog("    -> (no response)");
                        }
                    } catch (Exception e) {
                        appendLog("    -> error: " + e.getMessage());
                    }
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }

                os.close();
                is.close();
                pfd.close();
            } catch (Exception e) {
                appendLog("  Error: " + e.toString());
                if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
            }
        }

        // システムプロパティのダンプ（リフレクション経由）
        appendLog("========== System Properties Dump ==========");
        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties");
            Method getMethod = spClass.getMethod("get", String.class);
            // よく使われるプロパティを列挙
            String[] props = {
                "ro.build.version.release",
                "ro.product.model",
                "ro.product.manufacturer",
                "ro.build.date",
                "persist.sys.timezone",
                "persist.sys.language",
                "persist.sys.country",
                "sys.retaildemo.enabled",
                "ro.boot.hardware",
                "ro.boot.serialno"
            };
            for (String prop : props) {
                if (stopRequested.get()) break;
                String value = (String) getMethod.invoke(null, prop);
                appendLog("[PROP] " + prop + " = " + (value != null ? value : "(null)"));
            }
        } catch (Exception e) {
            appendLog("[PROP] Reflection error: " + e.getMessage());
        }

        // /proc ファイルの読み取り（JNI経由）
        appendLog("========== /proc Info Dump ==========");
        String[] procFiles = {
            "/proc/version",
            "/proc/self/status",
            "/proc/self/cmdline",
            "/proc/meminfo",
            "/proc/cpuinfo"
        };
        for (String f : procFiles) {
            if (stopRequested.get()) break;
            try {
                String content = nativeReadFile(f);
                if (content != null && !content.isEmpty()) {
                    appendLog("[PROC] " + f + ":\n" + content);
                } else {
                    appendLog("[PROC] " + f + " -> (empty or inaccessible)");
                }
            } catch (Exception e) {
                appendLog("[PROC] " + f + " error: " + e.getMessage());
            }
        }

        appendLog("========== DUMP COMPLETED ==========");
        appendLog("========================================");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
        finishTest();
    }

    // ファイル読み取り用のJNIヘルパー（nativeReadFileは実装済みと仮定）
    private native String nativeReadFile(String path);

    private void appendLog(final String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
        final String line = "[" + ts + "] " + msg + "\n";
        logBuilder.append(line);
        handler.post(() -> {
            tvLog.append(line);
            View parent = (View) tvLog.getParent();
            if (parent instanceof ScrollView) {
                ((ScrollView) parent).fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void updateStatus(final String status) {
        handler.post(() -> tvStatus.setText(status));
    }

    private void saveLog() {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists() && !dir.mkdirs()) {
                appendLog("Cannot create Download dir");
                return;
            }
            File file = new File(dir, "socket_dump_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== Socket Dump Log ===");
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
            Toast.makeText(MainActivity.this, "Dump completed", Toast.LENGTH_LONG).show();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                finishAffinity();
                System.exit(0);
            }, 2000);
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
