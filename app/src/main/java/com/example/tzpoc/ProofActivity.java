package com.example.tzpoc;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class ProofActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // このActivityが起動されたら、SystemCommandReceiverを起動する
        Intent receiverIntent = new Intent(this, SystemCommandReceiver.class);
        sendBroadcast(receiverIntent);
        finish();
    }
}
