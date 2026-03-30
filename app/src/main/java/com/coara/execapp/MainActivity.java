package com.coara.execapp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.database.Cursor;
import android.widget.Button;
import android.widget.EditText;
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
    private ScheduledExecutorService timeoutExecutor;
    private boolean isDeviceOwner;
    private boolean shizukuGranted;
    private Shizuku.OnRequestPermissionResultListener requestPermissionResultListener;
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int FILE_PICKER_REQUEST_CODE = 1002;
    private static final int SHIZUKU_REQUEST_CODE = 1003;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        EditText commandInput = findViewById(R.id.command_input);
        Button executeButton = findViewById(R.id.execute_button);
        Button pickBinaryButton = findViewById(R.id.pick_binary_button);
        Button clearBinaryButton = findViewById(R.id.clear_binary_button);
        Button stopButton = findViewById(R.id.stop_button);
        Button keyboardButton = findViewById(R.id.keyboard_button);
        TextView resultView = findViewById(R.id.result_view);

        checkPermissions();

        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        isDeviceOwner = dpm != null && dpm.isDeviceOwnerApp(getPackageName());

        shizukuGranted = false;
        requestPermissionResultListener = (requestCode, grantResult) -> {
            if (requestCode == SHIZUKU_REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
                shizukuGranted = true;
                runOnUiThread(() -> Toast.makeText(this, "Shizuku権限が付与されました（shell/rootで実行可能）", Toast.LENGTH_SHORT).show());
            }
        };
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener);

        if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                shizukuGranted = true;
            } else {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("システム設定変更の許可");
                builder.setMessage("settings put/get コマンドでシステム設定を変更するには\n" +
                        "WRITE_SETTINGS権限が必要です。\n\n" +
                        "今すぐ許可しますか？\n" +
                        "（許可しない場合、ShizukuまたはDevice Ownerが必要です）");
                builder.setPositiveButton("許可する", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                });
                builder.setNegativeButton("後で", null);
                builder.setCancelable(true);
                builder.show();
            }
        }

        pickBinaryButton.setOnClickListener(view -> launchFilePicker());

        clearBinaryButton.setOnClickListener(view -> {
            selectedBinary = null;
            Toast.makeText(this, "バイナリが解除されました。", Toast.LENGTH_SHORT).show();
        });

        executeButton.setOnClickListener(view -> {
            String command = commandInput.getText().toString().trim();
            if (command.isEmpty() && selectedBinary == null) {
                Toast.makeText(this, "コマンドまたはバイナリを指定してください。", Toast.LENGTH_SHORT).show();
                return;
            }

            if (selectedBinary != null && selectedBinary.exists()) {
                command = selectedBinary.getAbsolutePath() + " " + command;
            }

            executeCommand(command, resultView);
        });

        stopButton.setOnClickListener(view -> {
            if (currentProcess != null && currentProcess.isAlive()) {
                currentProcess.destroy();
                resultView.append("INFO: コマンドが強制終了されました\n");
            } else {
                Toast.makeText(this, "実行中のプロセスはありません。", Toast.LENGTH_SHORT).show();
            }
        });

        keyboardButton.setOnClickListener(view -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
                commandInput.requestFocus();
            }
        });
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, permissions[i] + " 権限が許可されました", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, permissions[i] + " 権限が拒否されました", Toast.LENGTH_SHORT).show();
                }
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
                    Toast.makeText(this, "バイナリが選択され、実行権限が付与されました: " + selectedBinary.getAbsolutePath(), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "バイナリ選択または実行権限付与に失敗しました。", Toast.LENGTH_SHORT).show();
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

    private void executeCommand(String command, @NonNull TextView resultView) {
        resultView.setText("");

        String trimmed = command.trim();
        if (trimmed.startsWith("settings ")) {
            String[] parts = trimmed.split("\\s+");

            // 短縮形対応: settings global adb_enable 0 → put扱い
            String action;
            String category;
            String key;
            String value = null;

            if (parts.length >= 4 && ("put".equals(parts[1]) || "get".equals(parts[1]))) {
                // 完全形
                action = parts[1];
                category = parts[2];
                key = parts[3];
                if ("put".equals(action) && parts.length >= 5) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 4; i < parts.length; i++) {
                        sb.append(parts[i]).append(i < parts.length - 1 ? " " : "");
                    }
                    value = sb.toString();
                }
            } else if (parts.length >= 3) {
                // 短縮形（putを省略）
                action = "put";
                category = parts[1];
                key = parts[2];
                if (parts.length >= 4) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 3; i < parts.length; i++) {
                        sb.append(parts[i]).append(i < parts.length - 1 ? " " : "");
                    }
                    value = sb.toString();
                }
            } else {
                resultView.append("ERROR: settingsコマンドの形式が不正です\n");
                return;
            }

            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, AppDeviceAdminReceiver.class);

            boolean success = false;
            String resultText = "";

            if ("put".equals(action) && value != null) {
                try {
                    if (isDeviceOwner && dpm != null) {
                        if ("global".equals(category)) dpm.setGlobalSetting(admin, key, value);
                        else if ("secure".equals(category)) dpm.setSecureSetting(admin, key, value);
                        else if ("system".equals(category)) dpm.setSystemSetting(admin, key, value);
                        resultText = "Device Ownerで設定変更完了: " + category + " " + key + " = " + value;
                        success = true;
                    } else {
                        ContentResolver cr = getContentResolver();
                        if ("system".equals(category)) success = Settings.System.putString(cr, key, value);
                        else if ("global".equals(category)) success = Settings.Global.putString(cr, key, value);
                        else if ("secure".equals(category)) success = Settings.Secure.putString(cr, key, value);
                        resultText = success ? "ContentResolverで設定変更完了: " + category + " " + key + " = " + value
                                             : "変更失敗（WRITE_SETTINGS権限が不足しています）";
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
                    success = true;
                } catch (Exception e) {
                    resultText = "ERROR: " + e.getMessage();
                }
            } else {
                resultText = "ERROR: 未対応のsettingsコマンドです";
            }

            runOnUiThread(() -> resultView.append(resultText + "\n"));
            saveLogToFile(command, resultText);
            return;
        }

        // 通常コマンド（Shizuku or shell）
        try {
            Process process;
            if (shizukuGranted) {
                try {
                    Class<?> clazz = Class.forName("rikka.shizuku.Shizuku");
                    java.lang.reflect.Method method = clazz.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                    method.setAccessible(true);
                    process = (Process) method.invoke(null, new String[]{"/system/bin/sh", "-c", command}, null, null);
                    runOnUiThread(() -> resultView.append("INFO: Shizukuで実行（shell/root権限）\n"));
                } catch (Exception reflectionEx) {
                    ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command);
                    process = pb.start();
                }
            } else {
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command);
                process = pb.start();
            }
            currentProcess = process;

            timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
            long timeout = isDeviceOwner ? 0L : 180L;
            if (timeout > 0) {
                timeoutExecutor.schedule(() -> {
                    if (currentProcess != null && currentProcess.isAlive()) {
                        currentProcess.destroy();
                        runOnUiThread(() -> resultView.append("INFO: タイムアウトにより強制終了されました\n"));
                    }
                }, timeout, TimeUnit.SECONDS);
            }

            Executors.newSingleThreadExecutor().submit(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(currentProcess.getInputStream()));
                     BufferedReader errorReader = new BufferedReader(new InputStreamReader(currentProcess.getErrorStream()))) {

                    StringBuilder output = new StringBuilder();
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

    private void saveLogToFile(String command, String logContent) {
        File directory = new File(getExternalFilesDir(null), "command_logs");
        if (!directory.exists()) {
            directory.mkdirs();
        }

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
            currentProcess.destroy();
        }
        if (timeoutExecutor != null && !timeoutExecutor.isShutdown()) {
            timeoutExecutor.shutdownNow();
        }
        if (requestPermissionResultListener != null) {
            Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener);
        }
    }
}
