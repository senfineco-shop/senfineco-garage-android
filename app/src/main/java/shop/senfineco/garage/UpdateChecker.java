package shop.senfineco.garage;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Checks GitHub Releases for a newer build of this app.
 *
 * Every successful CI build publishes a release whose assets keep FIXED names, so these two
 * links never change:
 *   {@link #VERSION_JSON_URL} → {"versionCode": N, "versionName": "1.0.N", ...}
 *   {@link #APK_URL}          → the newest signed APK
 *
 * The check is skipped when the app was installed from Google Play (Play re-signs the app with
 * its own key, so a GitHub APK could not be installed over it — Play delivers those updates).
 */
final class UpdateChecker {

    static final String REPO = "senfineco-shop/senfineco-garage-android";
    static final String VERSION_JSON_URL = "https://github.com/" + REPO + "/releases/latest/download/version.json";
    static final String APK_URL = "https://github.com/" + REPO + "/releases/latest/download/senfineco-garage.apk";

    /** Do not hit the network more often than this while the app stays open. */
    private static final long MIN_INTERVAL_MS = 3L * 60 * 60 * 1000; // 3 hours
    /** After "Later", stay quiet about the same version for this long. */
    private static final long SNOOZE_MS = 24L * 60 * 60 * 1000;      // 24 hours

    private static final String PREFS = "updates";
    private static final String K_LAST_CHECK = "last_check";
    private static final String K_LATEST_CODE = "latest_code";
    private static final String K_LATEST_NAME = "latest_name";
    private static final String K_SNOOZED_CODE = "snoozed_code";
    private static final String K_SNOOZED_AT = "snoozed_at";

    interface Listener {
        void onUpdateAvailable(long latestCode, @NonNull String latestName);
    }

    static final class Result {
        final long versionCode;
        final String versionName;
        Result(long c, String n) { versionCode = c; versionName = n; }
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UpdateChecker() {}

    /** Runs the check in the background; the listener is called on the main thread only when a newer build exists. */
    static void check(@NonNull Context ctx, boolean force, @NonNull Listener listener) {
        final Context app = ctx.getApplicationContext();
        if (installedFromPlay(app)) return;

        final long current = currentVersionCode(app);
        final SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final long now = System.currentTimeMillis();

        // Cached answer first (instant banner on cold start without waiting for the network).
        long cachedCode = p.getLong(K_LATEST_CODE, 0);
        String cachedName = p.getString(K_LATEST_NAME, "");
        if (cachedCode > current && !isSnoozed(p, cachedCode, now)) {
            listener.onUpdateAvailable(cachedCode, cachedName);
        }

        if (!force && now - p.getLong(K_LAST_CHECK, 0) < MIN_INTERVAL_MS) return;
        p.edit().putLong(K_LAST_CHECK, now).apply(); // throttle concurrent callers (onCreate + onResume)

        EXEC.execute(() -> {
            Result r = fetchLatest();
            if (r == null) return;
            p.edit().putLong(K_LATEST_CODE, r.versionCode)
                    .putString(K_LATEST_NAME, r.versionName)
                    .apply();
            if (r.versionCode > current && r.versionCode != cachedCode
                    && !isSnoozed(p, r.versionCode, System.currentTimeMillis())) {
                MAIN.post(() -> listener.onUpdateAvailable(r.versionCode, r.versionName));
            }
        });
    }

    /** "Later" was tapped: hide this version for a day. */
    static void snooze(@NonNull Context ctx, long versionCode) {
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(K_SNOOZED_CODE, versionCode)
                .putLong(K_SNOOZED_AT, System.currentTimeMillis())
                .apply();
    }

    private static boolean isSnoozed(SharedPreferences p, long code, long now) {
        return p.getLong(K_SNOOZED_CODE, -1) == code
                && now - p.getLong(K_SNOOZED_AT, 0) < SNOOZE_MS;
    }

    @Nullable
    private static Result fetchLatest() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(VERSION_JSON_URL).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Cache-Control", "no-cache");
            if (c.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null && sb.length() < 8192) sb.append(line);
            }
            JSONObject o = new JSONObject(sb.toString());
            long code = o.optLong("versionCode", 0);
            if (code <= 0) return null;
            return new Result(code, o.optString("versionName", "1.0." + code));
        } catch (Exception e) {
            return null; // offline / GitHub unreachable → silently try again later
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static long currentVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? pi.getLongVersionCode() : pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    @SuppressWarnings("deprecation")
    static boolean installedFromPlay(Context ctx) {
        try {
            String installer;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                installer = ctx.getPackageManager().getInstallSourceInfo(ctx.getPackageName()).getInstallingPackageName();
            } else {
                installer = ctx.getPackageManager().getInstallerPackageName(ctx.getPackageName());
            }
            return "com.android.vending".equals(installer);
        } catch (Exception e) {
            return false;
        }
    }
}
