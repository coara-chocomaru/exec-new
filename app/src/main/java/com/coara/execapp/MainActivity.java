package com.coara.execapp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.database.Cursor;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private Process currentProcess;
    private File selectedBinary;
    private String executionPath;
    private boolean isDeviceOwner;
    private boolean shizukuGranted;
    private Shizuku.OnRequestPermissionResultListener requestPermissionResultListener;
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int FILE_PICKER_REQUEST_CODE = 1002;
    private static final int SHIZUKU_REQUEST_CODE = 1003;
    private static final String PREF_NAME = "execapp_prefs";
    private static final String KEY_SETTINGS_CHECKED = "write_settings_checked_once";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().getDecorView().setBackgroundColor(Color.WHITE);
        ScrollView scrollView = findViewById(R.id.scroll_view);
        TextView resultView = findViewById(R.id.result_view);
        if (scrollView != null) scrollView.setBackgroundColor(Color.WHITE);
        if (resultView != null) {
            resultView.setBackgroundColor(Color.WHITE);
            resultView.setTextColor(Color.BLACK);
        }

        EditText commandInput = findViewById(R.id.command_input);
        Button executeButton = findViewById(R.id.execute_button);
        Button pickBinaryButton = findViewById(R.id.pick_binary_button);
        Button clearBinaryButton = findViewById(R.id.clear_binary_button);
        Button stopButton = findViewById(R.id.stop_button);
        Button keyboardButton = findViewById(R.id.keyboard_button);

        checkPermissions();

        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        isDeviceOwner = dpm != null && dpm.isDeviceOwnerApp(getPackageName());

        shizukuGranted = false;
        requestPermissionResultListener = (requestCode, grantResult) -> {
            if (requestCode == SHIZUKU_REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
                shizukuGranted = true;
                runOnUiThread(() -> Toast.makeText(this, "Shizuku権限が付与されました", Toast.LENGTH_SHORT).show());
            }
        };
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener);

        updateShizukuStatus();
        handleWriteSettingsPermission();

        pickBinaryButton.setOnClickListener(view -> launchFilePicker());

        clearBinaryButton.setOnClickListener(view -> {
            if (shizukuGranted && executionPath != null && executionPath.startsWith("/data/local/tmp/")) {
                runSilentShizukuCommand("rm -f " + executionPath);
            }
            selectedBinary = null;
            executionPath = null;
            Toast.makeText(this, "バイナリが解除されました。", Toast.LENGTH_SHORT).show();
        });

        executeButton.setOnClickListener(view -> {
            String command = commandInput.getText().toString().trim();
            if (command.isEmpty() && executionPath == null) {
                Toast.makeText(this, "コマンドまたはバイナリを指定してください。", Toast.LENGTH_SHORT).show();
                return;
            }
            if (executionPath != null) {
                command = executionPath + " " + command;
            }
            executeCommand(command, resultView);
        });

        stopButton.setOnClickListener(view -> stopCurrentProcess(resultView));

        keyboardButton.setOnClickListener(view -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
                commandInput.requestFocus();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateShizukuStatus();
    }

    private void updateShizukuStatus() {
        boolean binderAlive = Shizuku.pingBinder();
        boolean preV11 = Shizuku.isPreV11();
        if (binderAlive && !preV11) {
            shizukuGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            if (!shizukuGranted) {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
            }
        } else {
            shizukuGranted = false;
        }
    }

    private void handleWriteSettingsPermission() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        boolean alreadyChecked = prefs.getBoolean(KEY_SETTINGS_CHECKED, false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this) && !alreadyChecked) {
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("システム設定変更の許可");
                builder.setMessage("settings put/get コマンドでシステム設定を変更するには\nWRITE_SETTINGS権限が必要です。\n\n今すぐ許可しますか？\n（許可しない場合、Shizukuが必要です）");
                builder.setPositiveButton("許可する", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    prefs.edit().putBoolean(KEY_SETTINGS_CHECKED, true).apply();
                });
                builder.setNegativeButton("後で", (dialog, which) -> prefs.edit().putBoolean(KEY_SETTINGS_CHECKED, true).apply());
                builder.setCancelable(true);
                builder.show();
            }
        }
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int i = 0; i < permissions.length; i++) {
                Toast.makeText(this, permissions[i] + (grantResults[i] == PackageManager.PERMISSION_GRANTED ? " 権限が許可されました" : " 権限が拒否されました"), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void launchFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, FILE_PICKER_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_PICKER_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                selectedBinary = copyFileToInternalStorage(uri);
                if (selectedBinary != null && selectedBinary.setExecutable(true)) {
                    if (shizukuGranted && Shizuku.pingBinder()) {
                        handleShizukuBinaryCopy();
                    } else {
                        executionPath = selectedBinary.getAbsolutePath();
                        Toast.makeText(this, "バイナリが選択され、実行権限が付与されました: " + executionPath, Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(this, "バイナリ選択または実行権限付与に失敗しました。", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void handleShizukuBinaryCopy() {
        String filename = selectedBinary.getName();
        executionPath = "/data/local/tmp/" + filename;

        String cmd = "cp -f \"" + selectedBinary.getAbsolutePath() + "\" \"" + executionPath + "\" && chmod 777 \"" + executionPath + "\"";
        boolean success = runSilentShizukuCommand(cmd);

        if (success) {
            Toast.makeText(this, "バイナリ選択完了: " + executionPath, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "エラー", Toast.LENGTH_LONG).show();
            executionPath = selectedBinary.getAbsolutePath();
        }
    }

    @Nullable
    private File copyFileToInternalStorage(Uri uri) {
        File directory = new File(getExternalFilesDir(null), "binaries");
        if (!directory.exists() && !directory.mkdirs()) {
            Toast.makeText(this, "ディレクトリ作成に失敗しました。", Toast.LENGTH_SHORT).show();
            return null;
        }

        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            if (inputStream == null) return null;

            String fileName = getFileName(uri);
            File destFile = new File(directory, fileName);
            try (OutputStream outputStream = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                }
            }
            return destFile;
        } catch (IOException e) {
            Toast.makeText(this, "ファイルのコピーに失敗しました: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return null;
        }
    }

    private String getFileName(@NonNull Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    private boolean runSilentShizukuCommand(String command) {
        try {
            Class<?> clazz = Class.forName("rikka.shizuku.Shizuku");
            java.lang.reflect.Method method = clazz.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            method.setAccessible(true);
            Process process = (Process) method.invoke(null, new String[]{"/system/bin/sh", "-c", command}, null, null);

            StringBuilder error = new StringBuilder();
            try (BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = errReader.readLine()) != null) {
                    error.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0 && error.length() > 0) {
                runOnUiThread(() -> Toast.makeText(this, "Shizukuコマンド失敗: " + error.toString().trim(), Toast.LENGTH_LONG).show());
            }
            return exitCode == 0;
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Shizukuコマンド実行エラー: " + e.getMessage(), Toast.LENGTH_LONG).show());
            return false;
        }
    }

    private void executeCommand(String command, @NonNull TextView resultView) {
        resultView.setText("");

        String trimmed = command.trim();
        if (trimmed.startsWith("settings ")) {
            if (shizukuGranted && Shizuku.pingBinder()) {
            } else {
                String[] parts = trimmed.split("\\s+");
                String action, category, key;
                String value = null;
                if (parts.length >= 4 && ("put".equals(parts[1]) || "get".equals(parts[1]))) {
                    action = parts[1];
                    category = parts[2];
                    key = parts[3];
                    if ("put".equals(action) && parts.length >= 5) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 4; i < parts.length; i++) {
                            sb.append(parts[i]);
                            if (i < parts.length - 1) sb.append(" ");
                        }
                        value = sb.toString();
                    }
                } else if (parts.length >= 3) {
                    action = "put";
                    category = parts[1];
                    key = parts[2];
                    if (parts.length >= 4) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 3; i < parts.length; i++) {
                            sb.append(parts[i]);
                            if (i < parts.length - 1) sb.append(" ");
                        }
                        value = sb.toString();
                    }
                } else {
                    resultView.append("ERROR: settingsコマンドの形式が不正です\n");
                    return;
                }

                DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
                ComponentName admin = new ComponentName(this, AppDeviceAdminReceiver.class);
                String resultText;

                if ("put".equals(action) && value != null) {
                    try {
                        if (isDeviceOwner && dpm != null) {
                            if ("global".equals(category)) dpm.setGlobalSetting(admin, key, value);
                            else if ("secure".equals(category)) dpm.setSecureSetting(admin, key, value);
                            else if ("system".equals(category)) dpm.setSystemSetting(admin, key, value);
                            resultText = "Device Ownerで設定変更完了: " + category + " " + key + " = " + value;
                        } else {
                            ContentResolver cr = getContentResolver();
                            boolean success = false;
                            if ("system".equals(category)) success = Settings.System.putString(cr, key, value);
                            else if ("global".equals(category)) success = Settings.Global.putString(cr, key, value);
                            else if ("secure".equals(category)) success = Settings.Secure.putString(cr, key, value);
                            resultText = success ? "ContentResolverで設定変更完了: " + category + " " + key + " = " + value : "変更失敗（WRITE_SETTINGS権限が不足しています）";
                        }
                    } catch (Exception e) {
                        resultText = "ERROR: " + e.getMessage();
                    }
                } else if ("get".equals(action)) {
                    String val = null;
                    try {
                        ContentResolver cr = getContentResolver();
                        if ("system".equals(category)) val = Settings.System.getString(cr, key);
                        else if ("global".equals(category)) val = Settings.Global.getString(cr, key);
                        else if ("secure".equals(category)) val = Settings.Secure.getString(cr, key);
                        resultText = "取得結果: " + category + " " + key + " = " + (val != null ? val : "(null)");
                    } catch (Exception e) {
                        resultText = "ERROR: " + e.getMessage();
                    }
                } else {
                    resultText = "ERROR: 未対応のsettingsコマンドです";
                }
                resultView.append(resultText + "\n");
                saveLogToFile(command, resultText);
                return;
            }
        }

        try {
            Process process;
            StringBuilder output = new StringBuilder();

            if (shizukuGranted && Shizuku.pingBinder()) {
                try {
                    Class<?> clazz = Class.forName("rikka.shizuku.Shizuku");
                    java.lang.reflect.Method method = clazz.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                    method.setAccessible(true);
                    process = (Process) method.invoke(null, new String[]{"/system/bin/sh", "-c", command}, null, null);
                    resultView.append("INFO: Shizukuで実行\n");
                    output.append("INFO: Shizukuで実行\n");
                } catch (Exception reflectionEx) {
                    resultView.append("WARNING: Shizuku reflection失敗 → 通常アプリ権限で実行します\n");
                    output.append("WARNING: Shizuku reflection失敗 → 通常アプリ権限で実行します\n");
                    ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command);
                    process = pb.start();
                }
            } else {
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command);
                process = pb.start();
            }
            currentProcess = process;

            Executors.newSingleThreadExecutor().submit(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(currentProcess.getInputStream()));
                     BufferedReader errorReader = new BufferedReader(new InputStreamReader(currentProcess.getErrorStream()))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                        final String finalLine = line;
                        runOnUiThread(() -> resultView.append(finalLine + "\n"));
                    }
                    while ((line = errorReader.readLine()) != null) {
                        output.append("ERROR: ").append(line).append("\n");
                        final String finalErrorLine = line;
                        runOnUiThread(() -> resultView.append("ERROR: " + finalErrorLine + "\n"));
                    }

                    int exitCode = currentProcess.waitFor();
                    output.append("INFO: プロセス終了 (exit code: ").append(exitCode).append(")\n");
                    runOnUiThread(() -> resultView.append("INFO: プロセス終了 (exit code: " + exitCode + ")\n"));

                    saveLogToFile(command, output.toString());

                } catch (IOException | InterruptedException e) {
                    runOnUiThread(() -> resultView.append("ERROR: " + e.getMessage() + "\n"));
                }
            });
        } catch (Exception e) {
            resultView.setText("ERROR: " + e.getMessage());
        }
    }

    private void stopCurrentProcess(@NonNull TextView resultView) {
        if (currentProcess == null || !currentProcess.isAlive()) {
            Toast.makeText(this, "実行中のプロセスはありません。", Toast.LENGTH_SHORT).show();
            return;
        }

        resultView.append("INFO: 強制停止を実行しています...\n");

        try {
            currentProcess.destroy();
            Thread.sleep(500);

            if (currentProcess.isAlive()) {
                currentProcess.destroyForcibly();
                Thread.sleep(500);
            }

            if (currentProcess.isAlive()) {
                resultView.append("WARNING: プロセスが完全に停止しない場合があります\n");
            } else {
                resultView.append("INFO: プロセスを強制停止しました\n");
            }
        } catch (Exception e) {
            resultView.append("ERROR: 停止中に例外発生: " + e.getMessage() + "\n");
        }
    }

    private void saveLogToFile(String command, String logContent) {
        File directory = new File(getExternalFilesDir(null), "command_logs");
        if (!directory.exists()) directory.mkdirs();

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = command.replaceAll("[^a-zA-Z0-9]", "_") + "_" + timeStamp + ".txt";
        File logFile = new File(directory, fileName);

        try (FileOutputStream fos = new FileOutputStream(logFile);
             OutputStreamWriter writer = new OutputStreamWriter(fos)) {
            writer.write(logContent);
            runOnUiThread(() -> Toast.makeText(this, "ログが保存されました: " + logFile.getAbsolutePath(), Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "ログ保存中にエラー: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentProcess != null && currentProcess.isAlive()) {
            currentProcess.destroyForcibly();
        }
        if (requestPermissionResultListener != null) {
            Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener);
        }
    }
}
