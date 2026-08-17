package com.example.tzpoc;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import com.qualcomm.qti.qms.service.connectionsecurity.core.*;
import com.qualcomm.qti.qms.service.connectionsecurity.BuildUtils;

public class MainActivity extends AppCompatActivity {
    private TextView tvLog;
    private Button btnStart;
    private StringBuilder log = new StringBuilder();
    private File logFile;

    static { System.loadLibrary("service-api"); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tvLog = findViewById(R.id.tv_log);
        btnStart = findViewById(R.id.btn_start);
        logFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "poc_multi_angle.txt");
        checkPerms();
        btnStart.setOnClickListener(v -> executeMultiAngleTest());
    }

    private void checkPerms() {
        if (Build.VERSION.SDK_INT >= 23 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    private void executeMultiAngleTest() {
        btnStart.setEnabled(false);
        log.append("=== TZ PoC Multi-Angle Analysis ===\n");
        log.append("Time: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n\n");

        // Angle 1: Java JNI Wrappers
        log.append("[Angle 1] Java JNI Direct Calls\n");
        testServiceManager();
        testBuildUtils();
        testTloc();
        testRtic();
        testWifi();

        // Angle 2: C-Level dlsym Testing (Native side already handles it, but we log)
        log.append("\n[Angle 2] C-Level dlsym handled by native bridge.\n");
        log.append("All native calls are routed via dlsym to ensure symbol resolution.\n");

        // Angle 3: AIDL Simulation (Mock)
        log.append("\n[Angle 3] AIDL Simulation (System Binding Test)\n");
        log.append("Note: AIDL requires a running service. Testing dummy bind.\n");
        try {
            android.content.Intent intent = new android.content.Intent();
            intent.setPackage("com.qualcomm.qti.qms.service.connectionsecurity");
            android.content.ComponentName name = startService(intent);
            log.append("startService attempt: ").append(name != null ? "Service found" : "Service not found (expected)").append("\n");
        } catch (Throwable t) {
            log.append("AIDL Test (expected exception): ").append(t.getMessage()).append("\n");
        }

        log.append("\n=== Logging Completed ===\n");
        tvLog.setText(log.toString());
        saveLog();
        btnStart.setEnabled(true);
    }

    private void testServiceManager() {
        ServiceManagerImpl sm = new ServiceManagerImpl();
        try { sm.nativeInit(); log.append("  ServiceManager.nativeInit: OK\n"); } catch (Throwable t) { log.append("  ServiceManager.nativeInit: ").append(t).append("\n"); }
        try { sm.nativeDestroy(); log.append("  ServiceManager.nativeDestroy: OK\n"); } catch (Throwable t) { log.append("  ServiceManager.nativeDestroy: ").append(t).append("\n"); }
    }

    private void testBuildUtils() {
        BuildUtils bu = new BuildUtils();
        try { bu.nativeInit(); log.append("  BuildUtils.nativeInit: OK\n"); } catch (Throwable t) { log.append("  BuildUtils.nativeInit: ").append(t).append("\n"); }
        try { String f = bu.nativeGetBuildFlavor(); log.append("  BuildUtils.flavor: ").append(f).append("\n"); } catch (Throwable t) { log.append("  BuildUtils.flavor: ").append(t).append("\n"); }
        try { bu.nativeDestroy(); log.append("  BuildUtils.nativeDestroy: OK\n"); } catch (Throwable t) { log.append("  BuildUtils.nativeDestroy: ").append(t).append("\n"); }
    }

    private void testTloc() {
        TlocServiceImpl tloc = new TlocServiceImpl();
        int[] res = new int[1];
        try { int r = tloc.tlocWarmUp("test", 4, res); log.append("  tlocWarmUp: ret=").append(r).append(", out=").append(res[0]).append("\n"); } catch (Throwable t) { log.append("  tlocWarmUp: ").append(t).append("\n"); }
        int[] outLen = new int[1];
        byte[] data = new byte[10];
        try { int r = tloc.getTrustedLocation(data, 10, new int[1], null, 0, outLen); log.append("  getTrustedLocation: ret=").append(r).append(", len=").append(outLen[0]).append("\n"); } catch (Throwable t) { log.append("  getTrustedLocation: ").append(t).append("\n"); }
        // Boundary test (Buffer overflow attempt)
        try { int r = tloc.getTrustedLocation(data, Integer.MAX_VALUE, new int[1], null, 0, outLen); log.append("  BOUNDARY_TEST (MAX): ret=").append(r).append("\n"); } catch (Throwable t) { log.append("  BOUNDARY_TEST: ").append(t).append("\n"); }
    }

    private void testRtic() {
        RticReportImpl rtic = new RticReportImpl();
        int[] out = new int[1];
        try { int r = rtic.getRticData(new byte[10], 10, 123L, new int[1], null, 0, out, 1); log.append("  getRticData: ret=").append(r).append(", out=").append(out[0]).append("\n"); } catch (Throwable t) { log.append("  getRticData: ").append(t).append("\n"); }
    }

    private void testWifi() {
        WifiAuditorServiceImpl wifi = new WifiAuditorServiceImpl();
        long h = 0;
        try { h = wifi.nativeCreate("dummy"); log.append("  wifi.nativeCreate: ").append(h).append("\n"); } catch (Throwable t) { log.append("  wifi.nativeCreate: ").append(t).append("\n"); }
        if (h != 0) {
            try { wifi.nativeStartScan(); log.append("  wifi.nativeStartScan: OK\n"); } catch (Throwable t) { log.append("  wifi.nativeStartScan: ").append(t).append("\n"); }
            try { wifi.nativeDestroy(h); log.append("  wifi.nativeDestroy: OK\n"); } catch (Throwable t) { log.append("  wifi.nativeDestroy: ").append(t).append("\n"); }
        }
        try { wifi.nativeRegisterClient("testClient"); log.append("  wifi.nativeRegisterClient: OK\n"); } catch (Throwable t) { log.append("  wifi.nativeRegisterClient: ").append(t).append("\n"); }
        try { wifi.nativeStartClientScan("testClient"); log.append("  wifi.nativeStartClientScan: OK\n"); } catch (Throwable t) { log.append("  wifi.nativeStartClientScan: ").append(t).append("\n"); }
        try { wifi.nativeUpdateModel("model"); log.append("  wifi.nativeUpdateModel: OK\n"); } catch (Throwable t) { log.append("  wifi.nativeUpdateModel: ").append(t).append("\n"); }
    }

    private void saveLog() {
        try { FileOutputStream fos = new FileOutputStream(logFile, true); OutputStreamWriter w = new OutputStreamWriter(fos); w.write(log.toString()); w.close(); fos.close(); } catch (Exception e) { e.printStackTrace(); }
    }
}
