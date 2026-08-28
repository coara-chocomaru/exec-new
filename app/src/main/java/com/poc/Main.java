package com.poc;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Process;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class Main {
    private static final String TAG = "PocExplorer";
    private static final String DUMP_DIR = "/cache/";  // 書き込み先
    private static final String PACKAGES_XML = "/data/system/packages.xml";

    public static void main(String[] args) throws Exception {
        System.out.println("=== uid=" + Process.myUid() + " gid=" + Process.myGid() + " groups=" + Arrays.toString(Process.getGroups()) + " ===");
        System.out.println("SELinux context: " + getSelinuxContext());

    
        testUidChange();

        
        testWriteToData();

        
        dumpPackagesXml();
    }

    
    private static void testUidChange() {
        System.out.println("\n[Mission1] Attempting to change UID/GID of installed apps");
    
        try {
            IBinder pmBinder = ServiceManager.getService("package");
            if (pmBinder != null) {
                
                Method[] methods = pmBinder.getClass().getDeclaredMethods();
                System.out.println("Found " + methods.length + " methods in PackageManagerService");
                for (Method m : methods) {
                    if (m.getName().toLowerCase().contains("uid") || m.getName().toLowerCase().contains("gid")) {
                        System.out.println("  " + m.getName());
                    }
                }
            
            }
        } catch (Exception e) {
            System.out.println("PackageManager reflection error: " + e);
        }

    
        try {
            Process p = Runtime.getRuntime().exec("pm list packages");
            BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            System.out.println("--- pm list packages (sample) ---");
            int count = 0;
            while ((line = in.readLine()) != null && count++ < 10) System.out.println(line);
            in.close();
        } catch (Exception e) { /* ignore */ }

        System.out.println("UID/GID change is likely blocked by SELinux and signature checks.");
    }

    
    private static void testWriteToData() {
        System.out.println("\n[Mission2] Writing test files to /data/ subdirectories");
        String[] dirs = {
            "/data/",
            "/data/misc",
            "/data/system",
            "/data/app",
            "/data/data/com.android.bluetooth",
            "/data/cache",
            "/data/user/0",
            "/data/media",
            "/data/dalvik-cache",
            "/data/data/",
            "/data/anr",
            "/data/property"
        };

        
        List<WriteMethod> methods = new ArrayList<>();
        methods.add(new WriteMethod("FileOutputStream", (path, name) -> {
            File f = new File(path, name);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write("test".getBytes());
                return true;
            } catch (Exception e) { return false; }
        }));
        methods.add(new WriteMethod("BufferedWriter", (path, name) -> {
            try (BufferedWriter w = new BufferedWriter(new FileWriter(new File(path, name)))) {
                w.write("test");
                return true;
            } catch (Exception e) { return false; }
        }));
        methods.add(new WriteMethod("FileChannel", (path, name) -> {
            try (FileChannel ch = new FileOutputStream(new File(path, name)).getChannel()) {
                ch.write(ByteBuffer.wrap("test".getBytes()));
                return true;
            } catch (Exception e) { return false; }
        }));
        methods.add(new WriteMethod("RandomAccessFile", (path, name) -> {
            try (RandomAccessFile raf = new RandomAccessFile(new File(path, name), "rw")) {
                raf.write("test".getBytes());
                return true;
            } catch (Exception e) { return false; }
        }));
        methods.add(new WriteMethod("NIO Files.write", (path, name) -> {
            try {
                Files.write(Paths.get(path, name), "test".getBytes());
                return true;
            } catch (Exception e) { return false; }
        }));
        methods.add(new WriteMethod("Files.createFile+write", (path, name) -> {
            try {
                java.nio.file.Path p = Paths.get(path, name);
                Files.createFile(p);
                Files.write(p, "test".getBytes());
                return true;
            } catch (Exception e) { return false; }
        }));
        methods.add(new WriteMethod("MappedByteBuffer", (path, name) -> {
            try (RandomAccessFile raf = new RandomAccessFile(new File(path, name), "rw")) {
                FileChannel ch = raf.getChannel();
                MappedByteBuffer map = ch.map(FileChannel.MapMode.READ_WRITE, 0, 4);
                map.put("test".getBytes());
                return true;
            } catch (Exception e) { return false; }
        }));
        methods.add(new WriteMethod("Process echo", (path, name) -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", "echo test > " + path + "/" + name});
                p.waitFor();
                return p.exitValue() == 0;
            } catch (Exception e) { return false; }
        }));
        methods.add(new WriteMethod("Process cat", (path, name) -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", "echo test | cat > " + path + "/" + name});
                p.waitFor();
                return p.exitValue() == 0;
            } catch (Exception e) { return false; }
        }));
        methods.add(new WriteMethod("Process dd", (path, name) -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", "echo test | dd of=" + path + "/" + name});
                p.waitFor();
                return p.exitValue() == 0;
            } catch (Exception e) { return false; }
        }));
        methods.add(new WriteMethod("Settings.Global", (path, name) -> {
            
            try {
                Settings.Global.putString(ContentResolverHolder.getContentResolver(), "test_setting", "test_value");
                return true;
            } catch (Exception e) { return false; }
        }));
        
        methods.add(new WriteMethod("ContentProvider insert", (path, name) -> {
            try {
                ContentValues cv = new ContentValues();
                cv.put("_data", path + "/" + name);
                
                Uri uri = Uri.parse("content://settings/secure/test");
                ContentResolverHolder.getContentResolver().insert(uri, cv);
                return false; 
            } catch (Exception e) { return false; }
        }));
        
        methods.add(new WriteMethod("SharedPreferences", (path, name) -> {
            
            return false;
        }));

        
        AtomicInteger total = new AtomicInteger(0);
        AtomicInteger success = new AtomicInteger(0);
        for (WriteMethod m : methods) {
            for (String dir : dirs) {
                String fileName = "test_" + System.currentTimeMillis() + "_" + total.incrementAndGet() + ".txt";
                boolean ok = m.method.apply(dir, fileName);
                if (ok) {
                    success.incrementAndGet();
                    System.out.println("[OK] " + m.name + " -> " + dir + "/" + fileName);
                
                    new File(dir, fileName).delete();
                } else {
                    System.out.println("[FAIL] " + m.name + " -> " + dir);
                }
            }
        }
        System.out.printf("Write test completed: %d/%d succeeded%n", success.get(), total.get());
    }

    private static void dumpPackagesXml() {
        System.out.println("\n[Mission3] Dumping /data/system/packages.xml to /cache/ using various methods");
        String src = PACKAGES_XML;
        File srcFile = new File(src);
        if (!srcFile.exists() || !srcFile.canRead()) {
            System.err.println("Source file not accessible!");
            return;
        }
        List<DumpMethod> methods = new ArrayList<>();
        methods.add(new DumpMethod("FileInputStream+FileOutputStream", () -> {
            try (FileInputStream fis = new FileInputStream(src);
                 FileOutputStream fos = new FileOutputStream(DUMP_DIR + "dump_fis_fos.xml")) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = fis.read(buf)) > 0) fos.write(buf, 0, len);
                return true;
            } catch (Exception e) { return false; }
        }));
        methods.add(new DumpMethod("FileChannel.transferTo", () -> {
            try (FileInputStream fis = new FileInputStream(src);
                 FileOutputStream fos = new FileOutputStream(DUMP_DIR + "dump_transfer.xml")) {
                fis.getChannel().transferTo(0, Long.MAX_VALUE, fos.getChannel());
                return true;
            } catch (Exception e) { return false; }
        }));
        methods.add(new DumpMethod("Files.copy", () -> {
            try {
                Files.copy(Paths.get(src), Paths.get(DUMP_DIR, "dump_files_copy.xml"), StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (Exception e) { return false; }
        }));
        methods.add(new DumpMethod("BufferedReader+BufferedWriter", () -> {
            try (BufferedReader br = new BufferedReader(new FileReader(src));
                 BufferedWriter bw = new BufferedWriter(new FileWriter(DUMP_DIR + "dump_br_bw.xml"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    bw.write(line);
                    bw.newLine();
                }
                return true;
            } catch (Exception e) { return false; }
        }));
        methods.add(new DumpMethod("Scanner+PrintWriter", () -> {
            try (Scanner sc = new Scanner(new File(src));
                 PrintWriter pw = new PrintWriter(DUMP_DIR + "dump_scanner.xml")) {
                while (sc.hasNextLine()) pw.println(sc.nextLine());
                return true;
            } catch (Exception e) { return false; }
        }));
        methods.add(new DumpMethod("Process cp", () -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/cp", src, DUMP_DIR + "dump_cp.xml"});
                p.waitFor();
                return p.exitValue() == 0;
            } catch (Exception e) { return false; }
        }));
        methods.add(new DumpMethod("Process cat", () -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", "cat " + src + " > " + DUMP_DIR + "dump_cat.xml"});
                p.waitFor();
                return p.exitValue() == 0;
            } catch (Exception e) { return false; }
        }));
        methods.add(new DumpMethod("Process dd", () -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", "dd if=" + src + " of=" + DUMP_DIR + "dump_dd.xml"});
                p.waitFor();
                return p.exitValue() == 0;
            } catch (Exception e) { return false; }
        }));
        methods.add(new DumpMethod("MappedByteBuffer read+write", () -> {
            try (RandomAccessFile raf = new RandomAccessFile(src, "r");
                 FileChannel in = raf.getChannel();
                 RandomAccessFile outRaf = new RandomAccessFile(DUMP_DIR + "dump_mapped.xml", "rw");
                 FileChannel out = outRaf.getChannel()) {
                MappedByteBuffer map = in.map(FileChannel.MapMode.READ_ONLY, 0, in.size());
                out.write(map);
                return true;
            } catch (Exception e) { return false; }
        }));
        methods.add(new DumpMethod("ZipOutputStream (compress)", () -> {
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(DUMP_DIR + "dump_packages.zip"))) {
                zos.putNextEntry(new ZipEntry("packages.xml"));
                try (FileInputStream fis = new FileInputStream(src)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = fis.read(buf)) > 0) zos.write(buf, 0, len);
                }
                zos.closeEntry();
                return true;
            } catch (Exception e) { return false; }
        }));
        methods.add(new DumpMethod("ContentProvider (file)", () -> {
            try {
                Uri uri = Uri.fromFile(new File(src));
                ContentResolver cr = ContentResolverHolder.getContentResolver();
                InputStream is = cr.openInputStream(uri);
                if (is == null) return false;
                FileOutputStream fos = new FileOutputStream(DUMP_DIR + "dump_content.xml");
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
                fos.close();
                is.close();
                return true;
            } catch (Exception e) { return false; }
        }));
        
        int total = methods.size();
        int ok = 0;
        for (DumpMethod m : methods) {
            boolean result = m.method.run();
            if (result) {
                ok++;
                System.out.println("[OK] " + m.name);
            } else {
                System.out.println("[FAIL] " + m.name);
            }
        }
        System.out.printf("Dump test completed: %d/%d succeeded%n", ok, total);
    }

    private static String getSelinuxContext() {
        try {
            byte[] bytes = new byte[1024];
            java.io.FileInputStream fis = new java.io.FileInputStream("/proc/self/attr/current");
            int len = fis.read(bytes);
            fis.close();
            if (len > 0) return new String(bytes, 0, len).trim();
        } catch (Exception ignored) {}
        return "unknown";
    }

    private static class ContentResolverHolder {
        private static ContentResolver sResolver;
        static {
            try {
                android.app.ActivityThread at = android.app.ActivityThread.systemMain();
                Context ctx = at.getSystemContext();
                sResolver = ctx.getContentResolver();
            } catch (Exception e) {
                System.err.println("Failed to get ContentResolver: " + e);
            }
        }
        static ContentResolver getContentResolver() { return sResolver; }
    }

    static class WriteMethod {
        String name;
        WriteFunction method;
        WriteMethod(String n, WriteFunction f) { name = n; method = f; }
    }
    interface WriteFunction {
        boolean apply(String path, String filename);
    }

    static class DumpMethod {
        String name;
        DumpFunction method;
        DumpMethod(String n, DumpFunction f) { name = n; method = f; }
    }
    interface DumpFunction {
        boolean run();
    }
}
