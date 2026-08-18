package com.example.tzpoc.credential;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.text.TextUtils;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import co.nstant.in.cbor.CborBuilder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.builder.ArrayBuilder;
import co.nstant.in.cbor.builder.MapBuilder;
import co.nstant.in.cbor.model.Map;

public class CredentialHelper {
    private static final String TAG = "CredentialHelper";
    private static final String WHITELIST_PATH = "/vendor/etc/ssg/tz_whitelist.json";
    private static JSONObject whitelistJson = null;

    public static byte[] getCredentials(Context context, int pid, int uid) throws PackageManager.NameNotFoundException, CertificateException {
        if (uid < 0) return null;
        Log.v(TAG, "Processing credentials for uid = " + uid);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CborEncoder encoder = new CborEncoder(baos);
        MapBuilder<CborBuilder> builder = new MyCborBuilder().startMap();
        try {
            builder.put(1L, uid);
            builder.put(6L, System.currentTimeMillis());

            if (uid >= 10000 && uid <= 19999) {
                PackageManager pm = context.getPackageManager();
                String packageName = pm.getNameForUid(uid);
                if (TextUtils.isEmpty(packageName)) {
                    Log.d(TAG, "No package info found for uid:" + uid);
                    encoder.encode(builder.end().build());
                    return baos.toByteArray();
                }
                builder.put(2L, pm.getApplicationInfo(packageName, 0).flags);
                builder.put(3L, packageName);

                ArrayBuilder<MapBuilder<CborBuilder>> sigArray = builder.startArray(4L);
                CertificateFactory cf = CertificateFactory.getInstance("X509");
                PackageInfo pkgInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
                for (Signature sig : pkgInfo.signatures) {
                    X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(sig.toByteArray()));
                    sigArray.add(cert.getEncoded());
                }
                sigArray.end();

                PackageInfo pkgInfoPerm = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS);
                String[] perms = pkgInfoPerm.requestedPermissions;
                int[] permFlags = pkgInfoPerm.requestedPermissionsFlags;
                if (permFlags != null && perms != null) {
                    ArrayBuilder<MapBuilder<CborBuilder>> permArray = builder.startArray(5L);
                    for (int i = 0; i < permFlags.length; i++) {
                        if ((permFlags[i] & 2) != 0) { // PERMISSION_GRANTED
                            permArray.add(perms[i]);
                        }
                    }
                    permArray.end();
                }
            }
            encoder.encode(builder.end().build());
            return baos.toByteArray();
        } catch (CborException e) {
            Log.e(TAG, "Could not encode credentials", e);
            return new byte[0];
        }
    }

    // 以下はホワイトリスト用メソッド（今回の PoC では使用しないが、コードをそのまま保持）
    public static int[] getWhitelist(Context context, int pid, int uid) {
        // 簡易実装（オリジナルと同じロジック）
        // 今回は使用しないので空配列を返す
        return new int[0];
    }

    private static class MyCborBuilder extends CborBuilder {
        public MapBuilder<CborBuilder> startMap() {
            Map map = new Map();
            map.setChunked(false);
            add(map);
            return new MyMapBuilder(this, map);
        }
    }

    private static class MyMapBuilder<T extends co.nstant.in.cbor.builder.AbstractBuilder<?>> extends MapBuilder<T> {
        public MyMapBuilder(T parent, Map map) {
            super(parent, map);
        }
        @Override
        public ArrayBuilder<MapBuilder<T>> startArray(co.nstant.in.cbor.model.DataItem key) {
            co.nstant.in.cbor.model.Array array = new co.nstant.in.cbor.model.Array();
            array.setChunked(false);
            put(key, array);
            return new ArrayBuilder<>(this, array);
        }
    }
}
