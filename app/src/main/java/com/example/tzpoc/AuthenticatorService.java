package com.example.tzpoc;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class AuthenticatorService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        MyAuthenticator authenticator = new MyAuthenticator(this);
        return authenticator.getIBinder();
    }
}
