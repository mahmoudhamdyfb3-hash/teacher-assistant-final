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
import android.util.Log;
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
    private static final String TAG = "TeacherAssistantNotif";
    private static final int REQ_NOTIFICATIONS = 3101;

    private static final String PROD_BANNER_AD_UNIT_ID =
            "ca-app-pub-1215580137922140/4792811578";
    private static final String TEST_BANNER_AD_UNIT_ID =
            "ca-app-pub-3940256099942544/9214589741";

    private WebView webView;
    private AdView adView;

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
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        webView = new WebView(this);
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        root.addView(webView, webParams);

        // Keep the existing banner behavior.
        adView = new AdView(this);
        adView.setAdUnitId(
                BuildConfig.ADS_TEST_MODE
                        ? TEST_BANNER_AD_UNIT_ID
                        : PROD_BANNER_AD_UNIT_ID
        );

        int widthDp = Math.max(
                1,
                Math.round(
                        getResources().getDisplayMetrics().widthPixels /
                                getResources().getDisplayMetrics().density
                )
        );

        adView.setAdSize(
                AdSize.getLargeAnchoredAdaptiveBannerAdSize(
                        this,
                        widthDp
                )
        );

        LinearLayout.LayoutParams adParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
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

        webView.addJavascriptInterface(
                new AndroidNotifications(),
                "AndroidNotifications"
        );

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request
            ) {
                return openExternalIfNeeded(
                        request.getUrl().toString()
                );
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    String url
            ) {
                return openExternalIfNeeded(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "Page finished: " + url);

                // Pull the current localStorage lessons into Android once
                // after the page is ready.
                syncLessonsFromWebView(view);
            }
        });

        webView.loadUrl(
                "file:///android_asset/www/index.html"
        );
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(
                        Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{
                            Manifest.permission.POST_NOTIFICATIONS
                    },
                    REQ_NOTIFICATIONS
            );
        }
    }

    private void openExactAlarmSettingsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }

        try {
            Intent intent = new Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
            );
            intent.setData(
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Could not open exact-alarm settings", e);
        }
    }

    private void syncLessonsFromWebView(WebView view) {
        String js =
                "(function(){"
                        + "try{"
                        + "var l=localStorage.getItem('lessons')||'[]';"
                        + "var m=parseInt(localStorage.getItem('notify_minutes')||'15',10);"
                        + "if(!isFinite(m)||m<1)m=15;"
                        + "if(window.AndroidNotifications)"
                        + "window.AndroidNotifications.scheduleLessons(l,m);"
                        + "}catch(e){}})();";

        view.evaluateJavascript(js, null);
    }

    private class AndroidNotifications {
        @JavascriptInterface
        public void requestNotificationPermission() {
            runOnUiThread(
                    MainActivity.this::requestNotificationPermissionIfNeeded
            );
        }

        @JavascriptInterface
        public void scheduleLessons(
                String lessonsJson,
                int notifyMinutes
        ) {
            try {
                requestNotificationPermissionIfNeeded();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    android.app.AlarmManager am =
                            (android.app.AlarmManager)
                                    getSystemService(
                                            ALARM_SERVICE
                                    );

                    if (am != null &&
                            !am.canScheduleExactAlarms()) {
                        Log.w(
                                TAG,
                                "Exact alarm permission is not granted"
                        );
                        runOnUiThread(
                                MainActivity.this::
                                        openExactAlarmSettingsIfNeeded
                        );
                        return;
                    }
                }

                String safeJson =
                        lessonsJson == null ||
                                lessonsJson.trim().isEmpty()
                                ? "[]"
                                : lessonsJson;

                int safeMinutes =
                        Math.max(1, notifyMinutes);

                Log.d(
                        TAG,
                        "Scheduling lessons; minutes=" +
                                safeMinutes +
                                ", jsonLength=" +
                                safeJson.length()
                );

                NotificationScheduler.ensureChannel(
                        MainActivity.this
                );

                NotificationScheduler.saveAndSchedule(
                        MainActivity.this,
                        safeJson,
                        safeMinutes
                );

            } catch (Exception e) {
                Log.e(TAG, "scheduleLessons failed", e);
            }
        }

        @JavascriptInterface
        public void openNotificationSettings() {
            try {
                Intent intent = new Intent();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    intent.setAction(
                            Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    );
                    intent.putExtra(
                            Settings.EXTRA_APP_PACKAGE,
                            getPackageName()
                    );
                } else {
                    intent.setAction(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    );
                    intent.setData(
                            Uri.parse("package:" + getPackageName())
                    );
                }

                startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Could not open notification settings", e);
            }
        }

        @JavascriptInterface
        public void openAlarmSettings() {
            runOnUiThread(
                    MainActivity.this::
                            openExactAlarmSettingsIfNeeded
            );
        }
    }

    private boolean openExternalIfNeeded(String url) {
        if (url.startsWith("file://") ||
                url.startsWith("about:")) {
            return false;
        }

        try {
            Intent intent = new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(url)
            );
            startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Could not open external URL", e);
            return false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        requestNotificationPermissionIfNeeded();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            NotificationScheduler.scheduleAll(this);
            return;
        }

        android.app.AlarmManager am =
                (android.app.AlarmManager)
                        getSystemService(ALARM_SERVICE);

        if (am != null && am.canScheduleExactAlarms()) {
            NotificationScheduler.scheduleAll(this);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null &&
                webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (adView != null) {
            adView.destroy();
        }

        if (webView != null) {
            webView.destroy();
        }

        super.onDestroy();
    }
}
