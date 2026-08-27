package com.poc;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class DumpExplorer {
    private static final String TAG = "DumpExplorer";
    private static final String OUTPUT_BASE = "/data/data/com.android.bluetooth/dump_report/";
    private static final int MAX_READ_SIZE = 1024;
    private static final int MAX_DEPTH = 20;

    private static BufferedWriter writer;
    private static long fileCount = 0;
    private static long dirCount = 0;
    private static long errorCount = 0;
    private static void log(String msg) {
        System.err.println("[" + TAG + "] " + msg);
    }

    public static void main(String[] args) {
        log("=== DumpExplorer started ===");
        try {
            File outDir = new File(OUTPUT_BASE);
            if (!outDir.exists()) {
                outDir.mkdirs();
            }
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String reportFile = OUTPUT_BASE + "report_" + timestamp + ".txt";
            writer = new BufferedWriter(new FileWriter(reportFile));
            writeHeader(reportFile);

            String[] targets = {
                "/dev/block",
                "/data",
                "/proc/self"
            };

            for (String target : targets) {
                File f = new File(target);
                if (f.exists()) {
                    log("Scanning: " + target);
                    writer.write("\n=== Scanning: " + target + " ===\n");
                    walk(f, 0);
                } else {
                    log("Target does not exist: " + target);
                    writer.write("Target does not exist: " + target + "\n");
                }
            }

            writeFooter();
            log("=== DumpExplorer finished. Files: " + fileCount + ", Dirs: " + dirCount + ", Errors: " + errorCount);
        } catch (Exception e) {
            log("Fatal error: " + e);
            try { if (writer != null) writer.write("FATAL: " + e.toString()); } catch (Exception ignored) {}
        } finally {
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        }
    }

    private static void walk(File file, int depth) {
        if (depth > MAX_DEPTH) {
            log("Max depth reached, skipping: " + file.getAbsolutePath());
            return;
        }
        try {
            if (file.isDirectory()) {
                dirCount++;
                log("Dir: " + file.getAbsolutePath());
                writer.write("D " + file.getAbsolutePath() + " (mode=" + getPermissions(file) + ", owner=" + getOwner(file) + ")\n");
                writer.flush();

                String[] children = file.list();
                if (children == null) {
                    log("Cannot list directory: " + file.getAbsolutePath());
                    writer.write("  [Cannot list directory]\n");
                    return;
                }
                Arrays.sort(children);
                for (String child : children) {
                    if (file.getAbsolutePath().startsWith("/proc/self/") && child.matches("\\d+")) {
                        continue;
                    }
                    File sub = new File(file, child);
                    if (Files.isSymbolicLink(sub.toPath())) {
                        try {
                            String linkTarget = Files.readSymbolicLink(sub.toPath()).toString();
                            writer.write("  L " + sub.getAbsolutePath() + " -> " + linkTarget + "\n");
                            log("Symlink: " + sub.getAbsolutePath());
                        } catch (IOException e) {
                            writer.write("  L " + sub.getAbsolutePath() + " [broken link]\n");
                        }
                        continue;
                    }
                    walk(sub, depth + 1);
                }
            } else if (file.isFile()) {
                fileCount++;
                log("File: " + file.getAbsolutePath());
                writer.write("F " + file.getAbsolutePath() + " (size=" + file.length() + ", mode=" + getPermissions(file) + ", owner=" + getOwner(file) + ")\n");
                if (file.length() > 0 && file.canRead()) {
                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buffer = new byte[MAX_READ_SIZE];
                        int bytesRead = fis.read(buffer);
                        if (bytesRead > 0) {
                            String content = new String(buffer, 0, bytesRead, "UTF-8");
                            content = content.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
                            writer.write("  [CONTENT_START]\n");
                            writer.write(content + "\n");
                            writer.write("  [CONTENT_END]\n");
                        }
                    } catch (Exception e) {
                        writer.write("  [READ_ERROR: " + e.getMessage() + "]\n");
                        errorCount++;
                    }
                } else {
                    writer.write("  [EMPTY or UNREADABLE]\n");
                }
                writer.flush();
            } else {
                log("Special: " + file.getAbsolutePath());
                writer.write("S " + file.getAbsolutePath() + " (mode=" + getPermissions(file) + ")\n");
            }
        } catch (Exception e) {
            errorCount++;
            log("Error accessing " + file.getAbsolutePath() + ": " + e);
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
        writer.write("UID: " + getUid() + "\n");
        writer.write("----------------------------------------------------------------\n\n");
    }

    private static String getUid() {
        try {
            return String.valueOf(android.os.Process.myUid());
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static void writeFooter() throws IOException {
        writer.write("\n----------------------------------------------------------------\n");
        writer.write("Summary: Directories=" + dirCount + ", Files=" + fileCount + ", Errors=" + errorCount + "\n");
        writer.write("End of report.\n");
    }
}
