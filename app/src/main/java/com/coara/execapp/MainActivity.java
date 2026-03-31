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
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int FILE_PICKER_REQUEST_CODE = 1002;
    private static final int SHIZUKU_REQUEST_CODE = 1003;
    private static final int MANAGE_ALL_FILES_REQUEST_CODE = 1004;
    private static final int WRITE_SETTINGS_REQUEST_CODE = 1005;
    private static final String PREF_NAME = "execapp_prefs";
    private static final String KEY_SETTINGS_CHECKED = "write_settings_checked_once";
    private static final String BINARY_DIR_NAME = "binaries";
    private static final String LOG_DIR_NAME = "command_logs";

    private final ExecutorService backgroundExecutor = Executors.newCachedThreadPool();
    private final AtomicLong executionTokenGenerator = new AtomicLong(0L);

    private volatile Process currentProcess;
    private volatile File selectedBinary;
    private volatile String executionPath;
    private volatile boolean isDeviceOwner;
    private volatile boolean shizukuGranted;
    private volatile boolean shizukuPermissionRequestInFlight;
    private volatile long currentExecutionToken;
    private volatile boolean executionRunning;
    private volatile ExecutionMode currentExecutionMode = ExecutionMode.NONE;
    private volatile String currentCommand;

    private Shizuku.OnRequestPermissionResultListener requestPermissionResultListener;

    private enum ExecutionMode {
        NONE,
        APP_SHELL,
        SHIZUKU_SHELL,
        SETTINGS_DIRECT
    }

    private static final class SettingsCommandSpec {
        final String action;
        final String category;
        final String key;
        final String value;

        SettingsCommandSpec(String action, String category, String key, String value) {
            this.action = action;
            this.category = category;
            this.key = key;
            this.value = value;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().getDecorView().setBackgroundColor(Color.WHITE);

        ScrollView scrollView = findViewById(R.id.scroll_view);
        TextView resultView = findViewById(R.id.result_view);
        if (scrollView != null) {
            scrollView.setBackgroundColor(Color.WHITE);
        }
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

        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        isDeviceOwner = dpm != null && dpm.isDeviceOwnerApp(getPackageName());

        shizukuGranted = false;
        shizukuPermissionRequestInFlight = false;

        requestPermissionResultListener = (requestCode, grantResult) -> {
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                shizukuPermissionRequestInFlight = false;
                shizukuGranted = grantResult == PackageManager.PERMISSION_GRANTED;
                postToast(shizukuGranted ? "Shizuku権限が付与されました" : "Shizuku権限が拒否されました");
            }
        };
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener);

        updateShizukuStatus();

        pickBinaryButton.setOnClickListener(view -> launchFilePicker());

        clearBinaryButton.setOnClickListener(view -> {
            File internalBinary = selectedBinary;
            String executionPathSnapshot = executionPath;

            selectedBinary = null;
            executionPath = null;

            boolean deleteShizukuCopy = executionPathSnapshot != null && executionPathSnapshot.startsWith("/data/local/tmp/");

            if (internalBinary != null) {
                deleteQuietly(internalBinary);
            }

            if (deleteShizukuCopy) {
                final String pathToDelete = executionPathSnapshot;
                if (isShizukuUsable()) {
                    backgroundExecutor.execute(() -> runSilentShizukuCommand("rm -f \"" + pathToDelete + "\""));
                } else {
                    Toast.makeText(this, "/data/local/tmp 上のコピーはShizukuが利用できないため削除できません。", Toast.LENGTH_LONG).show();
                }
            }

            Toast.makeText(this, "バイナリが解除されました。", Toast.LENGTH_SHORT).show();
        });

        executeButton.setOnClickListener(view -> {
            String command = commandInput.getText().toString().trim();
            if (command.isEmpty() && executionPath == null) {
                Toast.makeText(this, "コマンドまたはバイナリを指定してください。", Toast.LENGTH_SHORT).show();
                return;
            }
            if (isExecutionRunning()) {
                Toast.makeText(this, "実行中の処理があります。STOPで停止してください。", Toast.LENGTH_SHORT).show();
                return;
            }
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

        checkPermissionsStep1();
    }

    private void checkPermissionsStep1() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                        },
                        PERMISSION_REQUEST_CODE
                );
            } else {
                checkPermissionsStep2();
            }
        } else {
            checkPermissionsStep2();
        }
    }

    private void checkPermissionsStep2() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.addCategory("android.intent.category.DEFAULT");
                    intent.setData(Uri.parse(String.format("package:%s", getApplicationContext().getPackageName())));
                    startActivityForResult(intent, MANAGE_ALL_FILES_REQUEST_CODE);
                } catch (Exception e) {
                    Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(intent, MANAGE_ALL_FILES_REQUEST_CODE);
                }
            } else {
                checkPermissionsStep3();
            }
        } else {
            checkPermissionsStep3();
        }
    }

    private void checkPermissionsStep3() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
            boolean alreadyChecked = prefs.getBoolean(KEY_SETTINGS_CHECKED, false);

            if (!Settings.System.canWrite(this) && !alreadyChecked) {
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("システム設定変更の許可");
                builder.setMessage("settings put/get コマンドでシステム設定を変更するには\nWRITE_SETTINGS権限が必要です。\n\n今すぐ許可しますか？\n（許可しない場合、Shizukuが必要です）");
                builder.setPositiveButton("許可する", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, WRITE_SETTINGS_REQUEST_CODE);
                    prefs.edit().putBoolean(KEY_SETTINGS_CHECKED, true).apply();
                });
                builder.setNegativeButton("後で", (dialog, which) -> prefs.edit().putBoolean(KEY_SETTINGS_CHECKED, true).apply());
                builder.setCancelable(true);
                builder.show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            int count = Math.min(permissions.length, grantResults.length);
            for (int i = 0; i < count; i++) {
                Toast.makeText(
                        this,
                        permissions[i] + (grantResults[i] == PackageManager.PERMISSION_GRANTED ? " 権限が許可されました" : " 権限が拒否されました"),
                        Toast.LENGTH_SHORT
                ).show();
            }
            checkPermissionsStep2();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateShizukuStatus();
    }

    private void updateShizukuStatus() {
        boolean binderAlive;
        boolean preV11;
        try {
            binderAlive = Shizuku.pingBinder();
        } catch (Throwable ignored) {
            binderAlive = false;
        }

        try {
            preV11 = Shizuku.isPreV11();
        } catch (Throwable ignored) {
            preV11 = false;
        }

        if (binderAlive && !preV11) {
            try {
                shizukuGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            } catch (Throwable ignored) {
                shizukuGranted = false;
            }

            if (!shizukuGranted && !shizukuPermissionRequestInFlight) {
                try {
                    shizukuPermissionRequestInFlight = true;
                    Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
                } catch (Throwable ignored) {
                    shizukuPermissionRequestInFlight = false;
                }
            }
        } else {
            shizukuGranted = false;
            shizukuPermissionRequestInFlight = false;
        }
    }

    private void launchFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, FILE_PICKER_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == MANAGE_ALL_FILES_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "全てのファイルアクセス 権限が許可されました", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "全てのファイルアクセス 権限が拒否されました", Toast.LENGTH_SHORT).show();
                }
            }
            checkPermissionsStep3();
        } else if (requestCode == WRITE_SETTINGS_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.System.canWrite(this)) {
                    Toast.makeText(this, "システム設定変更 権限が許可されました", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "システム設定変更 権限が拒否されました", Toast.LENGTH_SHORT).show();
                }
            }
        } else if (requestCode == FILE_PICKER_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) {
                Toast.makeText(this, "ファイルの取得に失敗しました。", Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                final int takeFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                getContentResolver().takePersistableUriPermission(uri, takeFlags);
            } catch (Throwable ignored) {
            }

            selectedBinary = copyFileToInternalStorage(uri);
            if (selectedBinary != null && selectedBinary.setExecutable(true)) {
                if (isShizukuUsable()) {
                    handleShizukuBinaryCopy(selectedBinary);
                } else {
                    executionPath = selectedBinary.getAbsolutePath();
                    Toast.makeText(this, "バイナリが選択され、実行権限が付与されました: " + executionPath, Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "バイナリ選択または実行権限付与に失敗しました。", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void handleShizukuBinaryCopy(@NonNull File sourceBinary) {
        String filename = sourceBinary.getName();
        executionPath = "/data/local/tmp/" + filename;

        String cmd = "cp -f \"" + sourceBinary.getAbsolutePath() + "\" \"" + executionPath + "\" && chmod 777 \"" + executionPath + "\"";
        boolean success = runSilentShizukuCommand(cmd);

        if (success) {
            Toast.makeText(this, "バイナリ選択完了: " + executionPath, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Shizuku経由のコピーに失敗しました。内部領域のパスを使用します。", Toast.LENGTH_LONG).show();
            executionPath = sourceBinary.getAbsolutePath();
        }
    }

    @Nullable
    private File copyFileToInternalStorage(@NonNull Uri uri) {
        File directory = getBinaryDirectory();
        if (!directory.exists() && !directory.mkdirs()) {
            Toast.makeText(this, "ディレクトリ作成に失敗しました。", Toast.LENGTH_SHORT).show();
            return null;
        }

        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                Toast.makeText(this, "ファイルを開けませんでした。", Toast.LENGTH_SHORT).show();
                return null;
            }

            String fileName = getFileName(uri);
            if (fileName == null || fileName.trim().isEmpty()) {
                fileName = "picked_binary";
            }
            fileName = sanitizeFileName(fileName);

            File destFile = resolveUniqueFile(directory, fileName);

            try (OutputStream outputStream = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[8192];
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

    @Nullable
    private String getFileName(@NonNull Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (columnIndex >= 0) {
                        result = cursor.getString(columnIndex);
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
        return result;
    }

    private boolean runSilentShizukuCommand(@NonNull String command) {
        try {
            Process process = startProcess(command, true);
            if (process == null) {
                return false;
            }

            StringBuilder merged = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    merged.append(line).append('\n');
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String trimmedOutput = merged.toString().trim();
                if (!trimmedOutput.isEmpty()) {
                    postToast("Shizukuコマンド失敗 (exit_code: " + exitCode + "): " + trimmedOutput);
                } else {
                    postToast("Shizukuコマンド失敗 (exit_code: " + exitCode + ")");
                }
            }
            safeDestroy(process);
            return exitCode == 0;
        } catch (Exception e) {
            postToast("Shizukuコマンド実行エラー: " + e.getMessage());
            return false;
        }
    }

    private void executeCommand(@NonNull String command, @NonNull TextView resultView) {
        resultView.setText("");
        String trimmed = command.trim();
        String effectiveCommand = buildEffectiveCommand(trimmed);

        if (executionPath == null && trimmed.startsWith("settings ") && !isShizukuUsable()) {
            boolean handled = executeSettingsDirect(trimmed, resultView);
            if (handled) {
                return;
            }
        }

        startCommandExecution(effectiveCommand, resultView);
    }

    private boolean executeSettingsDirect(@NonNull String command, @NonNull TextView resultView) {
        SettingsCommandSpec spec = parseSettingsCommand(command);
        if (spec == null) {
            appendResult(resultView, "ERROR: settingsコマンドの形式が不正です");
            saveLogToFile(command, "ERROR: settingsコマンドの形式が不正です");
            return true;
        }

        String resultText;
        try {
            if ("put".equals(spec.action)) {
                resultText = applySettingsPutDirect(spec);
            } else if ("get".equals(spec.action)) {
                resultText = applySettingsGetDirect(spec);
            } else {
                resultText = "ERROR: 未対応のsettingsコマンドです";
            }
        } catch (Exception e) {
            resultText = "ERROR: " + e.getMessage();
        }

        appendResult(resultView, resultText);
        saveLogToFile(command, resultText);
        return true;
    }

    @NonNull
    private String applySettingsPutDirect(@NonNull SettingsCommandSpec spec) {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, AppDeviceAdminReceiver.class);

        if (spec.value == null) {
            return "ERROR: settings put の値が指定されていません";
        }

        try {
            if (isDeviceOwner && dpm != null) {
                if ("global".equals(spec.category)) {
                    dpm.setGlobalSetting(admin, spec.key, spec.value);
                } else if ("secure".equals(spec.category)) {
                    dpm.setSecureSetting(admin, spec.key, spec.value);
                } else {
                    dpm.setSystemSetting(admin, spec.key, spec.value);
                }
                return "Device Ownerで設定変更完了: " + spec.category + " " + spec.key + " = " + spec.value;
            }

            ContentResolver cr = getContentResolver();
            boolean success = false;
            if ("system".equals(spec.category)) {
                success = Settings.System.putString(cr, spec.key, spec.value);
            } else if ("global".equals(spec.category)) {
                success = Settings.Global.putString(cr, spec.key, spec.value);
            } else if ("secure".equals(spec.category)) {
                success = Settings.Secure.putString(cr, spec.key, spec.value);
            }

            return success
                    ? "ContentResolverで設定変更完了: " + spec.category + " " + spec.key + " = " + spec.value
                    : "変更失敗（WRITE_SETTINGS権限が不足しているか、対象設定にアクセスできません）";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @NonNull
    private String applySettingsGetDirect(@NonNull SettingsCommandSpec spec) {
        try {
            ContentResolver cr = getContentResolver();
            String val = null;
            if ("system".equals(spec.category)) {
                val = Settings.System.getString(cr, spec.key);
            } else if ("global".equals(spec.category)) {
                val = Settings.Global.getString(cr, spec.key);
            } else if ("secure".equals(spec.category)) {
                val = Settings.Secure.getString(cr, spec.key);
            }
            return "取得結果: " + spec.category + " " + spec.key + " = " + (val != null ? val : "(null)");
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Nullable
    private SettingsCommandSpec parseSettingsCommand(@NonNull String command) {
        String[] parts = command.trim().split("\\s+");
        if (parts.length < 3 || !"settings".equals(parts[0])) {
            return null;
        }

        int index = 1;
        String action = "put";
        if ("put".equals(parts[1]) || "get".equals(parts[1])) {
            action = parts[1];
            index = 2;
        }

        String category = "system";
        if (parts.length > index && isCategory(parts[index])) {
            category = parts[index];
            index++;
        }

        if (parts.length <= index) {
            return null;
        }

        String key = parts[index++];
        String value = null;
        if ("put".equals(action)) {
            if (parts.length <= index) {
                return null;
            }
            value = joinParts(parts, index);
        }

        return new SettingsCommandSpec(action, category, key, value);
    }

    private boolean isCategory(@NonNull String value) {
        return "system".equals(value) || "global".equals(value) || "secure".equals(value);
    }

    @NonNull
    private String joinParts(@NonNull String[] parts, int startIndex) {
        StringBuilder sb = new StringBuilder();
        for (int i = startIndex; i < parts.length; i++) {
            sb.append(parts[i]);
            if (i < parts.length - 1) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    @NonNull
    private String buildEffectiveCommand(@NonNull String trimmedCommand) {
        if (executionPath == null) {
            return trimmedCommand;
        }
        if (trimmedCommand.isEmpty()) {
            return shellQuote(executionPath);
        }
        return shellQuote(executionPath) + " " + trimmedCommand;
    }

    private void startCommandExecution(@NonNull String command, @NonNull TextView resultView) {
        final long token = executionTokenGenerator.incrementAndGet();
        currentExecutionToken = token;
        currentCommand = command;
        currentExecutionMode = isShizukuUsable() ? ExecutionMode.SHIZUKU_SHELL : ExecutionMode.APP_SHELL;
        executionRunning = true;

        appendResult(resultView, currentExecutionMode == ExecutionMode.SHIZUKU_SHELL ? "INFO: Shizukuで実行" : "INFO: 通常権限で実行");

        backgroundExecutor.execute(() -> {
            Process process = null;
            StringBuilder output = new StringBuilder();

            try {
                process = startProcess(command, currentExecutionMode == ExecutionMode.SHIZUKU_SHELL);
                if (process == null) {
                    if (isTokenActive(token)) {
                        appendResult(resultView, "ERROR: プロセスの起動に失敗しました");
                        saveLogToFile(command, "ERROR: プロセスの起動に失敗しました");
                    }
                    clearExecutionStateIfMatches(token);
                    return;
                }

                currentProcess = process;

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append('\n');
                        if (isTokenActive(token)) {
                            appendResult(resultView, line);
                        }
                    }
                }

                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    String exitMessage = "ERROR: プロセスが異常終了しました (exit_code: " + exitCode + ")";
                    output.append(exitMessage).append('\n');
                    if (isTokenActive(token)) {
                        appendResult(resultView, exitMessage);
                    }
                }
                saveLogToFile(command, output.toString());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                output.append("ERROR: ").append(e.getMessage()).append('\n');
                if (isTokenActive(token)) {
                    appendResult(resultView, "ERROR: " + e.getMessage());
                }
                saveLogToFile(command, output.toString());
            } catch (Exception e) {
                output.append("ERROR: ").append(e.getMessage()).append('\n');
                if (isTokenActive(token)) {
                    appendResult(resultView, "ERROR: " + e.getMessage());
                }
                saveLogToFile(command, output.toString());
            } finally {
                safeDestroy(process);
                clearExecutionStateIfMatches(token);
            }
        });
    }

    @Nullable
    private Process startProcess(@NonNull String command, boolean preferShizuku) throws IOException {
        String mergedCommand = command.endsWith(" 2>&1") ? command : command + " 2>&1";
        String[] argv = new String[]{"/system/bin/sh", "-c", mergedCommand};

        if (preferShizuku && isShizukuUsable()) {
            try {
                Process shizukuProcess = startShizukuProcess(argv);
                if (shizukuProcess != null) {
                    return shizukuProcess;
                }
            } catch (Throwable ignored) {
            }
        }

        ProcessBuilder processBuilder = new ProcessBuilder(argv);
        processBuilder.redirectErrorStream(true);
        return processBuilder.start();
    }

    @Nullable
    private Process startShizukuProcess(@NonNull String[] argv) throws Exception {
        Class<?> clazz = Class.forName("rikka.shizuku.Shizuku");
        Method method = clazz.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        method.setAccessible(true);
        Object process = method.invoke(null, argv, null, null);

        if (process instanceof Process) {
            return (Process) process;
        }
        return null;
    }

    private boolean isShizukuUsable() {
        try {
            return Shizuku.pingBinder()
                    && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isExecutionRunning() {
        return executionRunning;
    }

    private boolean isTokenActive(long token) {
        return executionRunning && currentExecutionToken == token;
    }

    private void stopCurrentExecution(@NonNull TextView resultView) {
        final Process processSnapshot = currentProcess;
        final ExecutionMode modeSnapshot = currentExecutionMode;

        currentProcess = null;
        currentExecutionMode = ExecutionMode.NONE;
        currentCommand = null;
        currentExecutionToken = 0L;
        executionRunning = false;

        if (processSnapshot == null) {
            Toast.makeText(this, "実行中のプロセスはありません。", Toast.LENGTH_SHORT).show();
            return;
        }

        backgroundExecutor.execute(() -> {
            boolean stopped = false;

            try {
                if (modeSnapshot == ExecutionMode.SHIZUKU_SHELL) {
                    Long pid = getProcessPid(processSnapshot);
                    if (pid != null) {
                        try {
                            runSilentShizukuCommand("kill -TERM " + pid);
                            sleepQuietly(150);
                            runSilentShizukuCommand("kill -KILL " + pid);
                            sleepQuietly(150);
                        } catch (Throwable ignored) {
                        }
                    }
                }

                try {
                    processSnapshot.destroy();
                } catch (Throwable ignored) {
                }

                sleepQuietly(150);

                try {
                    processSnapshot.destroyForcibly();
                } catch (Throwable ignored) {
                }

                stopped = true;
            } catch (Throwable ignored) {
                stopped = true;
            }

            final boolean finalStopped = stopped;
            postToUi(() -> {
                if (finalStopped) {
                    appendResult(resultView, "INFO: 実行中の処理を停止しました");
                    Toast.makeText(this, "実行中の処理を停止しました", Toast.LENGTH_SHORT).show();
                } else {
                    appendResult(resultView, "INFO: 停止処理を試行しました");
                    Toast.makeText(this, "停止処理を試行しました", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    @Nullable
    private Long getProcessPid(@NonNull Process process) {
        try {
            Method pidMethod = Process.class.getMethod("pid");
            Object value = pidMethod.invoke(process);
            if (value instanceof Long) {
                return (Long) value;
            }
            if (value instanceof Integer) {
                return ((Integer) value).longValue();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void clearExecutionStateIfMatches(long token) {
        if (currentExecutionToken == token) {
            currentProcess = null;
            currentExecutionMode = ExecutionMode.NONE;
            currentCommand = null;
            executionRunning = false;
        }
    }

    private void appendResult(@NonNull TextView resultView, @NonNull String text) {
        postToUi(() -> resultView.append(text + "\n"));
    }

    private void postToast(@NonNull String message) {
        postToUi(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private void postToUi(@NonNull Runnable runnable) {
        if (isFinishing()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed()) {
            return;
        }
        runOnUiThread(runnable);
    }

    private void saveLogToFile(@NonNull String command, @NonNull String logContent) {
        File directory = getLogDirectory();
        if (!directory.exists() && !directory.mkdirs()) {
            postToast("ログ保存用ディレクトリを作成できませんでした");
            return;
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = sanitizeLogFileName(command) + "_" + timeStamp + ".txt";
        File logFile = resolveUniqueFile(directory, fileName);

        try (FileOutputStream fos = new FileOutputStream(logFile);
                OutputStreamWriter writer = new OutputStreamWriter(fos)) {
            writer.write(logContent);
            postToast("ログが保存されました: " + logFile.getAbsolutePath());
        } catch (Exception e) {
            postToast("ログ保存中にエラー: " + e.getMessage());
        }
    }

    @NonNull
    private File getBinaryDirectory() {
        return new File(getExternalFilesDir(null), BINARY_DIR_NAME);
    }

    @NonNull
    private File getLogDirectory() {
        return new File(getExternalFilesDir(null), LOG_DIR_NAME);
    }

    private void deleteQuietly(@Nullable File file) {
        if (file != null && file.exists()) {
            file.delete();
        }
    }

    @NonNull
    private String sanitizeFileName(@NonNull String fileName) {
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    @NonNull
    private String sanitizeLogFileName(@NonNull String command) {
        String sanitized = command.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (sanitized.length() > 30) {
            sanitized = sanitized.substring(0, 30);
        }
        return sanitized;
    }

    @NonNull
    private File resolveUniqueFile(@NonNull File directory, @NonNull String fileName) {
        File file = new File(directory, fileName);
        if (!file.exists()) {
            return file;
        }
        int i = 1;
        String name = fileName;
        String ext = "";
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx > 0) {
            name = fileName.substring(0, dotIdx);
            ext = fileName.substring(dotIdx);
        }
        while (file.exists()) {
            file = new File(directory, name + "_" + i + ext);
            i++;
        }
        return file;
    }

    @NonNull
    private String shellQuote(@NonNull String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private void safeDestroy(@Nullable Process process) {
        if (process != null) {
            try {
                process.destroy();
            } catch (Exception ignored) {
            }
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
