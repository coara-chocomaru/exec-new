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

    // Native methods
    public static native int nativeOpenDevice(String path);
    public static native byte[] nativeBinderTransaction(int fd, int handle, int code, int flags, byte[] data);
    public static native byte[] nativeBinderGetService(int fd, String serviceName);
    public static native byte[] nativeBinderPing(int fd);
    public static native String nativeBinderDumpReply(int fd, int handle, int code, int flags, String filename);

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
        appendLog("========== BINDER SERVICE EXPLORATION ==========");

        int hwbinderFd = nativeOpenDevice("/dev/hwbinder");
        int binderFd = nativeOpenDevice("/dev/binder");

        if (hwbinderFd < 0 && binderFd < 0) {
            appendLog("[!] No binder device available");
            return;
        }

        int fd = (hwbinderFd >= 0) ? hwbinderFd : binderFd;
        String devName = (hwbinderFd >= 0) ? "/dev/hwbinder" : "/dev/binder";
        appendLog("[+] Using " + devName + " fd=" + fd);

        // Ping test
        appendLog("[*] Sending PING (code=0xFFFFFFFE, handle=0)");
        byte[] pingReply = nativeBinderPing(fd);
        if (pingReply != null) {
            appendLog("[PING] Reply len=" + pingReply.length + " (dumped)");
            dumpToFile(pingReply, "binder_ping_reply.bin");
        } else {
            appendLog("[PING] No reply or error");
        }

        // List of common Android system services
        String[] services = {
            "surfaceflinger", "media.camera", "media.player", "media.extractor",
            "audio", "display", "sensors", "power", "package", "activity",
            "window", "input", "bluetooth", "wifi", "telephony.registry",
            "telecom", "phone", "connectivity", "netd", "wificond",
            "usb", "vibrator", "alarm", "battery", "meminfo",
            "gfxinfo", "cpuinfo", "dbinfo", "device_policy",
            "statusbar", "clipboard", "country_detector", "search",
            "wallpaper", "notification", "location", "jobscheduler",
            "backup", "appwidget", "dreams", "graphicsstats",
            "print", "media_session", "media_router", "restrictions",
            "companiondevice", "shortcut", "launcherapps", "crossprofileapps",
            "slice", "media.projection", "autofill", "imms",
            "statscompanion", "connmetrics", "contexthub",
            "sec_key_att_app_id_provider", "scheduling_policy",
            "telephony.registry", "account", "content", "overlay",
            "settings", "dropbox", "processinfo", "vibrator",
            "consumer_ir", "alarm", "window", "input",
            "package_native", "permission", "dbinfo", "cpuinfo",
            "gfxinfo", "otadexopt", "network_watchlist", "meminfo",
            "user", "activity", "procstats", "pinner",
            "device_identifiers", "batterystats", "appops", "power",
            "recovery", "display", "package", "sensorservice"
        };

        // 1. Get services and dump handles
        appendLog("[*] Attempting to get service handles...");
        for (String svc : services) {
            if (stopRequested.get()) break;
            appendLog("[GETSVC] Requesting '" + svc + "'");
            byte[] reply = nativeBinderGetService(fd, svc);
            if (reply != null && reply.length >= 4) {
                int handle = ((reply[0] & 0xFF) |
                              ((reply[1] & 0xFF) << 8) |
                              ((reply[2] & 0xFF) << 16) |
                              ((reply[3] & 0xFF) << 24));
                appendLog("[GETSVC] '" + svc + "' -> handle=" + handle + " (0x" + Integer.toHexString(handle) + ")");
                dumpToFile(reply, "getsvc_" + svc + ".bin");
                // Store for later interaction
                if (handle != 0) {
                    exploreService(fd, handle, svc);
                }
            } else {
                appendLog("[GETSVC] '" + svc + "' -> no reply or invalid");
            }
        }

        // 2. Also try handle 0 with various codes (context manager)
        int[] codes = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0x10, 0x20, 0xFFFFFFFE};
        for (int code : codes) {
            if (stopRequested.get()) break;
            appendLog("[TX] handle=0 code=0x" + Integer.toHexString(code));
            String result = nativeBinderDumpReply(fd, 0, code, 0, "tx_handle0_code" + code + ".bin");
            appendLog("[TX] " + result);
        }

        // 3. Additional: try sending empty transaction to some handles we got earlier (if any)
        // We'll collect handles from successful gets; but we already explore each service above.

        if (hwbinderFd >= 0) try { ParcelFileDescriptor.adoptFd(hwbinderFd).close(); } catch (Exception e) {}
        if (binderFd >= 0) try { ParcelFileDescriptor.adoptFd(binderFd).close(); } catch (Exception e) {}

        appendLog("========== EXPLORATION COMPLETED ==========");
        appendLog("========================================");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
        finishTest();
    }

    // Explore a specific service handle: send transactions with various codes and dump replies
    private void exploreService(int fd, int handle, String serviceName) {
        appendLog("[EXPLORE] Service '" + serviceName + "' handle=" + handle);
        int[] codes = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        for (int code : codes) {
            if (stopRequested.get()) break;
            String fname = "svc_" + serviceName + "_code" + code + ".bin";
            String result = nativeBinderDumpReply(fd, handle, code, 0, fname);
            appendLog("[EXPLORE] " + serviceName + " code " + code + " -> " + result);
        }
        // Also try oneway flag (TF_ONE_WAY = 0x01)
        for (int code : codes) {
            if (stopRequested.get()) break;
            String fname = "svc_" + serviceName + "_code" + code + "_oneway.bin";
            String result = nativeBinderDumpReply(fd, handle, code, 1, fname);
            appendLog("[EXPLORE] " + serviceName + " code " + code + " (oneway) -> " + result);
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
            appendLog("[DUMP] Failed to save " + filename + ": " + e.getMessage());
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
            File file = new File(dir, "binder_explore_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== Binder Exploration Log ===");
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
            Toast.makeText(MainActivity.this, "Exploration completed", Toast.LENGTH_LONG).show();
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
