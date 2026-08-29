package com.poc;

import android.app.ActivityThread;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ServiceManager;
import java.lang.reflect.Method;
import java.util.List;

public class Main {
    private static Context ctx;
    private static PackageManager pm;
    private static int pass = 0, fail = 0;

    public static void main(String[] args) {
        try {
            ctx = ActivityThread.systemMain().getApplication();
            pm = ctx.getPackageManager();
        } catch (Throwable t) {
            System.out.println("FAIL: Init context - " + t);
            System.exit(1);
        }

        checkServiceInterfaces();
        checkHiddenApis();
        checkTestClasses();
        checkTestPermissions();
        checkTestIntents();

        System.out.println("=== app_process Results: PASS=" + pass + " FAIL=" + fail);
        System.exit(fail > 0 ? 1 : 0);
    }

    private static void checkServiceInterfaces() {
        try {
            IBinder b = ServiceManager.getService("testharness");
            if (b == null) {
                System.out.println("PASS: testharness service not registered");
                pass++;
            } else {
                System.out.println("FAIL: testharness service exists");
                fail++;
            }
        } catch (Exception e) {
            System.out.println("PASS: testharness service not available (exception)");
            pass++;
        }
        try {
            IBinder b2 = ServiceManager.getService("factorytest");
            if (b2 == null) {
                System.out.println("PASS: factorytest service not registered");
                pass++;
            } else {
                System.out.println("FAIL: factorytest service exists");
                fail++;
            }
        } catch (Exception e) {
            System.out.println("PASS: factorytest service not available");
            pass++;
        }
    }

    private static void checkHiddenApis() {
        try {
            Class<?> am = Class.forName("android.app.ActivityManager");
            Method m = am.getMethod("getRunningTasks", int.class);
            m.invoke(null, 10);
            System.out.println("FAIL: getRunningTasks succeeded (hidden API accessible)");
            fail++;
        } catch (SecurityException e) {
            System.out.println("PASS: getRunningTasks blocked (SecurityException)");
            pass++;
        } catch (Exception e) {
            if (e.toString().contains("NoSuchMethodError")) {
                System.out.println("PASS: getRunningTasks unavailable (NoSuchMethodError)");
                pass++;
            } else {
                System.out.println("FAIL: getRunningTasks unexpected: " + e);
                fail++;
            }
        }
        try {
            Class<?> pmCls = Class.forName("android.content.pm.PackageManager");
            Method m2 = pmCls.getMethod("getPackageInstaller");
            m2.invoke(pm);
            System.out.println("FAIL: getPackageInstaller succeeded (hidden API)");
            fail++;
        } catch (SecurityException e) {
            System.out.println("PASS: getPackageInstaller blocked");
            pass++;
        } catch (Exception e) {
            if (e.toString().contains("NoSuchMethodError")) {
                System.out.println("PASS: getPackageInstaller unavailable");
                pass++;
            } else {
                System.out.println("FAIL: getPackageInstaller unexpected: " + e);
                fail++;
            }
        }
    }

    private static void checkTestClasses() {
        try {
            Class.forName("android.test.InstrumentationTestRunner");
            System.out.println("FAIL: android.test.InstrumentationTestRunner found");
            fail++;
        } catch (ClassNotFoundException e) {
            System.out.println("PASS: InstrumentationTestRunner not found");
            pass++;
        }
        try {
            Class.forName("android.test.suitebuilder.TestSuiteBuilder");
            System.out.println("FAIL: TestSuiteBuilder found");
            fail++;
        } catch (ClassNotFoundException e) {
            System.out.println("PASS: TestSuiteBuilder not found");
            pass++;
        }
        try {
            Class.forName("android.test.AndroidTestRunner");
            System.out.println("FAIL: AndroidTestRunner found");
            fail++;
        } catch (ClassNotFoundException e) {
            System.out.println("PASS: AndroidTestRunner not found");
            pass++;
        }
    }

    private static void checkTestPermissions() {
        try {
            if (ctx.checkSelfPermission("android.permission.TEST") == PackageManager.PERMISSION_GRANTED) {
                System.out.println("FAIL: TEST permission granted to us");
                fail++;
            } else {
                System.out.println("PASS: TEST permission not granted to us");
                pass++;
            }
        } catch (Exception e) {
            System.out.println("FAIL: TEST permission check error - " + e);
            fail++;
        }
        try {
            if (ctx.checkSelfPermission("android.permission.FACTORY_TEST") == PackageManager.PERMISSION_GRANTED) {
                System.out.println("FAIL: FACTORY_TEST permission granted to us");
                fail++;
            } else {
                System.out.println("PASS: FACTORY_TEST permission not granted to us");
                pass++;
            }
        } catch (Exception e) {
            System.out.println("FAIL: FACTORY_TEST permission check error - " + e);
            fail++;
        }
    }

    private static void checkTestIntents() {
        Intent i = new Intent("android.intent.action.FACTORY_TEST");
        if (i.resolveActivity(pm) == null) {
            System.out.println("PASS: No activity for FACTORY_TEST");
            pass++;
        } else {
            System.out.println("FAIL: Activity resolves FACTORY_TEST");
            fail++;
        }
        Intent i2 = new Intent("android.intent.action.TEST");
        if (i2.resolveActivity(pm) == null) {
            System.out.println("PASS: No activity for TEST");
            pass++;
        } else {
            System.out.println("FAIL: Activity resolves TEST");
            fail++;
        }
    }
}
