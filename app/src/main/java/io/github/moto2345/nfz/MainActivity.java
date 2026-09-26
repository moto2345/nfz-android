package io.github.moto2345.nfz;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 하코 NFZ 조회 — 웹앱(https://moto2345.github.io/nfz/)을 감싸는 안드로이드 앱.
 * 웹을 고치면 앱도 자동으로 최신 내용이 됩니다.
 */
public class MainActivity extends Activity {

    private static final String HOME_URL = "https://moto2345.github.io/nfz/";
    private static final String HOME_HOST = "moto2345.github.io";
    private static final String HOME_PATH = "/nfz/";
    private volatile String currentUrl = ""; // 앱 기능(NFZApp)은 우리 페이지에서만 허용
    private static final int REQ_LOCATION = 1;
    private static final int REQ_STORAGE = 2;
    private static final int REQ_FILE = 3;

    private WebView web;
    private String geoOrigin;
    private GeolocationPermissions.Callback geoCallback;
    private ValueCallback<Uri[]> fileCallback;
    private String[] pendingSave; // 저장 권한을 기다리는 파일 {이름, 내용, 형식}

    // GPS 위성 수 (실시간 추적 중에만 켬) — 웹 브라우저로는 알 수 없는 값이라 앱에서 알려 줌
    private LocationManager locationManager;
    private GnssStatus.Callback gnssCallback;
    private LocationListener gpsListener;
    private boolean gnssWanted = false, gnssRunning = false;
    private volatile int satUsed = -1, satSeen = -1;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // 비행 기록·저장한 장소 (localStorage)
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setSupportZoom(false);
        s.setTextZoom(100);                    // 휴대폰 글자 크기 설정에 따라 화면이 깨지지 않게 고정
        s.setUserAgentString(s.getUserAgentString() + " HakoNFZApp/" + appVersion());

        web.addJavascriptInterface(new Bridge(), "NFZApp");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(HOME_URL);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onResume() { super.onResume(); web.onResume(); if (gnssWanted) startGnss(); }

    @Override
    protected void onPause() { stopGnss(); web.onPause(); super.onPause(); } // 화면을 떠나면 배터리 절약

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    /* ───────── 페이지 이동: 앱 주소는 앱 안에서, 나머지(원스톱·전화 등)는 밖에서 ───────── */
    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (isHome(uri)) return false; // 앱 페이지만 앱 안에서, 같은 주소의 다른 사이트는 밖에서
            try {
                Intent i = "tel".equals(uri.getScheme())
                        ? new Intent(Intent.ACTION_DIAL, uri)
                        : new Intent(Intent.ACTION_VIEW, uri);
                startActivity(i);
            } catch (ActivityNotFoundException e) {
                toast("열 수 있는 앱이 없습니다.");
            }
            return true;
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            currentUrl = url == null ? "" : url;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            currentUrl = url == null ? "" : url;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) showOffline(view);
        }
    }

    private static boolean isHome(Uri uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        return "https".equals(uri.getScheme()) && HOME_HOST.equals(uri.getHost())
                && (path.equals("/nfz") || path.startsWith(HOME_PATH));
    }

    private boolean fromHome() {
        try { return isHome(Uri.parse(currentUrl)); } catch (Exception e) { return false; }
    }

    // 파일 이름에서 폴더 이동(../)·특수문자를 없앰
    private static String safeName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").replace("..", "_").trim();
        if (n.isEmpty()) n = "nfz_file.txt";
        return n.length() > 100 ? n.substring(n.length() - 100) : n;
    }

    private void showOffline(WebView view) {
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='font-family:sans-serif;text-align:center;padding:80px 24px;color:#333'>"
                + "<div style='font-size:48px'>📡</div><h3>인터넷에 연결할 수 없습니다</h3>"
                + "<p style='color:#777'>데이터나 와이파이를 켠 뒤 다시 시도하세요.</p>"
                + "<button onclick=\"location.href='" + HOME_URL + "'\" "
                + "style='margin-top:16px;padding:12px 24px;border:0;border-radius:10px;background:#1565c0;color:#fff;font-size:16px'>다시 시도</button>"
                + "</body></html>";
        view.loadDataWithBaseURL(HOME_URL, html, "text/html", "UTF-8", null);
    }

    /* ───────── 위치 권한 · 파일 선택 ───────── */
    private class Chrome extends WebChromeClient {
        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            if (hasLocationPermission()) {
                callback.invoke(origin, true, false);
            } else {
                geoOrigin = origin;
                geoCallback = callback;
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            }
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            try {
                startActivityForResult(Intent.createChooser(i, "백업 파일 선택"), REQ_FILE);
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                toast("파일을 고를 수 있는 앱이 없습니다.");
                return false;
            }
            return true;
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_LOCATION && geoCallback != null) {
            geoCallback.invoke(geoOrigin, hasLocationPermission(), false);
            if (!hasLocationPermission()) toast("위치 권한이 없으면 내 위치를 확인할 수 없습니다.");
            geoCallback = null;
        } else if (requestCode == REQ_STORAGE && pendingSave != null) {
            String[] p = pendingSave;
            pendingSave = null;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) writeFile(p[0], p[1], p[2]);
            else toast("저장 권한이 없어 파일을 저장하지 못했습니다.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) result = new Uri[]{data.getData()};
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    /* ───────── 파일 저장 (CSV·백업) → 휴대폰 '다운로드' 폴더 ───────── */
    private void writeFile(String name, String content, String mime) {
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new Exception("저장 위치를 만들 수 없음");
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new Exception("파일을 열 수 없음");
                    os.write(bytes);
                }
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                try (FileOutputStream os = new FileOutputStream(new File(dir, name))) {
                    os.write(bytes);
                }
            }
            toast("다운로드 폴더에 저장했습니다: " + name);
        } catch (Exception e) {
            toast("저장하지 못했습니다: " + e.getMessage());
        }
    }

    /* ───────── GPS 위성 수 ───────── */
    private void startGnss() {
        if (gnssRunning || !hasLocationPermission()) return;
        if (locationManager == null) locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) return;
        if (gnssCallback == null) gnssCallback = new GnssStatus.Callback() {
            @Override
            public void onSatelliteStatusChanged(GnssStatus status) {
                int used = 0, n = status.getSatelliteCount();
                for (int i = 0; i < n; i++) if (status.usedInFix(i)) used++;
                satUsed = used;
                satSeen = n;
            }

            @Override
            public void onStopped() { satUsed = -1; satSeen = -1; }
        };
        if (gpsListener == null) gpsListener = new LocationListener() { // GPS 칩을 켜 두기 위한 빈 수신기
            @Override public void onLocationChanged(Location location) {}
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) { satUsed = -1; satSeen = -1; }
        };
        try {
            locationManager.registerGnssStatusCallback(gnssCallback, new Handler(Looper.getMainLooper()));
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, gpsListener, Looper.getMainLooper());
            gnssRunning = true;
        } catch (Exception e) {
            gnssRunning = false;
        }
    }

    private void stopGnss() {
        if (!gnssRunning || locationManager == null) return;
        try { locationManager.unregisterGnssStatusCallback(gnssCallback); } catch (Exception ignored) {}
        try { locationManager.removeUpdates(gpsListener); } catch (Exception ignored) {}
        gnssRunning = false;
        satUsed = -1;
        satSeen = -1;
    }

    /* ───────── 웹앱에서 부르는 기능 (window.NFZApp) ───────── */
    private class Bridge {
        @JavascriptInterface
        public void saveFile(final String rawName, final String content, final String rawMime) {
            if (!fromHome()) return;
            final String name = safeName(rawName);
            final String mime = "text/csv".equals(rawMime) || "application/json".equals(rawMime) ? rawMime : "text/plain";
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                        && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    pendingSave = new String[]{name, content, mime};
                    requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
                } else {
                    writeFile(name, content, mime);
                }
            });
        }

        @JavascriptInterface
        public void share(final String text) {
            if (!fromHome()) return;
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, "비행 지점 공유"));
            });
        }

        @JavascriptInterface
        public void gnssStart() {
            if (!fromHome()) return;
            runOnUiThread(() -> { gnssWanted = true; startGnss(); });
        }

        @JavascriptInterface
        public void gnssStop() {
            runOnUiThread(() -> { gnssWanted = false; stopGnss(); });
        }

        // {"used": 위치 계산에 쓰는 위성 수, "seen": 보이는 위성 수} (-1 = 아직 모름)
        @JavascriptInterface
        public String gnss() {
            return "{\"used\":" + satUsed + ",\"seen\":" + satSeen + "}";
        }

        @JavascriptInterface
        public String version() {
            return appVersion();
        }
    }
}
