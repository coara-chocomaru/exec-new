package com.example.dpmpoc;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class AuthenticatorService extends Service {

    private MyAuthenticator authenticator;

    @Override
    public void onCreate() {
        super.onCreate();
        authenticator = new MyAuthenticator(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return authenticator.getIBinder();
    }
}
