package com.mahmoud.teacherassistant;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import android.webkit.DownloadListener;
import android.widget.LinearLayout;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TAG = "TeacherAssistantNotif";
    private static final int REQ_NOTIFICATIONS = 3101;

    private static final String PROD_BANNER_AD_UNIT_ID =
            "ca-app-pub-1215580137922140/4792811578";
    private static final String TEST_BANNER_AD_UNIT_ID =
            "ca-app-pub-3940256099942544/9214589741";

    private WebView webView;
    private AdView adView;
    private ValueCallback<Uri[]> fileChooserCallback;
    private static final int REQ_FILE_CHOOSER = 4101;

    private final BackupFileReceiver backupFileReceiver = new BackupFileReceiver();

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
        settings.setSupportMultipleWindows(false);

        webView.addJavascriptInterface(
                new AndroidNotifications(),
                "AndroidNotifications"
        );

        // Native bridge used only for Blob/file downloads on Android WebView.
        webView.addJavascriptInterface(
                backupFileReceiver,
                "AndroidFileSaver"
        );

        webView.addJavascriptInterface(
                new AndroidWhatsApp(),
                "AndroidWhatsApp"
        );

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            // Normal HTTP downloads can still use the browser's regular handling.
            if (url != null && !url.startsWith("blob:")) {
                Log.d(TAG, "WebView download requested: " + url);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams
            ) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }

                fileChooserCallback = filePathCallback;

                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/json");
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    Log.e(TAG, "Could not open backup file chooser", e);
                    fileChooserCallback = null;
                    return false;
                }
            }
        });

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

                // Enable Blob downloads without changing the app's existing JS logic.
                installBlobDownloadBridge(view);

                // Pull the current localStorage lessons into Android once after the page is ready.
                syncLessonsFromWebView(view);
            }
        });

        webView.loadUrl(
                "file:///android_asset/www/index.html"
        );
    }

    private void installBlobDownloadBridge(WebView view) {
        String js = "(function(){"
                + "try{"
                + "if(window.__androidBlobBridgeInstalled)return;"
                + "window.__androidBlobBridgeInstalled=true;"
                + "window.__androidBlobMap=new Map();"
                + "var __origCreate=URL.createObjectURL.bind(URL);"
                + "URL.createObjectURL=function(blob){"
                + "var u=__origCreate(blob);"
                + "try{window.__androidBlobMap.set(u,blob)}catch(e){}"
                + "return u;"
                + "};"
                + "var __origRevoke=URL.revokeObjectURL.bind(URL);"
                + "URL.revokeObjectURL=function(u){"
                + "try{window.__androidBlobMap.delete(u)}catch(e){}"
                + "return __origRevoke(u);"
                + "};"
                + "var __origClick=HTMLAnchorElement.prototype.click;"
                + "HTMLAnchorElement.prototype.click=function(){"
                + "try{"
                + "var u=this.href||this.getAttribute('href')||'';"
                + "var blob=window.__androidBlobMap.get(u);"
                + "if(blob&&window.AndroidFileSaver){"
                + "var name=this.download||'backup.json';"
                + "var mime=blob.type||'application/octet-stream';"
                + "var reader=new FileReader();"
                + "reader.onload=function(){"
                + "try{"
                + "var data=String(reader.result||'');"
                + "var comma=data.indexOf(',');"
                + "var b64=comma>=0?data.slice(comma+1):data;"
                + "window.AndroidFileSaver.beginFile(name,mime);"
                + "var step=262144;"
                + "for(var i=0;i<b64.length;i+=step){window.AndroidFileSaver.appendChunk(b64.slice(i,i+step));}"
                + "window.AndroidFileSaver.finishFile();"
                + "return;"
                + "}catch(e){console.error('Android blob save failed',e)}"
                + "};"
                + "reader.readAsDataURL(blob);"
                + "return;"
                + "}"
                + "}catch(e){console.error('Android blob bridge error',e)}"
                + "return __origClick.apply(this,arguments);"
                + "};"
                + "}catch(e){console.error('Install blob bridge failed',e)}"
                + "})();";
        view.evaluateJavascript(js, null);
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
                        + "var g=localStorage.getItem('mosaed_groups')||'[]';"
                        + "var m=parseInt(localStorage.getItem('notify_minutes')||'15',10);"
                        + "if(!isFinite(m)||m<1)m=15;"
                        + "if(window.AndroidNotifications)"
                        + "window.AndroidNotifications.scheduleAllData(l,g,m);"
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
        public void scheduleAllData(
                String lessonsJson,
                String groupsJson,
                int notifyMinutes
        ) {
            try {
                requestNotificationPermissionIfNeeded();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    android.app.AlarmManager am =
                            (android.app.AlarmManager)
                                    getSystemService(ALARM_SERVICE);

                    if (am != null &&
                            !am.canScheduleExactAlarms()) {
                        Log.w(
                                TAG,
                                "Exact alarm permission is not granted"
                        );
                        runOnUiThread(
                                MainActivity.this::openExactAlarmSettingsIfNeeded
                        );
                        return;
                    }
                }

                String safeLessons =
                        lessonsJson == null ||
                                lessonsJson.trim().isEmpty()
                                ? "[]"
                                : lessonsJson;

                String safeGroups =
                        groupsJson == null ||
                                groupsJson.trim().isEmpty()
                                ? "[]"
                                : groupsJson;

                int safeMinutes =
                        Math.max(1, notifyMinutes);

                Log.d(
                        TAG,
                        "Scheduling all data; minutes=" +
                                safeMinutes +
                                ", lessonsLength=" +
                                safeLessons.length() +
                                ", groupsLength=" +
                                safeGroups.length()
                );

                NotificationScheduler.ensureChannel(
                        MainActivity.this
                );

                NotificationScheduler.saveAndSchedule(
                        MainActivity.this,
                        safeLessons,
                        safeGroups,
                        safeMinutes
                );

            } catch (Exception e) {
                Log.e(TAG, "scheduleAllData failed", e);
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
                    MainActivity.this::openExactAlarmSettingsIfNeeded
            );
        }
    }

    private class AndroidWhatsApp {
        @JavascriptInterface
        public void openMessage(String url) {
            openWhatsAppChooser(url);
        }

        @JavascriptInterface
        public void openShareMessage(String message) {
            openWhatsAppShareMessage(message);
        }

        // Open the saved WhatsApp group invite link directly.
        // The message is copied to clipboard because WhatsApp does not accept
        // prefilled text on a chat.whatsapp.com invite URL.
        @JavascriptInterface
        public void openGroupWithMessage(String groupUrl, String message) {
            openWhatsAppGroupDirect(groupUrl, message);
        }
    }

    private void openWhatsAppChooser(String url) {
        if (url == null || url.trim().isEmpty()) {
            return;
        }

        runOnUiThread(() -> {
            try {
                Uri uri = Uri.parse(url.trim());
                android.content.pm.PackageManager pm = getPackageManager();

                Intent wa = new Intent(Intent.ACTION_VIEW, uri);
                wa.setPackage("com.whatsapp");

                Intent wab = new Intent(Intent.ACTION_VIEW, uri);
                wab.setPackage("com.whatsapp.w4b");

                boolean hasWa = wa.resolveActivity(pm) != null;
                boolean hasWab = wab.resolveActivity(pm) != null;

                if (hasWa && hasWab) {
                    Intent chooser = Intent.createChooser(
                            wa,
                            "اختار تطبيق واتساب"
                    );
                    chooser.putExtra(
                            Intent.EXTRA_INITIAL_INTENTS,
                            new Intent[]{wab}
                    );
                    startActivity(chooser);
                    return;
                }

                if (hasWa) {
                    startActivity(wa);
                    return;
                }

                if (hasWab) {
                    startActivity(wab);
                    return;
                }

                Intent normal = new Intent(Intent.ACTION_VIEW, uri);
                if (normal.resolveActivity(pm) != null) {
                    startActivity(
                            Intent.createChooser(
                                    normal,
                                    "اختار تطبيق لفتح الرابط"
                            )
                    );
                }
            } catch (Exception e) {
                Log.e(TAG, "Could not open WhatsApp message", e);
            }
        });
    }

    private void openWhatsAppGroupDirect(String groupUrl, String message) {
        final String safeUrl = groupUrl == null ? "" : groupUrl.trim();
        final String safeMessage = message == null ? "" : message;

        if (safeUrl.isEmpty()) {
            openWhatsAppShareMessage(safeMessage);
            return;
        }

        runOnUiThread(() -> {
            try {
                // Put the complete prepared message in the clipboard before opening the group.
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null && !safeMessage.isEmpty()) {
                    clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("رسالة المجموعة", safeMessage)
                    );
                }

                Uri uri = Uri.parse(safeUrl);
                android.content.pm.PackageManager pm = getPackageManager();

                Intent wa = new Intent(Intent.ACTION_VIEW, uri);
                wa.setPackage("com.whatsapp");

                Intent wab = new Intent(Intent.ACTION_VIEW, uri);
                wab.setPackage("com.whatsapp.w4b");

                boolean hasWa = wa.resolveActivity(pm) != null;
                boolean hasWab = wab.resolveActivity(pm) != null;

                if (hasWa && hasWab) {
                    Intent chooser = Intent.createChooser(wa, "اختار واتساب");
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{wab});
                    startActivity(chooser);
                    showToast("تم نسخ الرسالة ✅ افتح المجموعة والصقها في خانة الرسائل");
                    return;
                }

                if (hasWa) {
                    startActivity(wa);
                    showToast("تم نسخ الرسالة ✅ افتح المجموعة والصقها في خانة الرسائل");
                    return;
                }

                if (hasWab) {
                    startActivity(wab);
                    showToast("تم نسخ الرسالة ✅ افتح المجموعة والصقها في خانة الرسائل");
                    return;
                }

                Intent normal = new Intent(Intent.ACTION_VIEW, uri);
                if (normal.resolveActivity(pm) != null) {
                    startActivity(Intent.createChooser(normal, "فتح مجموعة واتساب"));
                    showToast("تم نسخ الرسالة ✅");
                } else {
                    openWhatsAppShareMessage(safeMessage);
                }
            } catch (Exception e) {
                Log.e(TAG, "Could not open WhatsApp group directly", e);
                // Safe fallback: preserve the working student-style share flow.
                openWhatsAppShareMessage(safeMessage);
            }
        });
    }

    private void openWhatsAppShareMessage(String message) {
        final String safeMessage = message == null ? "" : message;
        if (safeMessage.trim().isEmpty()) {
            showToast("لا يوجد نص للرسالة ❌");
            return;
        }

        runOnUiThread(() -> {
            try {
                android.content.pm.PackageManager pm = getPackageManager();

                Intent wa = new Intent(Intent.ACTION_SEND);
                wa.setType("text/plain");
                wa.putExtra(Intent.EXTRA_TEXT, safeMessage);
                wa.setPackage("com.whatsapp");

                Intent wab = new Intent(Intent.ACTION_SEND);
                wab.setType("text/plain");
                wab.putExtra(Intent.EXTRA_TEXT, safeMessage);
                wab.setPackage("com.whatsapp.w4b");

                boolean hasWa = wa.resolveActivity(pm) != null;
                boolean hasWab = wab.resolveActivity(pm) != null;

                if (hasWa && hasWab) {
                    Intent chooser = Intent.createChooser(wa, "اختار تطبيق واتساب");
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{wab});
                    startActivity(chooser);
                    return;
                }

                if (hasWa) {
                    startActivity(wa);
                    return;
                }

                if (hasWab) {
                    startActivity(wab);
                    return;
                }

                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_TEXT, safeMessage);
                if (share.resolveActivity(pm) != null) {
                    startActivity(Intent.createChooser(share, "مشاركة الرسالة"));
                } else {
                    showToast("واتساب غير مثبت على الجهاز ❌");
                }
            } catch (Exception e) {
                Log.e(TAG, "Could not share WhatsApp message", e);
                showToast("تعذر فتح واتساب ❌");
            }
        });
    }

    private class BackupFileReceiver {
        private String currentName;
        private String currentMime;
        private ByteArrayOutputStream buffer;

        @JavascriptInterface
        public synchronized void beginFile(String name, String mime) {
            currentName = sanitizeFileName(name);
            currentMime = (mime == null || mime.trim().isEmpty())
                    ? "application/octet-stream"
                    : mime;
            buffer = new ByteArrayOutputStream();
            Log.d(TAG, "Backup transfer started: " + currentName + " / " + currentMime);
        }

        @JavascriptInterface
        public synchronized void appendChunk(String base64Chunk) {
            if (buffer == null || base64Chunk == null) return;
            try {
                byte[] bytes = Base64.decode(base64Chunk, Base64.DEFAULT);
                buffer.write(bytes);
            } catch (Exception e) {
                Log.e(TAG, "Backup chunk decode failed", e);
                buffer = null;
            }
        }

        @JavascriptInterface
        public synchronized void finishFile() {
            if (buffer == null) {
                showToast("فشل حفظ النسخة الاحتياطية ❌");
                return;
            }

            byte[] data = buffer.toByteArray();
            String name = currentName == null ? "backup.json" : currentName;
            String mime = currentMime == null ? "application/octet-stream" : currentMime;

            buffer = null;
            currentName = null;
            currentMime = null;

            new Thread(() -> saveToDownloads(name, mime, data)).start();
        }
    }

    private void saveToDownloads(String name, String mime, byte[] data) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, mime);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.Downloads.IS_PENDING, 1);

                Uri uri = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        values
                );

                if (uri == null) {
                    throw new IllegalStateException("MediaStore insert returned null");
                }

                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException("Cannot open output stream");
                    out.write(data);
                    out.flush();
                }

                ContentValues done = new ContentValues();
                done.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(uri, done, null, null);

                showToast("تم حفظ النسخة الاحتياطية في مجلد التنزيلات ✅");
                return;
            }

            File downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
            );
            if (!downloads.exists() && !downloads.mkdirs()) {
                throw new IllegalStateException("Cannot create Downloads folder");
            }

            File outFile = new File(downloads, name);
            try (FileOutputStream out = new FileOutputStream(outFile)) {
                out.write(data);
                out.flush();
            }

            showToast("تم حفظ النسخة الاحتياطية في التنزيلات ✅");
        } catch (Exception e) {
            Log.e(TAG, "saveToDownloads failed", e);
            showToast("فشل حفظ النسخة الاحتياطية ❌");
        }
    }

    private String sanitizeFileName(String name) {
        String s = (name == null || name.trim().isEmpty())
                ? "backup.json"
                : name.trim();
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_");
        return s.length() > 180 ? s.substring(0, 180) : s;
    }

    private void showToast(String message) {
        runOnUiThread(() -> android.widget.Toast.makeText(
                MainActivity.this,
                message,
                android.widget.Toast.LENGTH_LONG
        ).show());
    }

    private boolean openExternalIfNeeded(String url) {
        if (url == null) {
            return false;
        }

        if (url.startsWith("file://") || url.startsWith("about:")) {
            return false;
        }

        try {
            Uri uri = Uri.parse(url);
            String lower = url.toLowerCase();

            boolean isWhatsApp =
                    lower.startsWith("whatsapp://") ||
                    lower.startsWith("whatsapp-business://") ||
                    lower.contains("wa.me/") ||
                    lower.contains("whatsapp.com/");

            if (isWhatsApp) {
                Intent normal = new Intent(Intent.ACTION_VIEW, uri);

                android.content.pm.PackageManager pm = getPackageManager();

                java.util.ArrayList<Intent> targeted = new java.util.ArrayList<>();

                String[] packages = {
                        "com.whatsapp",
                        "com.whatsapp.w4b"
                };

                for (String pkg : packages) {
                    try {
                        Intent targetedIntent = new Intent(Intent.ACTION_VIEW, uri);
                        targetedIntent.setPackage(pkg);

                        if (targetedIntent.resolveActivity(pm) != null) {
                            targeted.add(targetedIntent);
                        }
                    } catch (Exception ignored) {
                    }
                }

                if (targeted.size() == 1) {
                    startActivity(targeted.get(0));
                    return true;
                }

                if (targeted.size() >= 2) {
                    final String[] labels = {
                            "WhatsApp",
                            "WhatsApp Business"
                    };

                    new android.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle("اختار تطبيق واتساب")
                            .setItems(labels, (dialog, which) -> {
                                try {
                                    startActivity(targeted.get(which));
                                } catch (Exception e) {
                                    Log.e(TAG, "Could not open selected WhatsApp app", e);
                                }
                            })
                            .setNegativeButton("إلغاء", null)
                            .show();

                    return true;
                }

                // Fallback: let Android choose any app that can handle the URL.
                if (normal.resolveActivity(pm) != null) {
                    startActivity(Intent.createChooser(normal, "اختار تطبيق لفتح الرابط"));
                    return true;
                }

                return false;
            }

            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not open external URL", e);
        }

        return false;
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
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }

        if (adView != null) {
            adView.destroy();
        }

        if (webView != null) {
            webView.destroy();
        }

        super.onDestroy();
    }
}
