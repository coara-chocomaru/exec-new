package com.poc;

import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class Main {
    private static final String TAG = "Inspector";
    private static final String OUTPUT_BASE = "/data/data/com.android.bluetooth/inspect_report/";
    private static final int MAX_READ_SIZE = 1024;

    private static BufferedWriter writer;
    private static long fileCount = 0, dirCount = 0, errorCount = 0;

    // Native methods
    public static native String[] nativeListDirectory(String path);
    public static native String nativeReadFile(String path);

    static {
        try {
            String classPath = System.getProperty("java.class.path");
            if (classPath == null || classPath.isEmpty()) {
                throw new RuntimeException("java.class.path is empty");
            }
            String apkPath = classPath.split(File.pathSeparator)[0];
            File apkFile = new File(apkPath);

            File libBase = new File(apkFile.getParentFile(), "lib");

            // 3. ABI を決定
            String abi;
            String arch = System.getProperty("os.arch");
            if (arch != null && arch.contains("v7a")) {
                abi = "armeabi-v7a";
            } else {
                abi = "arm64-v8a";  // デフォルト
            }

            // 4. .so ファイルのパスを構築
            File libFile = new File(new File(libBase, abi), "libnative-inspector.so");
            if (libFile.exists()) {
                System.load(libFile.getAbsolutePath());
                System.err.println("[" + TAG + "] Loaded library from: " + libFile.getAbsolutePath());
            } else {
                // フォールバック: システムライブラリパスを探す（通常は失敗する）
                System.loadLibrary("native-inspector");
                System.err.println("[" + TAG + "] Loaded library via System.loadLibrary");
            }
        } catch (Throwable t) {
            System.err.println("[" + TAG + "] Failed to load native library: " + t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    // スレッドセーフな書き込みヘルパー
    private static void safeWrite(String s) {
        try { if (writer != null) writer.write(s); } catch (IOException ignored) {}
    }

    private static void safeFlush() {
        try { if (writer != null) writer.flush(); } catch (IOException ignored) {}
    }

    public static void main(String[] args) {
        System.err.println("[" + TAG + "] === Inspector started ===");
        try {
            File outDir = new File(OUTPUT_BASE);
            if (!outDir.exists()) outDir.mkdirs();
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String reportFile = OUTPUT_BASE + "report_" + timestamp + ".txt";
            writer = new BufferedWriter(new FileWriter(reportFile));
            writeHeader(reportFile);

            String[] targets = {
                "/dev/block",
                "/dev",
                "/data/misc",
                "/data/data",
                "/data/system",
                "/data/local",
                "/data/media",
                "/data/user",
                "/data/app",
                "/proc/self/fd",
                "/proc/self/map_files",
                "/proc/self/root/data/misc",
                "/proc/self/root/data/data"
            };

            for (String target : targets) {
                File f = new File(target);
                if (f.exists()) {
                    System.err.println("[" + TAG + "] Scanning: " + target);
                    safeWrite("\n=== Scanning: " + target + " ===\n");
                    if (target.startsWith("/proc/self/fd") || target.startsWith("/proc/self/map_files")) {
                        scanSymlinkDir(f);
                    } else {
                        walkWithNative(f, 0);
                    }
                } else {
                    System.err.println("[" + TAG + "] Target does not exist: " + target);
                    safeWrite("Target does not exist: " + target + "\n");
                }
            }

            writeFooter();
            System.err.println("[" + TAG + "] Finished. Files: " + fileCount + ", Dirs: " + dirCount + ", Errors: " + errorCount);
        } catch (Exception e) {
            System.err.println("[" + TAG + "] Fatal: " + e);
            safeWrite("FATAL: " + e.toString() + "\n");
        } finally {
            try { if (writer != null) writer.close(); } catch (IOException ignored) {}
        }
    }

    private static void walkWithNative(File file, int depth) {
        if (depth > 20) return;
        try {
            if (file.isDirectory()) {
                dirCount++;
                safeWrite("D " + file.getAbsolutePath() + "\n");
                safeFlush();

                File[] children = file.listFiles();
                if (children == null) {
                    String[] nativeEntries = nativeListDirectory(file.getAbsolutePath());
                    if (nativeEntries == null) {
                        safeWrite("  [Cannot list directory (native failed)]\n");
                        return;
                    }
                    for (String entry : nativeEntries) {
                        String[] parts = entry.split("\\|");
                        if (parts.length < 2) continue;
                        String name = parts[0];
                        char type = parts[1].charAt(0);
                        File sub = new File(file, name);
                        if (type == 'L') {
                            safeWrite("  L " + sub.getAbsolutePath() + " [skip]\n");
                            continue;
                        }
                        walkWithNative(sub, depth + 1);
                    }
                } else {
                    Arrays.sort(children);
                    for (File sub : children) {
                        try {
                            if (Files.isSymbolicLink(sub.toPath())) {
                                String linkTarget = Files.readSymbolicLink(sub.toPath()).toString();
                                safeWrite("  L " + sub.getAbsolutePath() + " -> " + linkTarget + "\n");
                            } else {
                                walkWithNative(sub, depth + 1);
                            }
                        } catch (IOException e) {
                            safeWrite("  L " + sub.getAbsolutePath() + " [broken]\n");
                        }
                    }
                }
            } else if (file.isFile()) {
                fileCount++;
                safeWrite("F " + file.getAbsolutePath() + " (size=" + file.length() + ")\n");
                if (file.length() > 0 && file.canRead()) {
                    String content = nativeReadFile(file.getAbsolutePath());
                    if (content != null && !content.isEmpty()) {
                        safeWrite("  [CONTENT_START]\n");
                        safeWrite(content + "\n");
                        safeWrite("  [CONTENT_END]\n");
                    } else {
                        safeWrite("  [EMPTY or JNI read failed]\n");
                    }
                } else {
                    safeWrite("  [EMPTY or UNREADABLE]\n");
                }
                safeFlush();
            } else {
                safeWrite("S " + file.getAbsolutePath() + "\n");
            }
        } catch (Exception e) {
            errorCount++;
            System.err.println("[" + TAG + "] Error: " + e);
            safeWrite("  [ERROR: " + e.getMessage() + "]\n");
        }
    }

    private static void scanSymlinkDir(File dir) {
        if (!dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            try {
                if (Files.isSymbolicLink(f.toPath())) {
                    String target = Files.readSymbolicLink(f.toPath()).toString();
                    safeWrite("  L " + f.getName() + " -> " + target + "\n");
                } else {
                    safeWrite("  " + f.getName() + "\n");
                }
            } catch (IOException e) {
                safeWrite("  " + f.getName() + " [error]\n");
            }
        }
        safeFlush();
    }

    private static void writeHeader(String reportFile) {
        safeWrite("Comprehensive Inspector Report\n");
        safeWrite("Generated: " + new Date() + "\n");
        safeWrite("UID: " + android.os.Process.myUid() + "\n");
        safeWrite("------------------------------------------------------------\n\n");
    }

    private static void writeFooter() {
        safeWrite("\n------------------------------------------------------------\n");
        safeWrite("Summary: Dirs=" + dirCount + ", Files=" + fileCount + ", Errors=" + errorCount + "\n");
    }
}
