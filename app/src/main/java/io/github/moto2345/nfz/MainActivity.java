package io.github.moto2345.nfz;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GnssStatus;
import android.location.altitude.AltitudeConverter;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
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
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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

    // 기압계(있는 폰만) — 해발고도를 GPS보다 안정적으로 계산하는 데 씀
    private SensorManager sensorManager;
    private SensorEventListener baroListener;
    private boolean baroRunning = false;
    private volatile double baroHpa = Double.NaN;
    private volatile long baroAt = 0;

    // 지오이드 높이(타원체 고도 − 해발고도, 우리나라 약 20~30m) — 안드로이드 14 이상에서 계산
    private final ExecutorService altExec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean altBusy = new AtomicBoolean(false);
    private volatile double geoidM = Double.NaN;
    // 속도 오차(m/s) — 폰 GPS가 알려 주는 속도 신뢰도 (안드로이드 8+)
    private volatile double spdAcc = Double.NaN;
    private volatile long spdAccAt = 0;
    private volatile long geoidAt = 0;

    // 앱이 직접 받는 위치 — 옛 기종·차량용 기기처럼 웹 화면의 위치 기능이 안 될 때 대신 씀
    private LocationListener nativeListener;
    private boolean nativeWanted = false, nativeRunning = false, pendingNative = false, askingLoc = false;
    private Location lastNative;

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
        else {
            web.loadUrl(HOME_URL);
            // 처음 켤 때 위치 권한을 미리 물음 (웹 화면이 묻지 못하는 옛 기기에서도 권한 창이 뜨게)
            if (!hasLocationPermission()) askLocation();
        }
    }

    private void askLocation() {
        if (askingLoc) return;
        askingLoc = true;
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        // 1) 웹 화면에 열린 창(날씨·앱 정보·안내 등)이 있으면 그것부터 닫기
        // 2) 이전 페이지가 있으면 뒤로
        // 3) 3초 안에 한 번 더 누르면 종료
        if (isHome(Uri.parse(currentUrl))) {
            web.evaluateJavascript("(window.nfzBack && window.nfzBack()) ? '1' : '0'", v -> {
                if (!"\"1\"".equals(v)) backOrExit();
            });
        } else backOrExit();
    }

    private long lastBackAt = 0;
    private void backOrExit() {
        if (web.canGoBack()) { web.goBack(); return; }
        long now = System.currentTimeMillis();
        if (now - lastBackAt < 3000) { finish(); return; }
        lastBackAt = now;
        toast("뒤로 버튼을 한 번 더 누르면 앱이 종료돼요");
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        if (gnssWanted) startGnss();
        if (nativeWanted) startNative();
        // 돌아왔을 때 화면 일부(결과창 등)가 하얗게 남는 경우가 있어 다시 그리게 함
        web.postDelayed(() -> {
            web.invalidate();
            if (fromHome()) web.evaluateJavascript("window.nfzResume&&window.nfzResume()", null);
        }, 250);
    }

    @Override
    protected void onPause() { stopGnss(); stopNative(); web.onPause(); super.onPause(); } // 화면을 떠나면 배터리 절약

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
            openOutside(uri);
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

    /* 바깥 링크: 웹 주소는 '앱 위에 뜨는 브라우저 창'(Custom Tab)으로 — 닫으면 앱이 보던 화면 그대로.
       원스톱 주소 검색 팝업 등도 브라우저 기능 그대로 동작. 전화번호는 전화 앱으로. */
    private void openOutside(Uri uri) {
        if (isOnestop(uri)) { openOnestop(uri.toString(), Double.NaN, Double.NaN); return; } // 원스톱은 앱 안 전용 창으로
        try {
            String sc = uri.getScheme() == null ? "" : uri.getScheme();
            Intent i;
            if ("tel".equals(sc)) i = new Intent(Intent.ACTION_DIAL, uri);
            else {
                i = new Intent(Intent.ACTION_VIEW, uri);
                if ("https".equals(sc) || "http".equals(sc)) {
                    Bundle extras = new Bundle();
                    extras.putBinder("android.support.customtabs.extra.SESSION", (IBinder) null); // 이 값이 있으면 브라우저가 Custom Tab으로 염
                    i.putExtras(extras);
                    i.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", 0xFF1565C0);
                    i.putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1);
                }
            }
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("열 수 있는 앱이 없습니다.");
        }
    }

    /* ───────── 드론원스톱 '비행가능지역 확인' 전용 창 ─────────
       공식 사이트를 앱 안의 창으로 열고, 휴대폰에서도 지도와 결과표가 한 화면에 보이게 배치만 바꿔 줌
       (세로: 지도 위·결과 아래 / 가로: 지도 왼쪽·결과 오른쪽).
       하코 NFZ에서 확인하던 지점 좌표를 넘기면 그 지점을 자동으로 선택해 결과가 바로 나옴.
       주소검색(카카오 우편번호) 팝업은 팝업 창으로, 페이지의 '닫기'는 이 창을 닫음. */
    private static final String ONESTOP_HOST = "drone.onestop.go.kr";
    private Dialog onestopDialog;

    private static boolean isOnestop(Uri u) {
        String h = u == null ? null : u.getHost();
        return h != null && (h.equals(ONESTOP_HOST) || h.endsWith("." + ONESTOP_HOST));
    }

    private static final String ONESTOP_CSS =
            "body>br,body>font{display:none!important}body{margin:0!important}"
            + ".home-flight{width:100%!important;margin:0!important;padding:0!important}"
            + ".home-flight-map{height:46vh!important;min-height:220px;width:100%!important;margin:0!important;padding:0!important;box-sizing:border-box!important}"
            + ".home-flight-map #map{height:100%!important;width:100%!important}"
            + ".home-flight-table{width:100%!important;margin:4px 0 0!important;padding:0 4px!important;box-sizing:border-box!important;height:auto!important;float:none!important}"
            + ".home-flight-table table.flight{width:100%!important;font-size:12.5px!important;line-height:1.35!important}"
            + ".home-flight-table th,.home-flight-table td{padding:4px 7px!important;height:auto!important}"
            + ".home-flight-table th{width:62px!important;font-size:12px!important;word-break:keep-all}"
            + "@media (orientation:landscape){.home-flight{display:flex!important;align-items:flex-start!important;gap:6px!important;height:auto!important}"
            + ".home-flight-map{flex:1 1 58%!important;width:auto!important;height:calc(100vh - 8px)!important;min-height:0}"
            + ".home-flight-table{flex:0 0 41%!important;width:41%!important;max-height:calc(100vh - 8px)!important;overflow:auto!important;margin:0!important}}";

    private String onestopScript(double lat, double lon) {
        String pick = Double.isNaN(lat) || Double.isNaN(lon) ? "" :
                String.format(Locale.US, "var lat=%.7f,lon=%.7f,n=0;(function go(){"
                        + "if(typeof vmap!=='undefined'&&vmap&&typeof ol!=='undefined'&&typeof singleClickEvent==='function'&&typeof getPixelToBBOX==='function'){"
                        + "setTimeout(function(){try{var c=ol.proj.transform([lon,lat],'EPSG:4326','EPSG:3857');"
                        + "vmap.getView().setCenter(ol.proj.fromLonLat([lon,lat]));vmap.getView().setZoom(15);"
                        + "setTimeout(function(){try{bbox=getPixelToBBOX();singleClickEvent(c,null);}catch(e){}},600);}catch(e){}},1200);"
                        + "}else if(n++<60)setTimeout(go,250);})();", lat, lon);
        return "(function(){if(document.getElementById('hako-fit'))return;var s=document.createElement('style');s.id='hako-fit';"
                + "s.textContent=" + jsString(ONESTOP_CSS) + ";(document.head||document.documentElement).appendChild(s);"
                + "setTimeout(function(){window.dispatchEvent(new Event('resize'));},300);" + pick + "})();";
    }

    private static String jsString(String v) {
        return "'" + v.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // 위쪽 파란 막대(제목 + 버튼들 + 닫기)가 달린 전체 화면 창. extra: {글자, 동작} 쌍
    private Dialog webDialog(String title, WebView w, Object... extra) {
        Dialog d = new Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF1565C0);
        bar.setPadding(dp(12), dp(6), dp(4), dp(6));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(14);
        t.setSingleLine(true);
        bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        for (int i = 0; i + 1 < extra.length; i += 2) bar.addView(barButton((String) extra[i], (Runnable) extra[i + 1]), wrap());
        bar.addView(barButton("닫기 ✕", d::dismiss), wrap());
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(w, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        d.setContentView(root);
        d.setOnKeyListener((dlg, code, ev) -> {
            if (code == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP) {
                if (w.canGoBack()) w.goBack(); else d.dismiss();
                return true;
            }
            return code == KeyEvent.KEYCODE_BACK;
        });
        d.setOnDismissListener(dlg -> {
            try { android.webkit.CookieManager.getInstance().flush(); } catch (Exception ignored) {} // 원스톱 로그인 유지
            try { w.destroy(); } catch (Exception ignored) {}
        });
        return d;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView barButton(String label, Runnable r) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(14);
        b.setPadding(dp(9), dp(7), dp(9), dp(7));
        b.setOnClickListener(v -> r.run());
        return b;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView popupWebView() {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        return w;
    }

    // 지금 보이는 화면을 안드로이드 인쇄(→ 'PDF로 저장' 선택 가능)로 보냄
    private void printWeb(WebView w, String name) {
        runOnUiThread(() -> {
            try {
                android.print.PrintManager pm = (android.print.PrintManager) getSystemService(PRINT_SERVICE);
                String job = name + "_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREA).format(new java.util.Date());
                pm.print(job, w.createPrintDocumentAdapter(job), null);
            } catch (Exception e) {
                toast("인쇄를 열 수 없습니다: " + e.getMessage());
            }
        });
    }

    // 원스톱 페이지 안의 window.print()(공식 결과 문서 인쇄 등)를 안드로이드 인쇄로 연결 — 인쇄 기능만 있는 작은 연결
    private class PrintBridge {
        private final WebView w; private final String name;
        PrintBridge(WebView w, String name) { this.w = w; this.name = name; }
        @JavascriptInterface public void print() { printWeb(w, name); }
    }

    // 원스톱 창·팝업 공통: 원스톱 주소는 안에서, 다른 사이트는 바깥 창으로, 팝업은 새 창으로, 파일 받기 지원
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void setupOnestopWeb(final WebView w, final Dialog d, final String printName, final double lat, final double lon, final boolean main) {
        WebSettings s = w.getSettings();
        s.setGeolocationEnabled(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        try { android.webkit.CookieManager.getInstance().setAcceptCookie(true); } catch (Exception ignored) {}
        w.addJavascriptInterface(new PrintBridge(w, printName), "HakoPrint");
        w.setDownloadListener((url, ua, cd, mime, len) -> {
            try {
                android.app.DownloadManager.Request rq = new android.app.DownloadManager.Request(Uri.parse(url));
                String cookie = android.webkit.CookieManager.getInstance().getCookie(url);
                if (cookie != null) rq.addRequestHeader("Cookie", cookie);
                rq.addRequestHeader("User-Agent", ua);
                String fn = android.webkit.URLUtil.guessFileName(url, cd, mime);
                rq.setTitle(fn);
                rq.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                rq.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fn);
                ((android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(rq);
                toast("다운로드 폴더에 받는 중: " + fn);
            } catch (Exception e) {
                toast("파일을 받을 수 없어요. 위쪽 🖨 PDF 버튼으로 저장해 보세요.");
            }
        });
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String sc = u.getScheme() == null ? "" : u.getScheme();
                if (isOnestop(u)) return false;
                if ("tel".equals(sc)) { openOutside(u); return true; }
                if ("http".equals(sc) || "https".equals(sc)) {
                    if (main && req.isForMainFrame() && req.hasGesture()) { openOutside(u); return true; } // 항공고시보 등 다른 사이트는 바깥 창으로
                    return false;
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String u) {
                if (u == null || !isOnestop(Uri.parse(u))) return;
                view.evaluateJavascript("window.print=function(){try{HakoPrint.print()}catch(e){}};", null);
                if (main && onestopReturn && !u.contains("/member/login")) { // 로그인 끝 → 보던 지점으로 돌아가기
                    onestopReturn = false;
                    view.loadUrl(ONESTOP_MAP);
                    return;
                }
                if (u.contains("flightArea_chk")) view.evaluateJavascript(onestopScript(main ? lat : Double.NaN, main ? lon : Double.NaN), null);
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                cb.invoke(origin, hasLocationPermission(), false); // 원스톱의 '내 위치' 버튼
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean userGesture, Message resultMsg) {
                // 로그인 직후 저절로 뜨는 공지 팝업들은 열지 않음 (누르지 않았는데 뜨는 창 · 사전확인/결과 화면에서 뜨는 창은 허용)
                String from = view.getUrl() == null ? "" : view.getUrl();
                if (!userGesture && !from.contains("inadvance") && !from.contains("flightArea") && !from.contains("ClipReport")) return false;
                final WebView child = popupWebView();
                final Dialog[] pd = new Dialog[1];
                pd[0] = webDialog("드론원스톱", child, "🖨 PDF", (Runnable) () -> printWeb(child, "원스톱"));
                setupOnestopWeb(child, pd[0], "원스톱", Double.NaN, Double.NaN, false);
                WebView.WebViewTransport tr = (WebView.WebViewTransport) resultMsg.obj;
                tr.setWebView(child);
                resultMsg.sendToTarget();
                pd[0].show();
                return true;
            }

            @Override
            public void onCloseWindow(WebView window) { d.dismiss(); } // 페이지의 '닫기' 버튼 · 팝업 닫기
        });
    }

    private static final String ONESTOP_MAP = "https://" + ONESTOP_HOST + "/common/flightArea_chk";
    private static final String ONESTOP_LOGIN = "https://" + ONESTOP_HOST + "/member/login/login";
    private boolean onestopReturn = false;

    private void openOnestop(final String url, final double lat, final double lon) {
        runOnUiThread(() -> {
            if (onestopDialog != null) { try { onestopDialog.dismiss(); } catch (Exception ignored) {} }
            onestopReturn = false;
            final WebView w = popupWebView();
            final Dialog d = webDialog("🛂 원스톱 비행가능지역", w,
                    "로그인", (Runnable) () -> { onestopReturn = true; w.loadUrl(ONESTOP_LOGIN); toast("로그인하면 보던 지점으로 돌아와요"); },
                    "🖨 PDF", (Runnable) () -> printWeb(w, "원스톱_비행가능지역"));
            onestopDialog = d;
            setupOnestopWeb(w, d, "원스톱_비행가능지역", lat, lon, true);
            w.loadUrl(url);
            d.show();
        });
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
                askLocation(); // 이미 묻는 중이면 그 답을 같이 씀
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
        if (requestCode == REQ_LOCATION) {
            askingLoc = false;
            boolean ok = hasLocationPermission();
            if (geoCallback != null) {
                geoCallback.invoke(geoOrigin, ok, false);
                geoCallback = null;
            }
            if (pendingNative) {
                pendingNative = false;
                if (ok) startNative(); else sendNativeErr(1, "perm");
            }
            if (!ok) toast("위치 권한이 없으면 내 위치를 확인할 수 없습니다.");
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
        startBaro(); // 기압계는 위치 권한과 상관없이 켤 수 있음
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
            @Override public void onLocationChanged(Location location) {
                updateGeoid(location);
                if (Build.VERSION.SDK_INT >= 26 && location != null && location.hasSpeedAccuracy()) {
                    spdAcc = location.getSpeedAccuracyMetersPerSecond();
                    spdAccAt = System.currentTimeMillis();
                }
            }
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

    /* ───────── 기압계 ───────── */
    private void startBaro() {
        if (baroRunning) return;
        if (sensorManager == null) sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor s = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        if (s == null) return; // 기압계 없는 폰
        if (baroListener == null) baroListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                double v = e.values[0];
                if (!(v > 300 && v < 1100)) return; // 말도 안 되는 값은 버림
                // 약 2초 평균으로 흔들림을 줄임 (측정 주기 약 0.2초)
                baroHpa = Double.isNaN(baroHpa) || System.currentTimeMillis() - baroAt > 5000 ? v : baroHpa + (v - baroHpa) * 0.1;
                baroAt = System.currentTimeMillis();
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };
        try {
            baroRunning = sensorManager.registerListener(baroListener, s, SensorManager.SENSOR_DELAY_NORMAL);
        } catch (Exception e) {
            baroRunning = false;
        }
    }

    private void stopBaro() {
        if (!baroRunning || sensorManager == null) return;
        try { sensorManager.unregisterListener(baroListener); } catch (Exception ignored) {}
        baroRunning = false;
        baroHpa = Double.NaN;
    }

    /* ───────── 지오이드 높이 (안드로이드 14+) ───────── */
    private void updateGeoid(Location loc) {
        if (Build.VERSION.SDK_INT < 34 || loc == null || !loc.hasAltitude()) return;
        if (System.currentTimeMillis() - geoidAt < 30000 && !Double.isNaN(geoidM)) return; // 천천히 변하는 값이라 30초마다
        if (!altBusy.compareAndSet(false, true)) return;
        final Location copy = new Location(loc);
        altExec.execute(() -> {
            try {
                new AltitudeConverter().addMslAltitudeToLocation(getApplicationContext(), copy); // 파일을 읽으므로 화면 스레드 밖에서
                if (copy.hasMslAltitude()) {
                    geoidM = copy.getAltitude() - copy.getMslAltitudeMeters();
                    geoidAt = System.currentTimeMillis();
                }
            } catch (Throwable ignored) {
            } finally {
                altBusy.set(false);
            }
        });
    }

    private void stopGnss() {
        if (!gnssRunning || locationManager == null) { stopBaro(); return; }
        try { locationManager.unregisterGnssStatusCallback(gnssCallback); } catch (Exception ignored) {}
        try { locationManager.removeUpdates(gpsListener); } catch (Exception ignored) {}
        gnssRunning = false;
        satUsed = -1;
        satSeen = -1;
        stopBaro();
    }

    /* ───────── 앱이 직접 받는 위치 (GPS·통신망) → window.nfzNativeLoc ───────── */
    private void startNative() {
        if (!hasLocationPermission()) { pendingNative = true; askLocation(); return; }
        if (locationManager == null) locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) { sendNativeErr(2, "none"); return; }
        if (nativeRunning) { // 이미 켜져 있으면 마지막 위치를 바로 한 번 더 알려 줌
            if (lastNative != null) sendNative(lastNative);
            return;
        }
        List<String> all;
        try { all = locationManager.getAllProviders(); } catch (Exception e) { all = null; }
        if (all == null || all.isEmpty()) { sendNativeErr(2, "none"); return; }
        // 기다리는 동안 보여 줄 마지막으로 알던 위치 (가장 최근 것)
        Location best = null;
        for (String p : all) {
            try {
                Location l = locationManager.getLastKnownLocation(p);
                if (l != null && (best == null || l.getElapsedRealtimeNanos() > best.getElapsedRealtimeNanos())) best = l;
            } catch (Exception ignored) {}
        }
        if (nativeListener == null) nativeListener = new LocationListener() {
            @Override public void onLocationChanged(Location l) {
                if (l == null) return;
                // GPS가 잡히는 동안에는 덜 정확한 통신망 위치로 흔들리지 않게 무시
                if (!LocationManager.GPS_PROVIDER.equals(l.getProvider()) && lastNative != null
                        && LocationManager.GPS_PROVIDER.equals(lastNative.getProvider())
                        && ageMs(lastNative) < 10000) return;
                // 같은 위치가 두 번 오면 무시 (화면 깜빡임 방지)
                if (lastNative != null && l.getElapsedRealtimeNanos() == lastNative.getElapsedRealtimeNanos()) return;
                lastNative = l;
                sendNative(l);
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            // 주의: 옛 안드로이드는 꺼진 수신원을 등록하는 순간 onProviderDisabled를 바로 부름 →
            // 여기서 다시 등록하면 끝없이 켜졌다 꺼졌다 반복(위치 아이콘 깜빡임). 등록은 유지되고 켜지면 저절로 다시 오므로 아무것도 안 함
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };
        int n = 0;
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            if (!all.contains(p)) continue;
            try {
                boolean on = locationManager.isProviderEnabled(p);
                if (!on && LocationManager.NETWORK_PROVIDER.equals(p)) continue; // 꺼진 통신망 위치는 아예 등록 안 함
                locationManager.requestLocationUpdates(p, 1000, 0, nativeListener, Looper.getMainLooper());
                if (on) n++;
            } catch (Exception ignored) {}
        }
        nativeRunning = true;
        if (best != null) { lastNative = best; sendNative(best); }
        if (n == 0) sendNativeErr(2, all.contains(LocationManager.GPS_PROVIDER) ? "off" : "nogps");
    }

    private void stopNative() {
        if (!nativeRunning || locationManager == null) return;
        try { locationManager.removeUpdates(nativeListener); } catch (Exception ignored) {}
        nativeRunning = false;
    }

    private static long ageMs(Location l) {
        return Math.max(0, (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1000000L);
    }

    private void sendNative(Location l) {
        if (!fromHome()) return;
        long age = ageMs(l);
        String js = "window.nfzNativeLoc&&window.nfzNativeLoc({"
                + String.format(Locale.US, "\"lat\":%.7f,\"lon\":%.7f", l.getLatitude(), l.getLongitude())
                + ",\"acc\":" + (l.hasAccuracy() ? String.format(Locale.US, "%.1f", l.getAccuracy()) : "null")
                + ",\"alt\":" + (l.hasAltitude() ? String.format(Locale.US, "%.1f", l.getAltitude()) : "null")
                + ",\"spd\":" + (l.hasSpeed() ? String.format(Locale.US, "%.2f", l.getSpeed()) : "null")
                + ",\"hdg\":" + (l.hasBearing() ? String.format(Locale.US, "%.1f", l.getBearing()) : "null")
                + ",\"age\":" + age
                + ",\"t\":" + (System.currentTimeMillis() - age) // 기기 시계가 틀린 차량용 기기도 있어 받은 시점 기준으로
                + ",\"prov\":\"" + (l.getProvider() == null ? "" : l.getProvider().replaceAll("[^a-z]", "")) + "\"})";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private void sendNativeErr(int code, String why) {
        runOnUiThread(() -> {
            if (fromHome())
                web.evaluateJavascript("window.nfzNativeErr&&window.nfzNativeErr(" + code + ",'" + why + "')", null);
        });
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

        // {"used": 위치 계산에 쓰는 위성 수, "seen": 보이는 위성 수 (-1 = 아직 모름),
        //  "hpa": 기압(hPa, 기압계 없으면 null), "geoid": 지오이드 높이(m, 모르면 null)}
        @JavascriptInterface
        public String gnss() {
            double p = baroHpa, g = geoidM;
            boolean pOk = !Double.isNaN(p) && System.currentTimeMillis() - baroAt < 5000;
            return "{\"used\":" + satUsed + ",\"seen\":" + satSeen
                    + ",\"hpa\":" + (pOk ? String.format(Locale.US, "%.2f", p) : "null")
                    + ",\"geoid\":" + (Double.isNaN(g) ? "null" : String.format(Locale.US, "%.1f", g))
                    + ",\"spdAcc\":" + (!Double.isNaN(spdAcc) && System.currentTimeMillis() - spdAccAt < 5000 ? String.format(Locale.US, "%.2f", spdAcc) : "null") + "}";
        }

        // 위치 권한·위치 서비스 상태 (웹 화면에선 앱 권한을 알 수 없어서 앱이 알려 줌)
        // {"fine": 정확한 위치 허용, "coarse": 대략적 위치 허용, "gps": 폰 위치 서비스(GPS) 켜짐}
        @JavascriptInterface
        public String locPerm() {
            boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            boolean gps = false;
            try {
                LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
                gps = lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
            } catch (Exception ignored) {}
            boolean net = false, hasGps = true;
            try {
                LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
                List<String> all = lm == null ? null : lm.getAllProviders();
                hasGps = all != null && all.contains(LocationManager.GPS_PROVIDER);
                net = all != null && all.contains(LocationManager.NETWORK_PROVIDER) && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            } catch (Exception ignored) {}
            return "{\"fine\":" + fine + ",\"coarse\":" + coarse + ",\"gps\":" + gps
                    + ",\"net\":" + net + ",\"hasGps\":" + hasGps + ",\"sdk\":" + Build.VERSION.SDK_INT + "}";
        }

        // 앱이 직접 위치 받기 시작/끝 — 결과는 window.nfzNativeLoc({lat,lon,acc,alt,spd,hdg,age,t,prov}),
        // 실패는 window.nfzNativeErr(코드, 이유) (1=권한 없음, 2=위치 끔/GPS 없음)
        @JavascriptInterface
        public void locStart() {
            if (!fromHome()) return;
            runOnUiThread(() -> { nativeWanted = true; startNative(); });
        }

        @JavascriptInterface
        public void locStop() {
            runOnUiThread(() -> { nativeWanted = false; stopNative(); });
        }

        // 드론원스톱 비행가능지역 확인을 앱 안 창으로 열고 이 좌표를 자동 선택
        @JavascriptInterface
        public void openOnestop(double lat, double lon) {
            if (!fromHome()) return;
            MainActivity.this.openOnestop("https://" + ONESTOP_HOST + "/common/flightArea_chk", lat, lon);
        }

        @JavascriptInterface
        public String version() {
            return appVersion();
        }
    }
}
