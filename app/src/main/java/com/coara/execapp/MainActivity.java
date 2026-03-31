package com.coara.execapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private static final int FILE_PICKER_REQUEST_CODE = 1002;
    private static final int SHIZUKU_REQUEST_CODE = 1003;
    private static final String PREF_NAME = "execapp_prefs";
    private static final String KEY_SETTINGS_CHECKED = "write_settings_checked_once";

    private final Object processLock = new Object();

    private Process currentProcess;
    private File selectedBinary;
    private String executionPath;
    private volatile boolean isDeviceOwner;
    private volatile boolean shizukuGranted;
    private volatile boolean shizukuPermissionRequested;
    private volatile boolean currentExecutionUsesShizuku;
    private volatile boolean stopRequested;

    private ExecutorService commandExecutor;
    private ExecutorService streamExecutor;

    private Shizuku.OnRequestPermissionResultListener requestPermissionResultListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().getDecorView().setBackgroundColor(Color.WHITE);

        ScrollView scrollView = findViewById(R.id.scroll_view);
        TextView resultView = findViewById(R.id.result_view);
        EditText commandInput = findViewById(R.id.command_input);
        Button executeButton = findViewById(R.id.execute_button);
        Button pickBinaryButton = findViewById(R.id.pick_binary_button);
        Button clearBinaryButton = findViewById(R.id.clear_binary_button);
        Button stopButton = findViewById(R.id.stop_button);
        Button keyboardButton = findViewById(R.id.keyboard_button);

        if (scrollView != null) {
            scrollView.setBackgroundColor(Color.WHITE);
        }
        if (resultView != null) {
            resultView.setBackgroundColor(Color.WHITE);
            resultView.setTextColor(Color.BLACK);
        }

        commandExecutor = Executors.newSingleThreadExecutor();
        streamExecutor = Executors.newCachedThreadPool();

        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        isDeviceOwner = dpm != null && dpm.isDeviceOwnerApp(getPackageName());

        requestPermissionResultListener = (requestCode, grantResult) -> {
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                shizukuPermissionRequested = false;
                shizukuGranted = grantResult == PackageManager.PERMISSION_GRANTED && isShizukuReady();
                runOnUiThread(() -> {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(this, "Shizuku権限が付与されました", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Shizuku権限が拒否されました", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        };
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener);

        updateShizukuStatus();
        handleWriteSettingsPermission();

        pickBinaryButton.setOnClickListener(view -> launchFilePicker());

        clearBinaryButton.setOnClickListener(view -> clearSelectedBinary());

        executeButton.setOnClickListener(view -> {
            String command = commandInput.getText().toString().trim();
            executeCommand(command, resultView);
        });

        stopButton.setOnClickListener(view -> stopCurrentExecution(resultView));

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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCurrentExecutionSilently();
        if (commandExecutor != null) {
            commandExecutor.shutdownNow();
        }
        if (streamExecutor != null) {
            streamExecutor.shutdownNow();
        }
        if (requestPermissionResultListener != null) {
            Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener);
        }
    }

    private boolean isShizukuReady() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isShizukuUsable() {
        try {
            return isShizukuReady() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void updateShizukuStatus() {
        boolean usable = isShizukuUsable();
        shizukuGranted = usable;

        if (!usable && isShizukuReady() && !shizukuPermissionRequested) {
            try {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    shizukuPermissionRequested = true;
                    Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
                }
            } catch (Throwable t) {
                shizukuGranted = false;
            }
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

    private void clearSelectedBinary() {
        File fileToDelete;
        synchronized (processLock) {
            fileToDelete = selectedBinary;
            selectedBinary = null;
            executionPath = null;
        }

        if (fileToDelete != null && fileToDelete.exists()) {
            fileToDelete.delete();
        }

        Toast.makeText(this, "バイナリが解除されました。", Toast.LENGTH_SHORT).show();
    }

    private void launchFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, FILE_PICKER_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_PICKER_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                File copied = copyFileToInternalStorage(uri);
                if (copied != null) {
                    boolean executable = copied.setExecutable(true, true);
                    if (!executable) {
                        executable = copied.setExecutable(true);
                    }
                    synchronized (processLock) {
                        selectedBinary = copied;
                        executionPath = copied.getAbsolutePath();
                    }
                    if (executable) {
                        Toast.makeText(this, "バイナリが選択され、実行権限が付与されました: " + executionPath, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "バイナリをコピーしましたが、実行権限の付与に失敗しました: " + executionPath, Toast.LENGTH_LONG).show();
                    }
                } else {
                    Toast.makeText(this, "バイナリ選択またはコピーに失敗しました。", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    @Nullable
    private File copyFileToInternalStorage(Uri uri) {
        File directory = new File(getFilesDir(), "binaries");
        if (!directory.exists() && !directory.mkdirs()) {
            Toast.makeText(this, "ディレクトリ作成に失敗しました。", Toast.LENGTH_SHORT).show();
            return null;
        }

        String fileName = sanitizeFileName(getFileName(uri));
        if (fileName == null || fileName.trim().isEmpty()) {
            fileName = "binary_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".bin";
        }

        File destFile = new File(directory, fileName);

        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                return null;
            }
            try (OutputStream outputStream = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, length);
                }
                outputStream.flush();
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
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        result = cursor.getString(index);
                    }
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) {
                    result = result.substring(cut + 1);
                }
            }
        }
        if (result == null || result.trim().isEmpty()) {
            result = "binary_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".bin";
        }
        return result;
    }

    private String sanitizeFileName(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = value.replaceAll("[\\\\/:*?\"<>|]+", "_").replaceAll("\\s+", "_");
        if (sanitized.length() > 120) {
            sanitized = sanitized.substring(0, 120);
        }
        return sanitized;
    }

    private void executeCommand(String command, @NonNull TextView resultView) {
        updateShizukuStatus();

        String trimmed = command == null ? "" : command.trim();
        if (trimmed.isEmpty() && executionPath == null) {
            Toast.makeText(this, "コマンドまたはバイナリを指定してください。", Toast.LENGTH_SHORT).show();
            return;
        }

        if (trimmed.equals("settings") || trimmed.startsWith("settings ")) {
            handleSettingsCommand(trimmed, resultView);
            return;
        }

        String finalCommand = trimmed;
        String binaryPath;
        synchronized (processLock) {
            binaryPath = executionPath;
        }

        if (binaryPath != null) {
            if (finalCommand.isEmpty()) {
                finalCommand = binaryPath;
            } else {
                finalCommand = binaryPath + " " + finalCommand;
            }
        }

        if (finalCommand.isEmpty()) {
            Toast.makeText(this, "コマンドまたはバイナリを指定してください。", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean useShizuku = isShizukuUsable();
        if (binaryPath != null && binaryPath.startsWith(getFilesDir().getAbsolutePath())) {
            useShizuku = false;
        }

        startShellExecution(finalCommand, resultView, useShizuku);
    }

    private boolean handleSettingsCommand(String command, @NonNull TextView resultView) {
        String[] parts = command.trim().split("\\s+");
        if (parts.length < 3) {
            appendResult(resultView, "ERROR: settingsコマンドの形式が不正です\n");
            saveLogToFile(command, "ERROR: settingsコマンドの形式が不正です\n");
            return true;
        }

        String action;
        String category;
        String key;
        String value = null;

        if (parts.length >= 4 && ("put".equals(parts[1]) || "get".equals(parts[1]))) {
            action = parts[1];
            category = parts[2];
            key = parts[3];
            if ("put".equals(action) && parts.length >= 5) {
                value = joinParts(parts, 4);
            }
        } else {
            action = "put";
            category = parts[1];
            key = parts[2];
            if (parts.length >= 4) {
                value = joinParts(parts, 3);
            }
        }

        String resultText;
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, AppDeviceAdminReceiver.class);

            if ("put".equals(action)) {
                if (value == null) {
                    resultText = "ERROR: settings put の値がありません";
                } else {
                    boolean handled = false;
                    if (isDeviceOwner && dpm != null) {
                        if ("global".equals(category)) {
                            dpm.setGlobalSetting(admin, key, value);
                            handled = true;
                        } else if ("secure".equals(category)) {
                            dpm.setSecureSetting(admin, key, value);
                            handled = true;
                        } else if ("system".equals(category)) {
                            dpm.setSystemSetting(admin, key, value);
                            handled = true;
                        }
                    }

                    if (!handled) {
                        ContentResolver cr = getContentResolver();
                        boolean success = false;
                        if ("system".equals(category)) {
                            success = Settings.System.putString(cr, key, value);
                        } else if ("global".equals(category)) {
                            success = Settings.Global.putString(cr, key, value);
                        } else if ("secure".equals(category)) {
                            success = Settings.Secure.putString(cr, key, value);
                        }

                        if (success) {
                            handled = true;
                        }
                    }

                    resultText = handled
                            ? "設定変更完了: " + category + " " + key + " = " + value
                            : "変更失敗（権限またはカテゴリが不足しています）";
                }
            } else if ("get".equals(action)) {
                String val = null;
                ContentResolver cr = getContentResolver();
                if ("system".equals(category)) {
                    val = Settings.System.getString(cr, key);
                } else if ("global".equals(category)) {
                    val = Settings.Global.getString(cr, key);
                } else if ("secure".equals(category)) {
                    val = Settings.Secure.getString(cr, key);
                }
                resultText = "取得結果: " + category + " " + key + " = " + (val != null ? val : "(null)");
            } else {
                resultText = "ERROR: 未対応のsettingsコマンドです";
            }
        } catch (Exception e) {
            resultText = "ERROR: " + e.getMessage();
        }

        appendResult(resultView, resultText + "\n");
        saveLogToFile(command, resultText);
        return true;
    }

    private String joinParts(String[] parts, int startIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = startIndex; i < parts.length; i++) {
            if (i > startIndex) {
                sb.append(' ');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private void startShellExecution(String command, @NonNull TextView resultView, boolean useShizuku) {
        synchronized (processLock) {
            if (currentProcess != null && currentProcess.isAlive()) {
                Toast.makeText(this, "すでに実行中の処理があります。STOPで終了してください。", Toast.LENGTH_SHORT).show();
                return;
            }
            stopRequested = false;
        }

        Process process;
        boolean actualUseShizuku = useShizuku;

        try {
            process = useShizuku ? createShizukuProcess(command) : createLocalProcess(command);
        } catch (Exception e) {
            if (useShizuku) {
                try {
                    actualUseShizuku = false;
                    appendResult(resultView, "WARNING: Shizuku実行に失敗したため通常実行へ切り替えます\n");
                    process = createLocalProcess(command);
                } catch (Exception fallbackEx) {
                    appendResult(resultView, "ERROR: " + fallbackEx.getMessage() + "\n");
                    saveLogToFile(command, "ERROR: " + fallbackEx.getMessage());
                    synchronized (processLock) {
                        currentProcess = null;
                        currentExecutionUsesShizuku = false;
                        stopRequested = false;
                    }
                    return;
                }
            } else {
                appendResult(resultView, "ERROR: " + e.getMessage() + "\n");
                saveLogToFile(command, "ERROR: " + e.getMessage());
                synchronized (processLock) {
                    currentProcess = null;
                    currentExecutionUsesShizuku = false;
                    stopRequested = false;
                }
                return;
            }
        }

        synchronized (processLock) {
            currentProcess = process;
            currentExecutionUsesShizuku = actualUseShizuku;
        }

        StringBuffer output = new StringBuffer();
        String header = actualUseShizuku ? "INFO: Shizukuで実行\n" : "INFO: 通常実行\n";
        output.append(header);
        appendResult(resultView, header);

        Future<?> stdoutFuture = streamExecutor.submit(() -> readStream(process.getInputStream(), resultView, output, false));
        Future<?> stderrFuture = streamExecutor.submit(() -> readStream(process.getErrorStream(), resultView, output, true));

        commandExecutor.submit(() -> {
            int exitCode = -1;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                output.append("ERROR: ").append(e.getMessage()).append('\n');
                appendResult(resultView, "ERROR: " + e.getMessage() + "\n");
            } catch (Exception e) {
                output.append("ERROR: ").append(e.getMessage()).append('\n');
                appendResult(resultView, "ERROR: " + e.getMessage() + "\n");
            }

            try {
                stdoutFuture.get();
            } catch (Exception ignored) {
            }
            try {
                stderrFuture.get();
            } catch (Exception ignored) {
            }

            synchronized (processLock) {
                if (currentProcess == process) {
                    currentProcess = null;
                    currentExecutionUsesShizuku = false;
                }
            }

            if (stopRequested) {
                String endLine = "INFO: 停止処理後に終了しました\n";
                output.append(endLine);
                appendResult(resultView, endLine);
            } else {
                String endLine = "INFO: プロセス終了 (exit code: " + exitCode + ")\n";
                output.append(endLine);
                appendResult(resultView, endLine);
            }

            saveLogToFile(command, output.toString());

            synchronized (processLock) {
                stopRequested = false;
            }
        });
    }

    private Process createLocalProcess(String command) throws IOException {
        return new ProcessBuilder("/system/bin/sh", "-c", command).start();
    }

    private Process createShizukuProcess(String command) throws Exception {
        Class<?> clazz = Class.forName("rikka.shizuku.Shizuku");
        Method method = clazz.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        method.setAccessible(true);
        return (Process) method.invoke(null, (Object) new String[]{"/system/bin/sh", "-c", command}, null, null);
    }

    private void readStream(InputStream inputStream, @NonNull TextView resultView, StringBuffer output, boolean isError) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String text = isError ? "ERROR: " + line : line;
                output.append(text).append('\n');
                appendResult(resultView, text + "\n");
            }
        } catch (IOException e) {
            if (!stopRequested) {
                String text = "ERROR: " + e.getMessage();
                output.append(text).append('\n');
                appendResult(resultView, text + "\n");
            }
        }
    }

    private void stopCurrentExecution(@Nullable TextView resultView) {
        Process process;
        boolean shizukuMode;
        synchronized (processLock) {
            process = currentProcess;
            shizukuMode = currentExecutionUsesShizuku;
            if (process == null || !process.isAlive()) {
                if (resultView != null) {
                    appendResult(resultView, "INFO: 実行中のプロセスはありません。\n");
                } else {
                    Toast.makeText(this, "実行中のプロセスはありません。", Toast.LENGTH_SHORT).show();
                }
                return;
            }
            stopRequested = true;
        }

        appendResult(resultView, "INFO: 停止要求を送信しました\n");

        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        closeQuietly(process.getOutputStream());

        try {
            process.destroy();
        } catch (Throwable ignored) {
        }

        if (shizukuMode) {
            try {
                process.destroyForcibly();
            } catch (Throwable ignored) {
            }
        }

        synchronized (processLock) {
            if (currentProcess == process && !process.isAlive()) {
                currentProcess = null;
                currentExecutionUsesShizuku = false;
                stopRequested = false;
            }
        }
    }

    private void stopCurrentExecutionSilently() {
        Process process;
        boolean shizukuMode;
        synchronized (processLock) {
            process = currentProcess;
            shizukuMode = currentExecutionUsesShizuku;
            if (process == null || !process.isAlive()) {
                currentProcess = null;
                currentExecutionUsesShizuku = false;
                stopRequested = false;
                return;
            }
            stopRequested = true;
        }

        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        closeQuietly(process.getOutputStream());

        try {
            process.destroy();
        } catch (Throwable ignored) {
        }

        if (shizukuMode) {
            try {
                process.destroyForcibly();
            } catch (Throwable ignored) {
            }
        }

        synchronized (processLock) {
            if (currentProcess == process && !process.isAlive()) {
                currentProcess = null;
                currentExecutionUsesShizuku = false;
                stopRequested = false;
            }
        }
    }

    private void appendResult(@Nullable TextView resultView, @NonNull String text) {
        if (resultView == null) {
            return;
        }
        runOnUiThread(() -> resultView.append(text));
    }

    private void saveLogToFile(String command, String logContent) {
        File directory = new File(getFilesDir(), "command_logs");
        if (!directory.exists() && !directory.mkdirs()) {
            runOnUiThread(() -> Toast.makeText(this, "ログ保存用ディレクトリの作成に失敗しました。", Toast.LENGTH_LONG).show());
            return;
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = sanitizeFileName(command);
        if (fileName == null || fileName.trim().isEmpty()) {
            fileName = "command";
        }
        if (fileName.length() > 80) {
            fileName = fileName.substring(0, 80);
        }
        fileName = fileName + "_" + timeStamp + ".txt";

        File logFile = new File(directory, fileName);

        try (FileOutputStream fos = new FileOutputStream(logFile);
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            writer.write(logContent);
            writer.flush();
            runOnUiThread(() -> Toast.makeText(this, "ログが保存されました: " + logFile.getAbsolutePath(), Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "ログ保存中にエラー: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private String sanitizeFileName(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("[\\\\/:*?\"<>|]+", "_").replaceAll("\\s+", "_");
    }

    private void closeQuietly(@Nullable Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
