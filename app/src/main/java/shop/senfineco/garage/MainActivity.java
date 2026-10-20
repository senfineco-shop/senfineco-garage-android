package shop.senfineco.garage;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Senfineco Garage — a thin, secure wrapper around https://garage.senfineco.shop
 * (worker sign-in with phone + password, no OTP, no Cloudflare Access).
 *
 * - Only garage.senfineco.shop is shown inside the app; every other link opens outside
 *   (browser, WhatsApp, phone dialer).
 * - Supports <input type="file" capture> so the reception inspection can take the
 *   mandatory vehicle photo with the camera.
 * - Keeps cookies (30-day worker session), survives rotation, handles the back button
 *   and shows a friendly offline page with retry.
 * - Makes the site's print buttons work: Android WebView ignores window.print(), so the
 *   page's print() calls are routed to the Android print service (printer or Save as PDF).
 * - Reconnects by itself (Oct 2026, the owner: "after the screen is locked and touched again the Jalan tablet shows
 *   No connection although the internet never dropped"): when the tablet wakes, Wi-Fi needs a moment and a page load in
 *   that moment fails. The offline page now retries on its own — at once when Android reports a network, when the app
 *   comes back to the screen, and every few seconds (1.5 s, 3 s, 5 s, 8 s, then every 15 s) — so nobody has to press
 *   "Try again".
 */
public class MainActivity extends AppCompatActivity {

    static final String HOME_URL = "https://garage.senfineco.shop/";
    static final String APP_HOST = "garage.senfineco.shop";

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_CAMERA_PERMISSION = 2001;

    /** Name of the JS bridge the print hook calls (window.SenfinecoPrint). */
    private static final String PRINT_BRIDGE = "SenfinecoPrint";

    /**
     * Replaces window.print() — a no-op inside Android WebView — with calls to the print bridge.
     * Also covers the common "hidden iframe" pattern (iframe.contentWindow.print()): the iframe's
     * HTML is handed over and printed on its own, so only the invoice / job card is printed.
     */
    private static final String PRINT_HOOK_JS =
            "(function(){"
            + "if(window.__senfPrintHooked)return;"
            + "function hook(w){try{"
            + "if(!w||w.__senfPrintHooked)return;"
            + "w.__senfPrintHooked=true;"
            + "w.print=function(){"
            + "if(w===window){" + PRINT_BRIDGE + ".printPage(document.title||'');}"
            + "else{" + PRINT_BRIDGE + ".printHtml('<!DOCTYPE html>'+w.document.documentElement.outerHTML,"
            + "String(w.location.href),w.document.title||document.title||'');}"
            + "};"
            + "}catch(e){}}"
            + "hook(window);"
            + "var d=Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype,'contentWindow');"
            + "if(d&&d.get){Object.defineProperty(HTMLIFrameElement.prototype,'contentWindow',{"
            + "configurable:true,enumerable:d.enumerable,"
            + "get:function(){var w=d.get.call(this);hook(w);return w;}});}"
            + "})();";

    private FrameLayout root;
    private LinearLayout column;
    private LinearLayout updateBanner;
    private TextView updateText;
    private WebView webView;
    private ProgressBar progressBar;
    private LinearLayout offlineView;

    private boolean mainFrameFailed = false;
    private long offeredUpdateCode = 0;

    // ---- automatic reconnect (see the class comment)
    private static final long[] RETRY_DELAYS_MS = {1500, 3000, 5000, 8000, 15000};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean resumed = false;
    private int retryAttempt = 0;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Runnable autoRetry = () -> {
        if (!resumed || offlineView == null || offlineView.getVisibility() != View.VISIBLE) return;
        if (isOnline()) {
            reloadAfterError();
        } else {
            scheduleRetry();
        }
    };

    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraOutputUri;
    private WebChromeClient.FileChooserParams pendingChooserParams;
    private PermissionRequest pendingWebPermission;

    /** Off-screen view used to print an iframe's content; kept referenced until printing ends. */
    private WebView printView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_orange));
        setContentView(root);

        // Edge-to-edge (Android 15+): keep the page below the status bar and above the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        // Vertical column: [update banner (hidden until a newer build exists)] + [web view].
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        updateBanner = buildUpdateBanner();
        updateBanner.setVisibility(View.GONE);
        column.addView(updateBanner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_bg));
        column.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setIndeterminate(false);
        progressBar.getProgressDrawable().setColorFilter(
                ContextCompat.getColor(this, R.color.brand_orange), android.graphics.PorterDuff.Mode.SRC_IN);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        plp.gravity = Gravity.TOP;
        progressBar.setLayoutParams(plp);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar);

        offlineView = buildOfflineView();
        offlineView.setVisibility(View.GONE);
        root.addView(offlineView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        configureWebView();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (offlineView.getVisibility() != View.VISIBLE && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        if (savedInstanceState != null && webView.restoreState(savedInstanceState) != null) {
            // state restored (rotation / process death)
        } else {
            webView.loadUrl(HOME_URL);
        }

        // Newer build published on GitHub? (skipped automatically for Play Store installs)
        UpdateChecker.check(this, true, this::showUpdateBanner);
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setUserAgentString(s.getUserAgentString() + " SenfinecoGarageApp/" + appVersionName());

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new PrintBridge(), PRINT_BRIDGE);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                mainFrameFailed = false;
                progressBar.setProgress(5);
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                if (!mainFrameFailed) {
                    showOffline(false);
                    retryAttempt = 0;
                    handler.removeCallbacks(autoRetry);
                }
                if (isAppUrl(url)) {
                    view.evaluateJavascript(PRINT_HOOK_JS, null);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    mainFrameFailed = true;
                    showOffline(true);
                    scheduleRetry(); // usually Wi-Fi waking up after the screen was locked
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                if (acceptsImages(params) && !hasCameraPermission()) {
                    pendingChooserParams = params;
                    ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
                    return true;
                }
                openChooser(params);
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                boolean wantsCamera = false;
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) wantsCamera = true;
                }
                if (!wantsCamera) {
                    request.deny();
                    return;
                }
                if (hasCameraPermission()) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                } else {
                    pendingWebPermission = request;
                    ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
                }
            }
        });

        // Invoices / PDFs / exports: hand them to the system (browser or viewer).
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                openExternal(Uri.parse(url)));
    }

    // ---------------------------------------------------------------- navigation

    /** @return true when the URL was handled outside the WebView. */
    private boolean handleUrl(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null) return false;
        if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
            if (host != null && host.equalsIgnoreCase(APP_HOST) && "https".equalsIgnoreCase(scheme)) {
                return false; // stay inside the app
            }
            openExternal(uri); // wa.me, other sites → outside
            return true;
        }
        if ("intent".equalsIgnoreCase(scheme)) {
            try {
                Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        // tel:, mailto:, whatsapp:, sms:, geo: ...
        openExternal(uri);
        return true;
    }

    private void openExternal(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show();
        }
    }

    private static boolean isAppUrl(@Nullable String url) {
        if (url == null) return false;
        Uri u = Uri.parse(url);
        return "https".equalsIgnoreCase(u.getScheme()) && APP_HOST.equalsIgnoreCase(u.getHost());
    }

    // ---------------------------------------------------------------- printing

    /** Called from the page's JavaScript (see PRINT_HOOK_JS) on a background thread. */
    private final class PrintBridge {
        @JavascriptInterface
        public void printPage(String title) {
            runOnUiThread(() -> {
                if (!isAppUrl(webView.getUrl())) return;
                printWebView(webView, title);
            });
        }

        @JavascriptInterface
        public void printHtml(String html, String frameUrl, String title) {
            runOnUiThread(() -> {
                if (!isAppUrl(webView.getUrl()) || html == null) return;
                printDocument(html, isAppUrl(frameUrl) ? frameUrl : HOME_URL, title);
            });
        }
    }

    /** Loads the HTML into an off-screen WebView (no JS) and prints it once it has loaded. */
    private void printDocument(String html, String baseUrl, String title) {
        if (printView != null) printView.destroy();
        WebView view = new WebView(this);
        view.getSettings().setJavaScriptEnabled(false);
        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                return true; // printing only — no navigation
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (v != printView || v.getTag() != null) return;
                v.setTag(Boolean.TRUE); // print once
                printWebView(v, title);
            }
        });
        printView = view;
        view.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null);
    }

    private void printWebView(WebView view, @Nullable String title) {
        if (isFinishing() || isDestroyed()) return;
        PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
        if (pm == null) {
            Toast.makeText(this, R.string.print_failed, Toast.LENGTH_LONG).show();
            return;
        }
        String jobName = (title == null || title.trim().isEmpty())
                ? getString(R.string.app_name) : title.trim();
        try {
            PrintDocumentAdapter adapter = view.createPrintDocumentAdapter(jobName);
            pm.print(jobName, adapter, new PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .build());
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.print_failed, Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- file chooser / camera

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean acceptsImages(WebChromeClient.FileChooserParams params) {
        String[] types = params.getAcceptTypes();
        if (types == null || types.length == 0) return true; // any file → offer camera too
        for (String t : types) {
            if (t == null) continue;
            String v = t.trim().toLowerCase(Locale.ROOT);
            if (v.isEmpty() || v.equals("*/*") || v.startsWith("image") || v.startsWith(".jp") || v.startsWith(".png")) {
                return true;
            }
        }
        return false;
    }

    private void openChooser(WebChromeClient.FileChooserParams params) {
        Intent cameraIntent = null;
        cameraOutputUri = null;
        if (acceptsImages(params) && hasCameraPermission()) {
            try {
                File dir = new File(getCacheDir(), "camera");
                if (!dir.exists()) dir.mkdirs();
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File photo = File.createTempFile("vehicle_" + stamp + "_", ".jpg", dir);
                cameraOutputUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photo);
                cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri);
                cameraIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (IOException e) {
                cameraIntent = null;
                cameraOutputUri = null;
            }
        }

        Intent content = new Intent(Intent.ACTION_GET_CONTENT);
        content.addCategory(Intent.CATEGORY_OPENABLE);
        String[] accept = params.getAcceptTypes();
        String mime = "*/*";
        if (accept != null && accept.length == 1 && accept[0] != null && accept[0].contains("/")) {
            mime = accept[0].trim();
        } else if (accept != null && accept.length > 1) {
            content.putExtra(Intent.EXTRA_MIME_TYPES, accept);
        }
        content.setType(mime);
        if (params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            content.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }

        Intent toStart;
        if (params.isCaptureEnabled() && cameraIntent != null) {
            toStart = cameraIntent; // <input capture> → straight to the camera
        } else {
            toStart = Intent.createChooser(content, getString(R.string.choose_file));
            if (cameraIntent != null) {
                toStart.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});
            }
        }
        try {
            startActivityForResult(toStart, REQ_FILE_CHOOSER);
        } catch (ActivityNotFoundException e) {
            cancelFileChooser();
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show();
        }
    }

    private void cancelFileChooser() {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        cameraOutputUri = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK) {
                if (data != null && data.getClipData() != null) {
                    ClipData clip = data.getClipData();
                    results = new Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        results[i] = clip.getItemAt(i).getUri();
                    }
                } else if (data != null && data.getData() != null) {
                    results = new Uri[]{data.getData()};
                } else if (cameraOutputUri != null) {
                    results = new Uri[]{cameraOutputUri}; // photo taken with the camera
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            cameraOutputUri = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CAMERA_PERMISSION) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show();
        }
        if (pendingChooserParams != null) {
            WebChromeClient.FileChooserParams p = pendingChooserParams;
            pendingChooserParams = null;
            openChooser(p); // without camera if denied — the gallery still works
        }
        if (pendingWebPermission != null) {
            PermissionRequest r = pendingWebPermission;
            pendingWebPermission = null;
            if (granted) {
                r.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            } else {
                r.deny();
            }
        }
    }

    // ---------------------------------------------------------------- offline page

    private LinearLayout buildOfflineView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_bg));
        int pad = dp(32);
        box.setPadding(pad, pad, pad, pad);

        TextView icon = new TextView(this);
        icon.setText("📡"); // 📡
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56);
        icon.setGravity(Gravity.CENTER);
        box.addView(icon);

        TextView title = new TextView(this);
        title.setText(R.string.offline_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.text_dark));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(16), 0, dp(8));
        box.addView(title);

        TextView msg = new TextView(this);
        msg.setText(R.string.offline_message);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        msg.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        msg.setGravity(Gravity.CENTER);
        box.addView(msg);

        TextView auto = new TextView(this);
        auto.setText(R.string.offline_auto);
        auto.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        auto.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        auto.setGravity(Gravity.CENTER);
        auto.setPadding(0, dp(10), 0, 0);
        box.addView(auto);

        Button retry = new Button(this);
        retry.setText(R.string.retry);
        retry.setAllCaps(false);
        retry.setTextColor(Color.WHITE);
        retry.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_orange));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(24);
        retry.setLayoutParams(blp);
        retry.setPadding(dp(32), dp(12), dp(32), dp(12));
        retry.setOnClickListener(v -> {
            mainFrameFailed = false;
            retryAttempt = 0;
            handler.removeCallbacks(autoRetry);
            showOffline(false);
            String current = webView.getUrl();
            webView.loadUrl(current != null && current.startsWith("https://" + APP_HOST) ? current : HOME_URL);
        });
        box.addView(retry);
        return box;
    }

    private void showOffline(boolean offline) {
        offlineView.setVisibility(offline ? View.VISIBLE : View.GONE);
        webView.setVisibility(offline ? View.INVISIBLE : View.VISIBLE);
    }

    // ---------------------------------------------------------------- automatic reconnect

    /** Next quiet retry while the offline page is shown and the app is on screen. */
    private void scheduleRetry() {
        handler.removeCallbacks(autoRetry);
        if (!resumed) return; // retried in onResume
        long delay = RETRY_DELAYS_MS[Math.min(retryAttempt, RETRY_DELAYS_MS.length - 1)];
        retryAttempt++;
        handler.postDelayed(autoRetry, delay);
    }

    /** Retry soon (a network just appeared, or the app came back to the screen). */
    private void retrySoon(long delayMs) {
        if (offlineView == null || offlineView.getVisibility() != View.VISIBLE) return;
        retryAttempt = 0;
        handler.removeCallbacks(autoRetry);
        handler.postDelayed(autoRetry, delayMs);
    }

    /** Loads the page again; the offline page stays until it has loaded (no flash of an error page). */
    private void reloadAfterError() {
        mainFrameFailed = false;
        String current = webView.getUrl();
        webView.loadUrl(current != null && current.startsWith("https://" + APP_HOST) ? current : HOME_URL);
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (RuntimeException e) {
            return true; // unknown → just try
        }
    }

    private void watchNetwork() {
        if (networkCallback != null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return;
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    handler.post(() -> { if (resumed) retrySoon(700); });
                }
            };
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException e) {
            networkCallback = null; // the timed retries still work
        }
    }

    private void unwatchNetwork() {
        if (networkCallback == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(networkCallback);
        } catch (RuntimeException e) {
            // already gone
        }
        networkCallback = null;
    }

    // ---------------------------------------------------------------- in-app update banner

    private LinearLayout buildUpdateBanner() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_orange_dark));
        bar.setPadding(dp(14), dp(10), dp(14), dp(10));

        updateText = new TextView(this);
        updateText.setTextColor(Color.WHITE);
        updateText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        updateText.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(updateText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button later = new Button(this);
        later.setText(R.string.update_later);
        later.setAllCaps(false);
        later.setTextColor(Color.WHITE);
        later.setBackgroundColor(Color.TRANSPARENT);
        later.setMinWidth(0);
        later.setMinimumWidth(0);
        later.setPadding(dp(10), dp(6), dp(10), dp(6));
        later.setOnClickListener(v -> {
            if (offeredUpdateCode > 0) UpdateChecker.snooze(this, offeredUpdateCode);
            updateBanner.setVisibility(View.GONE);
        });
        bar.addView(later);

        Button update = new Button(this);
        update.setText(R.string.update_now);
        update.setAllCaps(false);
        update.setTextColor(ContextCompat.getColor(this, R.color.brand_orange_dark));
        update.setBackgroundColor(Color.WHITE);
        update.setMinWidth(0);
        update.setMinimumWidth(0);
        update.setPadding(dp(16), dp(6), dp(16), dp(6));
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.setMarginStart(dp(6));
        update.setLayoutParams(ulp);
        update.setOnClickListener(v -> {
            Toast.makeText(this, R.string.update_downloading_hint, Toast.LENGTH_LONG).show();
            openExternal(Uri.parse(UpdateChecker.APK_URL)); // browser downloads → tap to install
        });
        bar.addView(update);
        return bar;
    }

    private void showUpdateBanner(long latestCode, String latestName) {
        if (isFinishing() || isDestroyed()) return;
        offeredUpdateCode = latestCode;
        updateText.setText(getString(R.string.update_available, latestName));
        updateBanner.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(autoRetry);
        unwatchNetwork();
        CookieManager.getInstance().flush();
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        webView.onResume();
        watchNetwork();
        retrySoon(500); // the screen was locked and the page could not load meanwhile → try again at once
        // Tablets stay open for days: re-check quietly (rate-limited inside UpdateChecker).
        UpdateChecker.check(this, false, this::showUpdateBanner);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(autoRetry);
        unwatchNetwork();
        if (printView != null) {
            printView.destroy();
            printView = null;
        }
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- helpers

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    private String appVersionName() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return v == null ? "1" : v;
        } catch (PackageManager.NameNotFoundException e) {
            return "1";
        }
    }
}
