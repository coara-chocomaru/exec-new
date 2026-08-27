package com.poc;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import android.util.Log;

public class DumpExplorer {
    private static final String TAG = "DumpExplorer";
    private static final String OUTPUT_BASE = "/data/data/com.android.bluetooth/dump_report/";
    private static final int MAX_READ_SIZE = 1024; // 読み取り最大バイト数（大きすぎるファイル対策）
    private static final int MAX_DEPTH = 20; // 深さ制限

    private static BufferedWriter writer;
    private static long fileCount = 0;
    private static long dirCount = 0;
    private static long errorCount = 0;

    public static void main(String[] args) {
        Log.i(TAG, "=== DumpExplorer started ===");
        try {
            // 出力ディレクトリ作成
            File outDir = new File(OUTPUT_BASE);
            if (!outDir.exists()) {
                outDir.mkdirs();
            }
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String reportFile = OUTPUT_BASE + "report_" + timestamp + ".txt";
            writer = new BufferedWriter(new FileWriter(reportFile));
            writeHeader(reportFile);

            // 調査対象リスト
            String[] targets = {
                "/dev/block",
                "/data",
                "/proc/self"
            };

            for (String target : targets) {
                File f = new File(target);
                if (f.exists()) {
                    Log.i(TAG, "Scanning: " + target);
                    writer.write("\n=== Scanning: " + target + " ===\n");
                    walk(f, 0);
                } else {
                    Log.w(TAG, "Target does not exist: " + target);
                    writer.write("Target does not exist: " + target + "\n");
                }
            }

            writeFooter();
            Log.i(TAG, "=== DumpExplorer finished. Files: " + fileCount + ", Dirs: " + dirCount + ", Errors: " + errorCount);
        } catch (Exception e) {
            Log.e(TAG, "Fatal error", e);
            try { if (writer != null) writer.write("FATAL: " + e.toString()); } catch (Exception ignored) {}
        } finally {
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        }
    }

    private static void walk(File file, int depth) {
        if (depth > MAX_DEPTH) {
            Log.w(TAG, "Max depth reached, skipping: " + file.getAbsolutePath());
            return;
        }
        try {
            if (file.isDirectory()) {
                dirCount++;
                Log.d(TAG, "Dir: " + file.getAbsolutePath());
                writer.write("D " + file.getAbsolutePath() + " (mode=" + getPermissions(file) + ", owner=" + getOwner(file) + ")\n");
                writer.flush();

                String[] children = file.list();
                if (children == null) {
                    // 読み取り不可ディレクトリ（通常はPermission deniedが発生するが、nullの場合もある）
                    Log.w(TAG, "Cannot list directory: " + file.getAbsolutePath());
                    writer.write("  [Cannot list directory]\n");
                    return;
                }
                // ソートして安定した順序に
                Arrays.sort(children);
                for (String child : children) {
                    // /proc/self の中の数字ディレクトリは大量にあるのでスキップ（プロセスID）
                    if (file.getAbsolutePath().startsWith("/proc/self/") && child.matches("\\d+")) {
                        continue; // 多数のプロセスIDディレクトリはスキップして時間短縮
                    }
                    File sub = new File(file, child);
                    // シンボリックリンクは実体を辿らない（無限ループ防止）
                    if (Files.isSymbolicLink(sub.toPath())) {
                        try {
                            String linkTarget = Files.readSymbolicLink(sub.toPath()).toString();
                            writer.write("  L " + sub.getAbsolutePath() + " -> " + linkTarget + "\n");
                            Log.d(TAG, "Symlink: " + sub.getAbsolutePath());
                        } catch (IOException e) {
                            writer.write("  L " + sub.getAbsolutePath() + " [broken link]\n");
                        }
                        continue;
                    }
                    walk(sub, depth + 1);
                }
            } else if (file.isFile()) {
                fileCount++;
                Log.d(TAG, "File: " + file.getAbsolutePath());
                writer.write("F " + file.getAbsolutePath() + " (size=" + file.length() + ", mode=" + getPermissions(file) + ", owner=" + getOwner(file) + ")\n");
                // ファイルの中身を読み取る（先頭のみ）
                if (file.length() > 0 && file.canRead()) {
                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buffer = new byte[MAX_READ_SIZE];
                        int bytesRead = fis.read(buffer);
                        if (bytesRead > 0) {
                            String content = new String(buffer, 0, bytesRead, "UTF-8");
                            // 制御文字を可読化（簡易）
                            content = content.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
                            writer.write("  [CONTENT_START]\n");
                            writer.write(content + "\n");
                            writer.write("  [CONTENT_END]\n");
                        }
                    } catch (Exception e) {
                        // 読み取りエラー（権限や破損）
                        writer.write("  [READ_ERROR: " + e.getMessage() + "]\n");
                        errorCount++;
                    }
                } else {
                    writer.write("  [EMPTY or UNREADABLE]\n");
                }
                writer.flush();
            } else {
                // 特殊ファイル（デバイスファイルなど）
                Log.d(TAG, "Special: " + file.getAbsolutePath());
                writer.write("S " + file.getAbsolutePath() + " (mode=" + getPermissions(file) + ")\n");
                // デバイスファイルは読み取りを試みない（ブロックする可能性あり）
            }
        } catch (Exception e) {
            errorCount++;
            Log.e(TAG, "Error accessing " + file.getAbsolutePath(), e);
            try {
                writer.write("  [ERROR: " + e.getMessage() + "]\n");
            } catch (IOException ignored) {}
        }
    }

    private static String getPermissions(File file) {
        try {
            PosixFileAttributes attrs = Files.readAttributes(file.toPath(), PosixFileAttributes.class);
            return PosixFilePermissions.toString(attrs.permissions());
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String getOwner(File file) {
        try {
            PosixFileAttributes attrs = Files.readAttributes(file.toPath(), PosixFileAttributes.class);
            return attrs.owner().getName() + ":" + attrs.group().getName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static void writeHeader(String reportFile) throws IOException {
        writer.write("DumpExplorer Report\n");
        writer.write("Generated: " + new Date() + "\n");
        writer.write("Output file: " + reportFile + "\n");
        writer.write("UID: " + android.os.Process.myUid() + "\n");
        writer.write("----------------------------------------------------------------\n\n");
    }

    private static void writeFooter() throws IOException {
        writer.write("\n----------------------------------------------------------------\n");
        writer.write("Summary: Directories=" + dirCount + ", Files=" + fileCount + ", Errors=" + errorCount + "\n");
        writer.write("End of report.\n");
    }
}
