package shop.senfineco.garage;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
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
 */
public class MainActivity extends AppCompatActivity {

    static final String HOME_URL = "https://garage.senfineco.shop/";
    static final String APP_HOST = "garage.senfineco.shop";

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_CAMERA_PERMISSION = 2001;

    private FrameLayout root;
    private LinearLayout column;
    private LinearLayout updateBanner;
    private TextView updateText;
    private WebView webView;
    private ProgressBar progressBar;
    private LinearLayout offlineView;

    private boolean mainFrameFailed = false;
    private long offeredUpdateCode = 0;

    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraOutputUri;
    private WebChromeClient.FileChooserParams pendingChooserParams;
    private PermissionRequest pendingWebPermission;

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
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    mainFrameFailed = true;
                    showOffline(true);
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
        CookieManager.getInstance().flush();
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        // Tablets stay open for days: re-check quietly (rate-limited inside UpdateChecker).
        UpdateChecker.check(this, false, this::showUpdateBanner);
    }

    @Override
    protected void onDestroy() {
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
