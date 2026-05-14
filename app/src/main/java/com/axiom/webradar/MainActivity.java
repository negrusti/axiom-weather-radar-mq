package com.axiom.webradar;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;
    private static final String SESSION_PAGE_URL = "https://meteofrance.mq/fr/images-radar/50km";
    private static final String RADAR_BASE_URL = "https://rwg.meteofrance.com/geoservices/mapcache-WMS?service=WMS&request=GetMap";
    private static final String DEM_BASE_URL = "https://rwg.meteofrance.com/geoservices/fond-WMS/mapcache?service=WMS&request=GetMap";
    private static final String CONTOUR_BASE_URL = "https://rwg.meteofrance.com/geoservices/CCG-mapcache-WMS?service=WMS&request=GetMap&transparent=TRUE";
    private static final long LOCATION_MIN_TIME_MS = 5L * 60L * 1000L;
    private static final float LOCATION_MIN_DISTANCE_METERS = 25.0f;
    private static final int MIN_REQUEST_WIDTH = 1024;
    private static final int MIN_REQUEST_HEIGHT = 768;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newFixedThreadPool(3);
    private final SimpleDateFormat displayTimeFormat = new SimpleDateFormat("M/d/yyyy, h:mm:ss a", Locale.US);
    private final SimpleDateFormat wmsTimeFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
    private final List<RadarPreset> presets = new ArrayList<>();
    private final LruCache<String, Bitmap> bitmapCache = new LruCache<String, Bitmap>(32 * 1024) {
        @Override
        protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };
    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (!shouldAcceptLocation(location)) {
                return;
            }
            lastKnownLocation = location;
            radarView.setDeviceLocation(location);
        }

        @Override
        public void onProviderDisabled(String provider) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }
    };
    private final Runnable playbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (!playing) {
                return;
            }
            advanceFrame(1);
            mainHandler.postDelayed(this, playbackDelayMs());
        }
    };

    private RadarMapView radarView;
    private View loadingOverlay;
    private TextView loadingText;
    private TextView timeLabel;
    private TextView speedValue;
    private SeekBar frameSlider;
    private SeekBar speedSlider;
    private Button playButton;
    private Button presetAntillesButton;
    private Button preset200Button;
    private Button preset50Button;
    private LocationManager locationManager;

    private RadarPreset currentPreset;
    private final List<Date> frameTimes = new ArrayList<>();
    private Location lastKnownLocation;
    private boolean locationUpdatesActive;
    private boolean destroyed;
    private boolean initialized;
    private boolean playing;
    private int currentFrameIndex;
    private int layerGeneration;
    private String activeRadarKey;
    private String sessionToken;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        wmsTimeFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        displayTimeFormat.setTimeZone(TimeZone.getDefault());

        buildPresets();
        bindViews();
        bindControls();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        enterImmersiveMode();

        radarView.post(new Runnable() {
            @Override
            public void run() {
                if (initialized) {
                    return;
                }
                initialized = true;
                selectPreset(findPreset("martinique_50"), true, true, false);
            }
        });
        ensureLocationAccess();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startLocationUpdatesIfPossible();
    }

    @Override
    protected void onStop() {
        stopPlayback();
        stopLocationUpdates();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        stopPlayback();
        stopLocationUpdates();
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
    }

    private void bindViews() {
        radarView = findViewById(R.id.radarView);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        loadingText = findViewById(R.id.loadingText);
        timeLabel = findViewById(R.id.timeLabel);
        speedValue = findViewById(R.id.speedValue);
        frameSlider = findViewById(R.id.timelineSlider);
        speedSlider = findViewById(R.id.speedSlider);
        playButton = findViewById(R.id.playButton);
        presetAntillesButton = findViewById(R.id.presetAntillesButton);
        preset200Button = findViewById(R.id.preset200Button);
        preset50Button = findViewById(R.id.preset50Button);
    }

    private void bindControls() {
        presetAntillesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectPreset(findPreset("antilles"), true, true, false);
            }
        });
        preset200Button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectPreset(findPreset("martinique_200"), true, true, false);
            }
        });
        preset50Button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectPreset(findPreset("martinique_50"), true, true, false);
            }
        });

        findViewById(R.id.zoomInButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                radarView.zoomBy(1.25f);
            }
        });
        findViewById(R.id.zoomOutButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                radarView.zoomBy(0.8f);
            }
        });
        findViewById(R.id.prevButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopPlayback();
                advanceFrame(-1);
            }
        });
        findViewById(R.id.nextButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopPlayback();
                advanceFrame(1);
            }
        });
        playButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setPlaying(!playing);
            }
        });
        findViewById(R.id.refreshButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshCurrentPreset();
            }
        });

        speedSlider.setMax(7);
        speedSlider.setProgress(1);
        updateSpeedLabel();
        speedSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateSpeedLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (playing) {
                    restartPlayback();
                }
            }
        });

        frameSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || progress == currentFrameIndex || progress >= frameTimes.size()) {
                    return;
                }
                currentFrameIndex = progress;
                updateTimeLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                stopPlayback();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                loadRadarFrame(false);
            }
        });
    }

    private void buildPresets() {
        presets.add(new RadarPreset(
                "antilles",
                "Antilles",
                20.0d,
                -67.707055434993d,
                10.0d,
                -54.421559875624d,
                "BASE_REFLECTIVITY_ANTILLES",
                "COUNTRY"
        ));
        presets.add(new RadarPreset(
                "martinique_200",
                "200 km",
                16.5d,
                -64.071986211046d,
                12.5d,
                -58.776467123433d,
                "BASE_REFLECTIVITY_MARTINIQUE_200",
                "COUNTRY"
        ));
        presets.add(new RadarPreset(
                "martinique_50",
                "50 km",
                16.5d,
                -64.071986211046d,
                12.5d,
                -58.776467123433d,
                "BASE_REFLECTIVITY_MARTINIQUE_50",
                "DEPARTEMENT"
        ));
    }

    private void selectPreset(
            @NonNull RadarPreset preset,
            boolean showLoading,
            boolean resetTransform,
            boolean forceTokenRefresh
    ) {
        currentPreset = preset;
        stopPlayback();
        rebuildFrameTimes();
        currentFrameIndex = Math.max(frameTimes.size() - 1, 0);
        updatePresetButtons();
        updateFrameSlider();
        updateTimeLabel();
        activeRadarKey = null;
        radarView.setPreset(preset, resetTransform);
        radarView.setBaseBitmap(null);
        radarView.setContourBitmap(null);
        radarView.setRadarBitmap(null);
        if (lastKnownLocation != null) {
            radarView.setDeviceLocation(lastKnownLocation);
        }
        loadAllLayers(showLoading, forceTokenRefresh);
    }

    private void refreshCurrentPreset() {
        if (currentPreset == null) {
            return;
        }
        stopPlayback();
        rebuildFrameTimes();
        currentFrameIndex = Math.max(frameTimes.size() - 1, 0);
        updateFrameSlider();
        updateTimeLabel();
        loadAllLayers(true, true);
    }

    private void loadAllLayers(boolean showLoading, boolean forceTokenRefresh) {
        if (currentPreset == null || radarView.getWidth() <= 0 || radarView.getHeight() <= 0) {
            return;
        }
        final int generation = ++layerGeneration;
        activeRadarKey = null;
        if (showLoading) {
            showLoadingMessage("Loading " + currentPreset.label + "...", true);
        }
        ensureSessionToken(generation, forceTokenRefresh, new Runnable() {
            @Override
            public void run() {
                if (generation != layerGeneration || destroyed) {
                    return;
                }
                loadStaticLayer(
                        generation,
                        staticLayerKey(currentPreset, "dem"),
                        buildBaseUrl(currentPreset),
                        new BitmapConsumer() {
                            @Override
                            public void accept(@Nullable Bitmap bitmap) {
                                if (bitmap != null) {
                                    radarView.setBaseBitmap(bitmap);
                                }
                            }
                        }
                );
                loadStaticLayer(
                        generation,
                        staticLayerKey(currentPreset, "contour"),
                        buildContourUrl(currentPreset),
                        new BitmapConsumer() {
                            @Override
                            public void accept(@Nullable Bitmap bitmap) {
                                if (bitmap != null) {
                                    radarView.setContourBitmap(bitmap);
                                }
                            }
                        }
                );
                loadRadarFrame(showLoading);
            }
        });
    }

    private void loadStaticLayer(
            final int generation,
            @NonNull final String cacheKey,
            @NonNull final String url,
            @NonNull final BitmapConsumer consumer
    ) {
        Bitmap cached = bitmapCache.get(cacheKey);
        if (cached != null) {
            consumer.accept(cached);
            return;
        }

        networkExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Bitmap bitmap = fetchBitmap(url);
                    if (bitmap != null) {
                        bitmapCache.put(cacheKey, bitmap);
                    }
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed || generation != layerGeneration) {
                                return;
                            }
                            consumer.accept(bitmap);
                        }
                    });
                } catch (final Exception ignored) {
                }
            }
        });
    }

    private void loadRadarFrame(final boolean showLoading) {
        if (currentPreset == null || frameTimes.isEmpty()) {
            return;
        }
        final Date frameTime = frameTimes.get(currentFrameIndex);
        final String radarKey = radarFrameKey(currentPreset, frameTime);
        activeRadarKey = radarKey;
        updateFrameSlider();
        updateTimeLabel();

        Bitmap cached = bitmapCache.get(radarKey);
        if (cached != null) {
            radarView.setRadarBitmap(cached);
            if (showLoading) {
                showLoadingMessage("", false);
            }
            return;
        }

        networkExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Bitmap bitmap = fetchBitmap(buildRadarUrl(currentPreset, frameTime));
                    if (bitmap != null) {
                        bitmapCache.put(radarKey, bitmap);
                    }
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed || !radarKey.equals(activeRadarKey)) {
                                return;
                            }
                            if (bitmap != null) {
                                radarView.setRadarBitmap(bitmap);
                            }
                            if (showLoading) {
                                showLoadingMessage("", false);
                            }
                        }
                    });
                } catch (final Exception exception) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed || !radarKey.equals(activeRadarKey)) {
                                return;
                            }
                            showLoadingMessage("Unable to load radar frame.", true);
                        }
                    });
                }
            }
        });
    }

    private void ensureSessionToken(
            final int generation,
            boolean forceRefresh,
            @NonNull final Runnable onReady
    ) {
        if (sessionToken != null && !forceRefresh) {
            onReady.run();
            return;
        }

        networkExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final String fetchedToken = fetchSessionToken();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed || generation != layerGeneration) {
                                return;
                            }
                            sessionToken = fetchedToken;
                            onReady.run();
                        }
                    });
                } catch (final Exception exception) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed || generation != layerGeneration) {
                                return;
                            }
                            showLoadingMessage("Radar session failed: " + exception.getMessage(), true);
                        }
                    });
                }
            }
        });
    }

    private void rebuildFrameTimes() {
        frameTimes.clear();
        if (currentPreset == null) {
            return;
        }

        Date end = alignedFrameTime();
        long periodMs = currentPreset.periodMinutes * 60L * 1000L;
        int count = currentPreset.lookbackMinutes / currentPreset.periodMinutes;
        long endMs = end.getTime();

        for (int index = count; index >= 0; index--) {
            frameTimes.add(new Date(endMs - index * periodMs));
        }
    }

    @NonNull
    private Date alignedFrameTime() {
        Date now = new Date();
        @SuppressWarnings("deprecation")
        int minutes = now.getMinutes();

        if (minutes < 20) {
            now.setMinutes(0);
        } else if (minutes < 35) {
            now.setMinutes(15);
        } else if (minutes < 50) {
            now.setMinutes(30);
        } else {
            now.setMinutes(45);
        }
        now.setSeconds(0);
        now.setTime((now.getTime() / 1000L) * 1000L);
        return now;
    }

    private void advanceFrame(int direction) {
        if (frameTimes.isEmpty()) {
            return;
        }
        currentFrameIndex += direction;
        if (currentFrameIndex < 0) {
            currentFrameIndex = frameTimes.size() - 1;
        } else if (currentFrameIndex >= frameTimes.size()) {
            currentFrameIndex = 0;
        }
        loadRadarFrame(false);
    }

    private void setPlaying(boolean enabled) {
        playing = enabled;
        playButton.setText(enabled ? "Pause" : "Play");
        mainHandler.removeCallbacks(playbackRunnable);
        if (enabled) {
            mainHandler.postDelayed(playbackRunnable, playbackDelayMs());
        }
    }

    private void restartPlayback() {
        if (!playing) {
            return;
        }
        setPlaying(true);
    }

    private void stopPlayback() {
        setPlaying(false);
    }

    private long playbackDelayMs() {
        return Math.max(250L, Math.round(1100.0d / currentSpeedMultiplier()));
    }

    private double currentSpeedMultiplier() {
        return 0.5d + (speedSlider.getProgress() * 0.5d);
    }

    private void updateSpeedLabel() {
        speedValue.setText(String.format(Locale.US, "%.1fx", currentSpeedMultiplier()));
    }

    private void updateFrameSlider() {
        frameSlider.setMax(Math.max(frameTimes.size() - 1, 0));
        frameSlider.setProgress(Math.min(currentFrameIndex, frameSlider.getMax()));
    }

    private void updateTimeLabel() {
        if (frameTimes.isEmpty()) {
            timeLabel.setText("--");
            return;
        }
        timeLabel.setText(displayTimeFormat.format(frameTimes.get(currentFrameIndex)));
    }

    private void updatePresetButtons() {
        stylePresetButton(presetAntillesButton, currentPreset != null && "antilles".equals(currentPreset.id));
        stylePresetButton(preset200Button, currentPreset != null && "martinique_200".equals(currentPreset.id));
        stylePresetButton(preset50Button, currentPreset != null && "martinique_50".equals(currentPreset.id));
    }

    private void stylePresetButton(@NonNull Button button, boolean selected) {
        button.setBackgroundColor(Color.parseColor(selected ? "#1B64A0" : "#00000000"));
        button.setTextColor(Color.parseColor(selected ? "#FFFFFF" : "#16598F"));
    }

    private String staticLayerKey(@NonNull RadarPreset preset, @NonNull String suffix) {
        return preset.id + ":" + suffix + ":" + requestWidth() + "x" + requestHeight();
    }

    private String radarFrameKey(@NonNull RadarPreset preset, @NonNull Date date) {
        return preset.id + ":radar:" + wmsTimeFormat.format(date) + ":" + requestWidth() + "x" + requestHeight();
    }

    private int requestWidth() {
        return Math.max(radarView.getWidth(), MIN_REQUEST_WIDTH);
    }

    private int requestHeight() {
        return Math.max(radarView.getHeight(), MIN_REQUEST_HEIGHT);
    }

    private String buildBaseUrl(@NonNull RadarPreset preset) {
        return buildWmsUrl(
                DEM_BASE_URL,
                "DEM",
                null,
                requestWidth(),
                requestHeight(),
                preset,
                null
        );
    }

    private String buildContourUrl(@NonNull RadarPreset preset) {
        return buildWmsUrl(
                CONTOUR_BASE_URL,
                preset.contourLayer,
                null,
                requestWidth(),
                requestHeight(),
                preset,
                null
        );
    }

    private String buildRadarUrl(@NonNull RadarPreset preset, @NonNull Date frameTime) {
        return buildWmsUrl(
                RADAR_BASE_URL,
                preset.radarLayer,
                "synopsis_reflectivity_oppidum_transparence",
                requestWidth(),
                requestHeight(),
                preset,
                frameTime
        );
    }

    private String buildWmsUrl(
            @NonNull String baseUrl,
            @NonNull String layer,
            @Nullable String style,
            int width,
            int height,
            @NonNull RadarPreset preset,
            @Nullable Date time
    ) {
        StringBuilder builder = new StringBuilder(baseUrl);
        builder.append("&layers=").append(urlEncode(layer));
        builder.append("&format=").append(urlEncode("image/png"));
        builder.append("&version=").append(urlEncode("1.3.0"));
        builder.append("&crs=").append(urlEncode("EPSG:3857"));
        builder.append("&width=").append(width);
        builder.append("&height=").append(height);
        builder.append("&bbox=").append(urlEncode(preset.bboxParam()));
        builder.append("&transparent=TRUE");
        if (style != null) {
            builder.append("&styles=").append(urlEncode(style));
        }
        if (time != null) {
            builder.append("&time=").append(urlEncode(wmsTimeFormat.format(time)));
        }
        if (sessionToken != null) {
            builder.append("&token=").append(urlEncode(sessionToken));
        }
        return builder.toString();
    }

    private Bitmap fetchBitmap(@NonNull String urlText) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setUseCaches(true);
        connection.setRequestProperty("User-Agent", "AxiomWebRadar/1.0");

        try {
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("HTTP " + responseCode);
            }

            InputStream stream = connection.getInputStream();
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                return BitmapFactory.decodeStream(stream, null, options);
            } finally {
                stream.close();
            }
        } finally {
            connection.disconnect();
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
                // Fully consume the response so the cookie headers are available consistently.
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
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
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

    private String urlEncode(@NonNull String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    private RadarPreset findPreset(@NonNull String id) {
        for (RadarPreset preset : presets) {
            if (id.equals(preset.id)) {
                return preset;
            }
        }
        return presets.get(0);
    }

    private void showLoadingMessage(@NonNull String message, boolean visible) {
        loadingText.setText(message);
        loadingOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
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
        return (location.getTime() - lastKnownLocation.getTime()) >= LOCATION_MIN_TIME_MS;
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
            return;
        }

        locationUpdatesActive = true;
        requestProvider(LocationManager.GPS_PROVIDER, gpsEnabled);
        requestProvider(LocationManager.NETWORK_PROVIDER, networkEnabled);
        readLastKnownLocation(gpsEnabled, networkEnabled);
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

    @SuppressWarnings("MissingPermission")
    private void requestProvider(@NonNull String provider, boolean enabled) {
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
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("MissingPermission")
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
            radarView.setDeviceLocation(bestLocation);
        }
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
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE && hasLocationPermission()) {
            startLocationUpdatesIfPossible();
        }
    }

    private interface BitmapConsumer {
        void accept(@Nullable Bitmap bitmap);
    }
}
