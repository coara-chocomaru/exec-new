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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private static final String TARGET_PKG_TZ = "com.qualcomm.qti.qms.service.trustzoneaccess";
    private static final String TARGET_CLS_TZ = "com.qualcomm.qti.qms.service.trustzoneaccess.TZAccessService";

    private TextView tvStatus, tvLog;
    private Button btnStart, btnStop;
    private Handler handler = new Handler(Looper.getMainLooper());
    private StringBuilder logBuilder = new StringBuilder();
    private IMinkSocketFd mTZService;
    private boolean isBound = false;
    private AtomicBoolean isTesting = new AtomicBoolean(false);
    private AtomicBoolean stopRequested = new AtomicBoolean(false);
    private Thread testThread;

    static {
        System.loadLibrary("pocjni");
    }

    public static native ParcelFileDescriptor nativeConnectSocket(IMinkSocketFd tzService, String path, int[] handleArr);
    public static native String nativeReadFile(String path);

    private ServiceConnection tzConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mTZService = IMinkSocketFd.Stub.asInterface(service);
            appendLog("[TZ] Service bound");
            updateStatus("Bound - starting dump");
            enableButtons(false, true);
            stopRequested.set(false);
            testThread = new Thread(() -> executeDump());
            testThread.start();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mTZService = null;
            isBound = false;
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
        appendLog("App started. Press 'Start' to begin.");
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
            intent.setClassName(TARGET_PKG_TZ, TARGET_CLS_TZ);
            boolean ret = bindService(intent, tzConnection, Context.BIND_AUTO_CREATE);
            if (ret) {
                appendLog("Binding service...");
                updateStatus("Binding...");
                isBound = true;
            } else {
                appendLog("bindService returned false");
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

    private void executeDump() {
        appendLog("========================================");
        appendLog("========== Socket Information Dump ==========");

        // 各ソケットと送信データ（プロトコル別）
        dumpFwmarkd();
        dumpDnsProxyd();
        dumpMdnsd();
        dumpLogd();
        dumpNetd();
        dumpGeneric("/dev/socket/tcm", new String[]{"help\n", "status\n", "version\n"});
        dumpGeneric("/dev/socket/location", new String[]{"help\n", "status\n", "version\n"});

        appendLog("========== System Properties Dump ==========");
        dumpSystemProperties();

        appendLog("========== /proc Info Dump ==========");
        dumpProcFiles();

        appendLog("========== DUMP COMPLETED ==========");
        appendLog("========================================");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
        finishTest();
    }

    // ===== fwmarkd 専用 (バイナリプロトコル) =====
    private void dumpFwmarkd() {
        appendLog("[FW] Dumping fwmarkd");
        ParcelFileDescriptor pfd = null;
        try {
            int[] iArr = new int[1];
            pfd = mTZService.a("/dev/socket/fwmarkd", iArr);
            if (pfd == null) {
                appendLog("[FW] Failed to open");
                return;
            }
            java.io.FileDescriptor fd = pfd.getFileDescriptor();
            if (fd == null || !fd.valid()) {
                appendLog("[FW] Invalid FD");
                pfd.close();
                return;
            }

            // SELECT_NETWORK (cmd=6), uid=自身, netId=0, trafficCtrlInfo=0
            ByteBuffer buf = ByteBuffer.allocate(16);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(6);          // cmdId
            buf.putInt(android.os.Process.myUid()); // uid
            buf.putInt(0);          // netId
            buf.putInt(0);          // trafficCtrlInfo

            OutputStream os = new FileOutputStream(fd);
            os.write(buf.array());
            os.flush();

            // 応答は4バイトのエラーコード (int)
            InputStream is = new FileInputStream(fd);
            byte[] resp = new byte[4];
            int read = readBytes(is, resp, 4, 1000);
            if (read == 4) {
                int result = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN).getInt();
                appendLog("[FW] SELECT_NETWORK response: " + result + " (0=success)");
            } else {
                appendLog("[FW] No response or incomplete");
            }

            os.close();
            is.close();
            pfd.close();
        } catch (Exception e) {
            appendLog("[FW] Error: " + e.getMessage());
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
        }
    }

    // ===== dnsproxyd 専用 (DNSクエリ) =====
    private void dumpDnsProxyd() {
        appendLog("[DNS] Dumping dnsproxyd");
        ParcelFileDescriptor pfd = null;
        try {
            int[] iArr = new int[1];
            pfd = mTZService.a("/dev/socket/dnsproxyd", iArr);
            if (pfd == null) {
                appendLog("[DNS] Failed to open");
                return;
            }
            java.io.FileDescriptor fd = pfd.getFileDescriptor();
            if (fd == null || !fd.valid()) {
                appendLog("[DNS] Invalid FD");
                pfd.close();
                return;
            }

            // DNSクエリ: localhost Aレコード (RFC 1035)
            byte[] query = buildDnsQuery("localhost", 1); // TYPE A
            OutputStream os = new FileOutputStream(fd);
            os.write(query);
            os.flush();

            InputStream is = new FileInputStream(fd);
            byte[] resp = new byte[512];
            int read = readBytes(is, resp, 512, 2000);
            if (read > 0) {
                appendLog("[DNS] Response (" + read + " bytes): " + bytesToHex(resp, read));
                // 簡易パース: ヘッダーから応答コードを抽出
                int rcode = resp[3] & 0x0F;
                appendLog("[DNS] RCODE: " + rcode + " (0=no error)");
            } else {
                appendLog("[DNS] No response");
            }

            os.close();
            is.close();
            pfd.close();
        } catch (Exception e) {
            appendLog("[DNS] Error: " + e.getMessage());
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * DNSクエリ（RFC 1035）を構築する。
     * @throws IOException ByteArrayOutputStreamのwriteで発生する可能性（実際には発生しないが、シグネチャに宣言）
     */
    private byte[] buildDnsQuery(String name, int qtype) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // ヘッダー (12 bytes): ID=0x1234, QR=0, Opcode=0, AA=0, TC=0, RD=1, RA=0, Z=0, RCODE=0, QDCOUNT=1
        baos.write(0x12); baos.write(0x34); // ID
        baos.write(0x01); baos.write(0x00); // flags: RD=1
        baos.write(0x00); baos.write(0x01); // QDCOUNT=1
        baos.write(0x00); baos.write(0x00); // ANCOUNT=0
        baos.write(0x00); baos.write(0x00); // NSCOUNT=0
        baos.write(0x00); baos.write(0x00); // ARCOUNT=0

        // QNAME: ラベルエンコード
        for (String label : name.split("\\.")) {
            baos.write(label.length());
            baos.write(label.getBytes(StandardCharsets.US_ASCII));
        }
        baos.write(0); // 終端

        // QTYPE (2 bytes) and QCLASS (2 bytes)
        baos.write((qtype >> 8) & 0xFF); baos.write(qtype & 0xFF);
        baos.write(0x00); baos.write(0x01); // IN

        return baos.toByteArray();
    }

    // ===== mdnsd 専用 (mDNSクエリ) =====
    private void dumpMdnsd() {
        appendLog("[MDNS] Dumping mdnsd");
        ParcelFileDescriptor pfd = null;
        try {
            int[] iArr = new int[1];
            pfd = mTZService.a("/dev/socket/mdnsd", iArr);
            if (pfd == null) {
                appendLog("[MDNS] Failed to open");
                return;
            }
            java.io.FileDescriptor fd = pfd.getFileDescriptor();
            if (fd == null || !fd.valid()) {
                appendLog("[MDNS] Invalid FD");
                pfd.close();
                return;
            }

            // mDNSクエリ: localhost.local (mDNS uses .local)
            byte[] query = buildDnsQuery("localhost.local", 1);
            OutputStream os = new FileOutputStream(fd);
            os.write(query);
            os.flush();

            InputStream is = new FileInputStream(fd);
            byte[] resp = new byte[512];
            int read = readBytes(is, resp, 512, 2000);
            if (read > 0) {
                appendLog("[MDNS] Response (" + read + " bytes): " + bytesToHex(resp, read));
            } else {
                appendLog("[MDNS] No response");
            }

            os.close();
            is.close();
            pfd.close();
        } catch (Exception e) {
            appendLog("[MDNS] Error: " + e.getMessage());
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
        }
    }

    // ===== logd 専用 (読み取り試行) =====
    private void dumpLogd() {
        appendLog("[LOGD] Dumping logd (attempt read)");
        ParcelFileDescriptor pfd = null;
        try {
            int[] iArr = new int[1];
            pfd = mTZService.a("/dev/socket/logd", iArr);
            if (pfd == null) {
                appendLog("[LOGD] Failed to open");
                return;
            }
            java.io.FileDescriptor fd = pfd.getFileDescriptor();
            if (fd == null || !fd.valid()) {
                appendLog("[LOGD] Invalid FD");
                pfd.close();
                return;
            }

            // 何も送信せずに読み取りを試行 (サーバーからデータが来るかも)
            InputStream is = new FileInputStream(fd);
            byte[] buf = new byte[4096];
            int read = readBytes(is, buf, 4096, 1000);
            if (read > 0) {
                appendLog("[LOGD] Read " + read + " bytes: " + new String(buf, 0, read, StandardCharsets.UTF_8));
            } else {
                appendLog("[LOGD] No data (maybe need to send log message first)");
            }

            is.close();
            pfd.close();
        } catch (Exception e) {
            appendLog("[LOGD] Error: " + e.getMessage());
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
        }
    }

    // ===== netd 専用 (テキストコマンド) =====
    private void dumpNetd() {
        dumpGeneric("/dev/socket/netd", new String[]{"help\n", "status\n", "version\n", "interface list\n"});
    }

    // ===== 汎用テキストコマンド =====
    private void dumpGeneric(String path, String[] commands) {
        appendLog("[GEN] Dumping " + path);
        ParcelFileDescriptor pfd = null;
        try {
            int[] iArr = new int[1];
            pfd = mTZService.a(path, iArr);
            if (pfd == null) {
                appendLog("[GEN] Failed to open " + path);
                return;
            }
            java.io.FileDescriptor fd = pfd.getFileDescriptor();
            if (fd == null || !fd.valid()) {
                appendLog("[GEN] Invalid FD");
                pfd.close();
                return;
            }

            OutputStream os = new FileOutputStream(fd);
            InputStream is = new FileInputStream(fd);

            for (String cmd : commands) {
                if (stopRequested.get()) break;
                appendLog("[GEN] CMD: " + cmd.trim());
                try {
                    os.write(cmd.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    byte[] buf = new byte[2048];
                    int read = readBytes(is, buf, 2048, 1000);
                    if (read > 0) {
                        String resp = new String(buf, 0, read, StandardCharsets.UTF_8);
                        appendLog("[GEN] Response: " + resp.replace("\n", "\\n").replace("\r", "\\r"));
                    } else {
                        appendLog("[GEN] No response");
                    }
                } catch (Exception e) {
                    appendLog("[GEN] CMD error: " + e.getMessage());
                }
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }

            os.close();
            is.close();
            pfd.close();
        } catch (Exception e) {
            appendLog("[GEN] Error: " + e.getMessage());
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
        }
    }

    // ===== システムプロパティ =====
    private void dumpSystemProperties() {
        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties");
            Method getMethod = spClass.getMethod("get", String.class);
            String[] props = {
                "ro.build.version.release", "ro.product.model", "ro.product.manufacturer",
                "ro.build.date", "persist.sys.timezone", "persist.sys.language",
                "persist.sys.country", "sys.retaildemo.enabled", "ro.boot.hardware",
                "ro.boot.serialno", "ro.build.display.id", "ro.build.version.sdk"
            };
            for (String prop : props) {
                if (stopRequested.get()) break;
                String value = (String) getMethod.invoke(null, prop);
                appendLog("[PROP] " + prop + " = " + (value != null ? value : "(null)"));
            }
        } catch (Exception e) {
            appendLog("[PROP] Reflection error: " + e.getMessage());
        }
    }

    // ===== /proc ファイル =====
    private void dumpProcFiles() {
        String[] files = {
            "/proc/version", "/proc/self/status", "/proc/self/cmdline",
            "/proc/meminfo", "/proc/cpuinfo", "/proc/uptime", "/proc/loadavg"
        };
        for (String f : files) {
            if (stopRequested.get()) break;
            try {
                String content = nativeReadFile(f);
                if (content != null && !content.isEmpty()) {
                    appendLog("[PROC] " + f + ":\n" + content);
                } else {
                    appendLog("[PROC] " + f + " -> (empty or inaccessible)");
                }
            } catch (Exception e) {
                appendLog("[PROC] " + f + " error: " + e.getMessage());
            }
        }
    }

    // ===== ヘルパー: タイムアウト付きバイト読み取り =====
    private int readBytes(InputStream is, byte[] buffer, int maxLen, int timeoutMs) {
        int total = 0;
        long start = System.currentTimeMillis();
        try {
            while (total < maxLen && System.currentTimeMillis() - start < timeoutMs) {
                if (is.available() > 0) {
                    int n = is.read(buffer, total, maxLen - total);
                    if (n <= 0) break;
                    total += n;
                } else {
                    Thread.sleep(20);
                }
            }
            return total;
        } catch (Exception e) {
            return -1;
        }
    }

    private String bytesToHex(byte[] bytes, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && i < 64; i++) {
            sb.append(String.format("%02x ", bytes[i]));
        }
        if (len > 64) sb.append("...");
        return sb.toString();
    }

    // ===== UIログ =====
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
            File file = new File(dir, "socket_dump_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== Socket Dump Log ===");
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
            Toast.makeText(MainActivity.this, "Dump completed", Toast.LENGTH_LONG).show();
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
        if (isBound) unbindService(tzConnection);
        saveLog();
    }
}
