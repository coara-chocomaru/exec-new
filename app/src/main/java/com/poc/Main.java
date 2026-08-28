package com.poc;

import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class Main {
    private static final String TAG = "DumpExplorer";
    private static final String OUTPUT_BASE = "/cache/dump_report/";
    private static final int MAX_READ_SIZE = 1024;
    private static final int MAX_DEPTH = 20;

    private static BufferedWriter writer;
    private static long fileCount = 0, dirCount = 0, errorCount = 0;

    private static void log(String msg) {
        System.err.println("[" + TAG + "] " + msg);
    }

    private static void safeWrite(String s) {
        try { if (writer != null) writer.write(s); } catch (IOException ignored) {}
    }

    private static void safeFlush() {
        try { if (writer != null) writer.flush(); } catch (IOException ignored) {}
    }

    public static void main(String[] args) {
        log("=== Pure Java DumpExplorer (no native code) ===");
        try {
            File outDir = new File(OUTPUT_BASE);
            if (!outDir.exists()) outDir.mkdirs();
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String reportFile = OUTPUT_BASE + "report_" + timestamp + ".txt";
            writer = new BufferedWriter(new FileWriter(reportFile));
            writeHeader(reportFile);

            String[] targets = {
                "/data/misc/bluetooth",
                "/data/data/com.android.settings",
                "/dev/block",
                "/proc/self",
                "/sys/fs/selinux"
            };

            for (String target : targets) {
                File f = new File(target);
                if (f.exists()) {
                    log("Scanning: " + target);
                    safeWrite("\n=== Scanning: " + target + " ===\n");
                    walk(f, 0);
                } else {
                    log("Target does not exist: " + target);
                    safeWrite("Target does not exist: " + target + "\n");
                }
            }

            writeFooter();
            log("Finished. Files: " + fileCount + ", Dirs: " + dirCount + ", Errors: " + errorCount);
        } catch (Exception e) {
            log("Fatal: " + e);
            safeWrite("FATAL: " + e.toString() + "\n");
        } finally {
            try { if (writer != null) writer.close(); } catch (IOException ignored) {}
        }
    }

    private static void walk(File file, int depth) {
        if (depth > MAX_DEPTH) return;
        try {
            if (file.isDirectory()) {
                dirCount++;
                safeWrite("D " + file.getAbsolutePath() + "\n");
                safeFlush();

                String[] children = file.list();
                if (children == null) {
                    safeWrite("  [Cannot list directory]\n");
                    return;
                }
                Arrays.sort(children);
                for (String child : children) {
                    if (file.getAbsolutePath().startsWith("/proc/self/") && child.matches("\\d+")) {
                        continue;
                    }
                    File sub = new File(file, child);
                    try {
                        if (Files.isSymbolicLink(sub.toPath())) {
                            String target = Files.readSymbolicLink(sub.toPath()).toString();
                            safeWrite("  L " + sub.getAbsolutePath() + " -> " + target + "\n");
                            continue;
                        }
                    } catch (IOException e) {
                        safeWrite("  L " + sub.getAbsolutePath() + " [broken link]\n");
                        continue;
                    }
                    walk(sub, depth + 1);
                }
            } else if (file.isFile()) {
                fileCount++;
                safeWrite("F " + file.getAbsolutePath() + " (size=" + file.length() + ")\n");
                if (file.length() > 0 && file.canRead()) {
                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buffer = new byte[MAX_READ_SIZE];
                        int bytesRead = fis.read(buffer);
                        if (bytesRead > 0) {
                            String content = new String(buffer, 0, bytesRead, "UTF-8");
                            content = content.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
                            safeWrite("  [CONTENT_START]\n");
                            safeWrite(content + "\n");
                            safeWrite("  [CONTENT_END]\n");
                        }
                    } catch (Exception e) {
                        safeWrite("  [READ_ERROR: " + e.getMessage() + "]\n");
                        errorCount++;
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
            log("Error accessing " + file.getAbsolutePath() + ": " + e);
            safeWrite("  [ERROR: " + e.getMessage() + "]\n");
        }
    }

    private static void writeHeader(String reportFile) {
        safeWrite("Pure Java DumpExplorer Report\n");
        safeWrite("Generated: " + new Date() + "\n");
        safeWrite("UID: " + android.os.Process.myUid() + "\n");
        safeWrite("------------------------------------------------------------\n\n");
    }

    private static void writeFooter() {
        safeWrite("\n------------------------------------------------------------\n");
        safeWrite("Summary: Dirs=" + dirCount + ", Files=" + fileCount + ", Errors=" + errorCount + "\n");
    }
}
