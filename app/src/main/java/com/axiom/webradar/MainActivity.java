package com.axiom.webradar;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;
    private static final String SESSION_PAGE_URL = "https://meteofrance.mq/fr/images-radar/50km";
    private static final String VIEWER_URL = "file:///android_asset/radar_viewer.html";
    private static final String JS_BOOTSTRAP_FN = "window.AxiomRadar && window.AxiomRadar.bootstrap";
    private static final long LOCATION_MIN_TIME_MS = 5L * 60L * 1000L;
    private static final float LOCATION_MIN_DISTANCE_METERS = 25.0f;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger requestGeneration = new AtomicInteger();
    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (!shouldAcceptLocation(location)) {
                return;
            }
            lastKnownLocation = location;
            deliverLocationIfReady();
        }

        @Override
        public void onProviderDisabled(String provider) {
            notifyViewerLocationStatus("Location provider disabled.");
        }

        @Override
        public void onProviderEnabled(String provider) {
            notifyViewerLocationStatus("Location provider enabled.");
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            // Deprecated on newer Android versions, retained for API 23 compatibility.
        }
    };

    private WebView radarWebView;
    private View loadingOverlay;
    private TextView loadingText;
    private LocationManager locationManager;

    private boolean pageLoaded;
    private boolean destroyed;
    private boolean locationUpdatesActive;
    private String bootstrapJson;
    private Location lastKnownLocation;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        radarWebView = findViewById(R.id.radarWebView);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        loadingText = findViewById(R.id.loadingText);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        enterImmersiveMode();
        configureWebView();
        radarWebView.loadUrl(VIEWER_URL);
        requestBootstrap(true, getString(R.string.loading_message));
        ensureLocationAccess();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startLocationUpdatesIfPossible();
    }

    @Override
    protected void onStop() {
        stopLocationUpdates();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        stopLocationUpdates();
        executor.shutdownNow();
        if (radarWebView != null) {
            radarWebView.removeJavascriptInterface("AndroidRadarApp");
            radarWebView.destroy();
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
    }

    private void showLoadingMessage(@NonNull String message, boolean visible) {
        loadingText.setText(message);
        loadingOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void configureWebView() {
        WebSettings settings = radarWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        radarWebView.setBackgroundColor(0x00000000);
        radarWebView.addJavascriptInterface(new RadarJavascriptBridge(), "AndroidRadarApp");
        radarWebView.setWebChromeClient(new WebChromeClient());
        radarWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                deliverBootstrapIfReady();
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceError error
            ) {
                if (request != null && request.isForMainFrame()) {
                    showLoadingMessage("Viewer failed to load.", true);
                }
            }
        });
    }

    private void requestBootstrap(final boolean showBlockingOverlay, @NonNull final String loadingMessage) {
        if (showBlockingOverlay) {
            showLoadingMessage(loadingMessage, true);
        }
        final int generation = requestGeneration.incrementAndGet();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final String newBootstrap = buildBootstrapJson(fetchSessionToken());
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed || generation != requestGeneration.get()) {
                                return;
                            }
                            bootstrapJson = newBootstrap;
                            deliverBootstrapIfReady();
                        }
                    });
                } catch (final Exception exception) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed || generation != requestGeneration.get()) {
                                return;
                            }
                            showLoadingMessage("Radar session failed: " + exception.getMessage(), true);
                            notifyViewerError("Radar session failed. Use Refresh to try again.");
                        }
                    });
                }
            }
        });
    }

    private void deliverBootstrapIfReady() {
        if (!pageLoaded || bootstrapJson == null) {
            return;
        }
        String script = JS_BOOTSTRAP_FN + "(" + JSONObject.quote(bootstrapJson) + ")";
        radarWebView.evaluateJavascript(script, null);
        deliverLocationIfReady();
    }

    private void notifyViewerError(@NonNull String message) {
        if (!pageLoaded) {
            return;
        }
        String script = "window.AxiomRadar && window.AxiomRadar.showNativeError("
                + JSONObject.quote(message)
                + ")";
        radarWebView.evaluateJavascript(script, null);
    }

    private void notifyViewerLocationStatus(@NonNull String message) {
        if (!pageLoaded) {
            return;
        }
        String script = "window.AxiomRadar && window.AxiomRadar.setGpsStatus("
                + JSONObject.quote(message)
                + ")";
        radarWebView.evaluateJavascript(script, null);
    }

    private void deliverLocationIfReady() {
        if (!pageLoaded || lastKnownLocation == null) {
            return;
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("lat", lastKnownLocation.getLatitude());
            payload.put("lng", lastKnownLocation.getLongitude());
            payload.put("accuracy", lastKnownLocation.hasAccuracy() ? lastKnownLocation.getAccuracy() : JSONObject.NULL);
            payload.put("bearing", lastKnownLocation.hasBearing() ? lastKnownLocation.getBearing() : JSONObject.NULL);
            payload.put("speedMps", lastKnownLocation.hasSpeed() ? lastKnownLocation.getSpeed() : JSONObject.NULL);
            payload.put("timestamp", lastKnownLocation.getTime());

            String script = "window.AxiomRadar && window.AxiomRadar.updateGpsPosition("
                    + JSONObject.quote(payload.toString())
                    + ")";
            radarWebView.evaluateJavascript(script, null);
        } catch (JSONException ignored) {
            notifyViewerLocationStatus("Failed to encode GPS position.");
        }
    }

    private boolean shouldAcceptLocation(@Nullable Location location) {
        if (location == null) {
            return false;
        }
        if (lastKnownLocation == null) {
            return true;
        }
        if (location.getTime() <= lastKnownLocation.getTime()) {
            return false;
        }
        if (location.distanceTo(lastKnownLocation) >= 10.0f) {
            return true;
        }
        if (location.hasAccuracy() && lastKnownLocation.hasAccuracy()) {
            return location.getAccuracy() < lastKnownLocation.getAccuracy();
        }
        return false;
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureLocationAccess() {
        if (hasLocationPermission()) {
            startLocationUpdatesIfPossible();
            return;
        }
        ActivityCompat.requestPermissions(
                this,
                new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                },
                LOCATION_PERMISSION_REQUEST_CODE
        );
    }

    private void startLocationUpdatesIfPossible() {
        if (locationManager == null || locationUpdatesActive || !hasLocationPermission()) {
            return;
        }

        boolean gpsEnabled = false;
        boolean networkEnabled = false;
        try {
            gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception ignored) {
        }
        try {
            networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception ignored) {
        }

        if (!gpsEnabled && !networkEnabled) {
            notifyViewerLocationStatus("Location services are off.");
            return;
        }

        locationUpdatesActive = true;
        notifyViewerLocationStatus("Waiting for GPS fix...");

        tryStartProvider(LocationManager.GPS_PROVIDER, gpsEnabled);
        tryStartProvider(LocationManager.NETWORK_PROVIDER, networkEnabled);
        readLastKnownLocation(gpsEnabled, networkEnabled);
    }

    @SuppressLint("MissingPermission")
    private void tryStartProvider(@NonNull String provider, boolean enabled) {
        if (!enabled) {
            return;
        }
        try {
            locationManager.requestLocationUpdates(
                    provider,
                    LOCATION_MIN_TIME_MS,
                    LOCATION_MIN_DISTANCE_METERS,
                    locationListener,
                    Looper.getMainLooper()
            );
        } catch (Exception exception) {
            notifyViewerLocationStatus("Unable to start " + provider + " updates.");
        }
    }

    @SuppressLint("MissingPermission")
    private void readLastKnownLocation(boolean gpsEnabled, boolean networkEnabled) {
        Location bestLocation = null;

        if (gpsEnabled) {
            bestLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        }
        if (bestLocation == null && networkEnabled) {
            bestLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        }
        if (bestLocation != null) {
            lastKnownLocation = bestLocation;
            deliverLocationIfReady();
        }
    }

    private void stopLocationUpdates() {
        if (!locationUpdatesActive || locationManager == null) {
            return;
        }
        locationUpdatesActive = false;
        try {
            locationManager.removeUpdates(locationListener);
        } catch (Exception ignored) {
        }
    }

    private String fetchSessionToken() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(SESSION_PAGE_URL).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "AxiomWebRadar/1.0");
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");

        try {
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("HTTP " + responseCode);
            }

            drainResponse(connection);

            String encodedCookie = extractSessionCookie(connection.getHeaderFields());
            if (encodedCookie == null || encodedCookie.isEmpty()) {
                throw new IOException("Missing mfsession cookie");
            }
            return rot13(encodedCookie);
        } finally {
            connection.disconnect();
        }
    }

    private void drainResponse(@NonNull HttpURLConnection connection) throws IOException {
        InputStream stream = connection.getInputStream();
        try {
            byte[] buffer = new byte[4096];
            while (stream.read(buffer) != -1) {
                // Fully consume the response so headers and cookies are available consistently.
            }
        } finally {
            stream.close();
        }
    }

    @Nullable
    private String extractSessionCookie(@NonNull Map<String, List<String>> headers) {
        List<String> values = headers.get("Set-Cookie");
        if (values == null) {
            values = headers.get("set-cookie");
        }
        if (values == null) {
            return null;
        }
        for (String header : values) {
            if (header == null || !header.startsWith("mfsession=")) {
                continue;
            }
            int separator = header.indexOf(';');
            return separator >= 0
                    ? header.substring("mfsession=".length(), separator)
                    : header.substring("mfsession=".length());
        }
        return null;
    }

    private String rot13(@NonNull String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 'a' && c <= 'z') {
                builder.append((char) ('a' + ((c - 'a' + 13) % 26)));
            } else if (c >= 'A' && c <= 'Z') {
                builder.append((char) ('A' + ((c - 'A' + 13) % 26)));
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private String buildBootstrapJson(@NonNull String sessionToken) throws JSONException {
        JSONObject bootstrap = new JSONObject();
        bootstrap.put("sessionToken", sessionToken);
        bootstrap.put("defaultPresetId", "martinique_50");
        bootstrap.put("generatedAt", System.currentTimeMillis());
        bootstrap.put("presets", new JSONArray()
                .put(createPreset(
                        "antilles",
                        "Antilles",
                        "https://meteofrance.mq/sites/default/files/2020-09/dirag_mosaique_radar_antilles.svg",
                        "https://meteofrance.mq/sites/default/files/2020-09/dirag_mosaique_radar_antilles_0.svg",
                        bbox(20.0, -67.707055434993, 10.0, -54.421559875624),
                        bbox(20.0, -65.183912286632, 10.0, -54.816087713368),
                        center(15.0, -61.06),
                        zoom(6.3, 6.3, 7.0, 6.1, 6.1, 7.2),
                        "BASE_REFLECTIVITY_ANTILLES",
                        "COUNTRY"
                ))
                .put(createPreset(
                        "martinique_200",
                        "200 km",
                        "https://meteofrance.mq/sites/default/files/2020-09/dirag_radar_martinique.svg",
                        "https://meteofrance.mq/sites/default/files/2020-09/dirag_radar_martinique_0.svg",
                        bbox(16.5, -64.071986211046, 12.5, -58.776467123433),
                        bbox(16.5, -63.066276439573, 12.5, -58.933723560427),
                        center(14.5, -58.0),
                        zoom(7.7, 7.7, 7.7, 7.3, 7.3, 7.3),
                        "BASE_REFLECTIVITY_MARTINIQUE_200",
                        "COUNTRY"
                ))
                .put(createPreset(
                        "martinique_50",
                        "50 km",
                        "https://meteofrance.mq/sites/default/files/2020-09/dirag_radar_martinique_1.svg",
                        "https://meteofrance.mq/sites/default/files/2020-09/dirag_radar_martinique_2.svg",
                        bbox(16.5, -64.071986211046, 12.5, -58.776467123433),
                        bbox(16.5, -63.066276439573, 12.5, -58.933723560427),
                        center(14.440285723046, -61.050834254769),
                        zoom(9.2, 9.2, 9.6, 8.5, 8.5, 9.8),
                        "BASE_REFLECTIVITY_MARTINIQUE_50",
                        "DEPARTEMENT"
                )));
        return bootstrap.toString();
    }

    private JSONObject createPreset(
            @NonNull String id,
            @NonNull String label,
            @NonNull String desktopSvg,
            @NonNull String mobileSvg,
            @NonNull JSONArray bbox,
            @NonNull JSONArray mobileBbox,
            @NonNull JSONArray center,
            @NonNull JSONObject zoom,
            @NonNull String radarLayer,
            @NonNull String contourLayer
    ) throws JSONException {
        JSONObject preset = new JSONObject();
        preset.put("id", id);
        preset.put("label", label);
        preset.put("bbox", bbox);
        preset.put("mobileBbox", mobileBbox);
        preset.put("center", center);
        preset.put("zoom", zoom);
        preset.put("desktopSvg", desktopSvg);
        preset.put("mobileSvg", mobileSvg);
        preset.put("periodMinutes", 15);
        preset.put("lookbackMinutes", 180);

        JSONObject layers = new JSONObject();
        layers.put("radar", new JSONObject()
                .put("baseUrl", "https://rwg.meteofrance.com/geoservices/mapcache-WMS?service=WMS&request=GetMap")
                .put("name", radarLayer)
                .put("style", "synopsis_reflectivity_oppidum_transparence")
                .put("format", "image/png")
                .put("version", "1.3.0"));
        layers.put("contours", new JSONObject()
                .put("baseUrl", "https://rwg.meteofrance.com/geoservices/CCG-mapcache-WMS?service=WMS&request=GetMap&transparent=TRUE")
                .put("name", contourLayer)
                .put("format", "image/png")
                .put("version", "1.3.0"));
        preset.put("layers", layers);
        return preset;
    }

    private JSONArray bbox(double north, double west, double south, double east) throws JSONException {
        return new JSONArray()
                .put(new JSONArray().put(north).put(west))
                .put(new JSONArray().put(south).put(east));
    }

    private JSONArray center(double lat, double lng) throws JSONException {
        return new JSONArray().put(lat).put(lng);
    }

    private JSONObject zoom(
            double initial,
            double min,
            double max,
            double mobileInitial,
            double mobileMin,
            double mobileMax
    ) throws JSONException {
        return new JSONObject()
                .put("initial", initial)
                .put("min", min)
                .put("max", max)
                .put("mobileInitial", mobileInitial)
                .put("mobileMin", mobileMin)
                .put("mobileMax", mobileMax);
    }

    private void enterImmersiveMode() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersiveMode();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCATION_PERMISSION_REQUEST_CODE) {
            return;
        }
        if (hasLocationPermission()) {
            startLocationUpdatesIfPossible();
        } else {
            notifyViewerLocationStatus("Location permission denied.");
        }
    }

    private final class RadarJavascriptBridge {
        @JavascriptInterface
        public void requestSessionRefresh() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    requestBootstrap(false, "Refreshing radar session...");
                }
            });
        }

        @JavascriptInterface
        public void requestLocationPermission() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    ensureLocationAccess();
                }
            });
        }

        @JavascriptInterface
        public void notifyViewerReady() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    showLoadingMessage("", false);
                    if (hasLocationPermission()) {
                        notifyViewerLocationStatus(lastKnownLocation == null ? "Waiting for GPS fix..." : "GPS ready.");
                    } else {
                        notifyViewerLocationStatus("Enable location to show device position.");
                    }
                }
            });
        }

        @JavascriptInterface
        public void setKeepScreenOn(final boolean keepScreenOn) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    radarWebView.setKeepScreenOn(keepScreenOn);
                }
            });
        }

        @JavascriptInterface
        public void postStatus(final String status) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (loadingOverlay.getVisibility() == View.VISIBLE) {
                        loadingText.setText(status);
                    }
                }
            });
        }
    }
}
