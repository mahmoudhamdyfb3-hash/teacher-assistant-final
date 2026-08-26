package com.mahmoud.teacherassistant;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;

public class MainActivity extends Activity {
    private static final int REQ_NOTIFICATIONS = 3101;
    private static final String PROD_BANNER_AD_UNIT_ID =
            "ca-app-pub-1215580137922140/4792811578";
    private static final String TEST_BANNER_AD_UNIT_ID =
            "ca-app-pub-3940256099942544/9214589741";

    private WebView webView;
    private AdView adView;
    private String lastLessonsJson;
    private int lastNotifyMinutes = -1;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        NotificationScheduler.ensureChannel(this);
        requestNotificationPermissionIfNeeded();
        MobileAds.initialize(this, initializationStatus -> {});

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        webView = new WebView(this);
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(webView, webParams);

        adView = new AdView(this);
        adView.setAdUnitId(BuildConfig.ADS_TEST_MODE
                ? TEST_BANNER_AD_UNIT_ID
                : PROD_BANNER_AD_UNIT_ID);

        int widthDp = Math.max(1, Math.round(
                getResources().getDisplayMetrics().widthPixels /
                        getResources().getDisplayMetrics().density));

        adView.setAdSize(
                AdSize.getLargeAnchoredAdaptiveBannerAdSize(this, widthDp));

        LinearLayout.LayoutParams adParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(adView, adParams);

        setContentView(root);
        adView.loadAd(new AdRequest.Builder().build());

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new AndroidNotifications(),
                "AndroidNotifications");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                return openExternalIfNeeded(request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view, String url) {
                return openExternalIfNeeded(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                installNotificationSync(view);
            }
        });

        webView.loadUrl("file:///android_asset/www/index.html");
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
        }
    }

    private void installNotificationSync(WebView view) {
        String js =
                "(function(){"
                        + "try{"
                        + "var last='';"
                        + "function sync(){"
                        + "try{"
                        + "var lessons=localStorage.getItem('lessons')||'[]';"
                        + "var mins=parseInt(localStorage.getItem('notify_minutes')||'15',10);"
                        + "if(!isFinite(mins)||mins<1)mins=15;"
                        + "var sig=lessons+'|'+mins;"
                        + "if(sig!==last){"
                        + "last=sig;"
                        + "if(window.AndroidNotifications)"
                        + "window.AndroidNotifications.scheduleLessons(lessons,mins);"
                        + "}"
                        + "}catch(e){}"
                        + "}"
                        + "sync();"
                        + "setInterval(sync,1500);"
                        + "}catch(e){}"
                        + "})();";

        view.evaluateJavascript(js, null);
    }

    private class AndroidNotifications {
        @JavascriptInterface
        public void scheduleLessons(String lessonsJson, int notifyMinutes) {
            runOnUiThread(() -> {
                try {
                    requestNotificationPermissionIfNeeded();

                    String safeJson =
                            lessonsJson == null || lessonsJson.trim().isEmpty()
                                    ? "[]" : lessonsJson;
                    int safeMinutes = Math.max(1, notifyMinutes);

                    if (safeJson.equals(lastLessonsJson) &&
                            safeMinutes == lastNotifyMinutes) {
                        return;
                    }

                    lastLessonsJson = safeJson;
                    lastNotifyMinutes = safeMinutes;

                    NotificationScheduler.ensureChannel(MainActivity.this);
                    NotificationScheduler.saveAndSchedule(
                            MainActivity.this, safeJson, safeMinutes);
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public void requestNotificationPermission() {
            runOnUiThread(() -> requestNotificationPermissionIfNeeded());
        }

        @JavascriptInterface
        public void openNotificationSettings() {
            try {
                Intent intent = new Intent();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                } else {
                    intent.setAction(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                }
                startActivity(intent);
            } catch (Exception ignored) {
            }
        }
    }

    private boolean openExternalIfNeeded(String url) {
        if (url.startsWith("file://") || url.startsWith("about:")) return false;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        requestNotificationPermissionIfNeeded();
        NotificationScheduler.scheduleAll(this);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (adView != null) adView.destroy();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
