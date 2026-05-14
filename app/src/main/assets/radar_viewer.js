(function () {
    var WMS_CRS = L.CRS.EPSG3857;
    var TOKEN_REFRESH_INTERVAL_MS = 45 * 60 * 1000;
    var PLAYBACK_BASE_DELAY_MS = 1100;

    var state = {
        map: null,
        baseOverlay: null,
        contourLayer: null,
        radarLayer: null,
        currentPresetId: null,
        presetsById: {},
        bootstrap: null,
        frameTimes: [],
        frameIndex: 0,
        playing: false,
        playbackTimer: null,
        refreshTimer: null,
        tokenRefreshPending: false,
        gpsPosition: null,
        gpsMarker: null,
        gpsCircle: null
    };

    var elements = {
        presetButtons: document.getElementById("presetButtons"),
        timeLabel: document.getElementById("timeLabel"),
        timelineSlider: document.getElementById("timelineSlider"),
        speedSlider: document.getElementById("speedSlider"),
        speedValue: document.getElementById("speedValue"),
        playButton: document.getElementById("playButton"),
        prevButton: document.getElementById("prevButton"),
        nextButton: document.getElementById("nextButton"),
        refreshButton: document.getElementById("refreshButton")
    };

    function nativeBridge() {
        return window.AndroidRadarApp || null;
    }

    function postNativeStatus(text) {
        var bridge = nativeBridge();
        if (bridge && typeof bridge.postStatus === "function") {
            bridge.postStatus(text);
        }
    }

    function setStatus(text) {
        postNativeStatus(text);
    }

    function notifyViewerReady() {
        var bridge = nativeBridge();
        if (bridge && typeof bridge.notifyViewerReady === "function") {
            bridge.notifyViewerReady();
        }
        if (bridge && typeof bridge.setKeepScreenOn === "function") {
            bridge.setKeepScreenOn(true);
        }
    }

    function requestNativeRefresh(reason) {
        if (state.tokenRefreshPending) {
            return;
        }
        state.tokenRefreshPending = true;
        setStatus(reason || "Refreshing radar session...");
        var bridge = nativeBridge();
        if (bridge && typeof bridge.requestSessionRefresh === "function") {
            bridge.requestSessionRefresh();
        } else {
            state.tokenRefreshPending = false;
            setStatus("Native refresh bridge unavailable.");
        }
    }

    function clearLayer(layer) {
        if (layer && state.map) {
            state.map.removeLayer(layer);
        }
    }

    function clearPlaybackTimer() {
        if (state.playbackTimer) {
            window.clearTimeout(state.playbackTimer);
            state.playbackTimer = null;
        }
    }

    function clearRefreshTimer() {
        if (state.refreshTimer) {
            window.clearInterval(state.refreshTimer);
            state.refreshTimer = null;
        }
    }

    function ensureMap() {
        if (state.map) {
            return;
        }

        state.map = L.map("map", {
            zoomControl: false,
            attributionControl: true,
            scrollWheelZoom: false,
            dragging: true,
            tap: true,
            zoomSnap: 0.1,
            zoomDelta: 0.2,
            maxBoundsViscosity: 1.0
        });

        state.map.createPane("basePane");
        state.map.getPane("basePane").style.zIndex = "150";
        state.map.getPane("tilePane").style.zIndex = "450";
        state.map.getPane("overlayPane").style.zIndex = "500";

        L.control.zoom({ position: "topleft" }).addTo(state.map);
        state.map.attributionControl.setPrefix("Leaflet");

        window.addEventListener("resize", function () {
            if (state.map) {
                state.map.invalidateSize();
            }
        });
    }

    function isCompactLayout() {
        return window.innerWidth < 760;
    }

    function pickBounds(preset) {
        return isCompactLayout() ? preset.mobileBbox : preset.bbox;
    }

    function pickZoom(preset) {
        if (isCompactLayout()) {
            return {
                initial: preset.zoom.mobileInitial,
                min: preset.zoom.mobileMin,
                max: preset.zoom.mobileMax
            };
        }
        return {
            initial: preset.zoom.initial,
            min: preset.zoom.min,
            max: preset.zoom.max
        };
    }

    function buildFrames(preset) {
        var end = new Date();
        var minutes = end.getMinutes();
        var frames = [];
        var count;
        var index;

        if (minutes < 20) {
            end.setMinutes(0);
        } else if (minutes < 35) {
            end.setMinutes(15);
        } else if (minutes < 50) {
            end.setMinutes(30);
        } else {
            end.setMinutes(45);
        }

        end.setSeconds(0);
        end.setMilliseconds(0);

        count = Math.floor(preset.lookbackMinutes / preset.periodMinutes);
        for (index = count; index >= 0; index -= 1) {
            frames.push(new Date(end.getTime() - index * preset.periodMinutes * 60000));
        }

        return frames;
    }

    function wmsUrl(baseUrl) {
        return baseUrl + "&token=" + encodeURIComponent(state.bootstrap.sessionToken);
    }

    function bindTileError(layer) {
        layer.on("tileerror", function () {
            requestNativeRefresh("Refreshing expired radar session...");
        });
    }

    function refreshLayerUrls() {
        var preset = state.presetsById[state.currentPresetId];
        if (!preset) {
            return;
        }

        if (state.contourLayer) {
            state.contourLayer.setUrl(wmsUrl(preset.layers.contours.baseUrl));
            state.contourLayer.redraw();
        }

        if (state.radarLayer) {
            state.radarLayer.setUrl(wmsUrl(preset.layers.radar.baseUrl));
            state.radarLayer.redraw();
        }
    }

    function updateGpsLayer() {
        var latLng;
        var accuracy;

        if (!state.gpsPosition || !state.map) {
            return;
        }

        latLng = [state.gpsPosition.lat, state.gpsPosition.lng];
        accuracy = typeof state.gpsPosition.accuracy === "number" ? state.gpsPosition.accuracy : 0;

        if (!state.gpsMarker) {
            state.gpsMarker = L.circleMarker(latLng, {
                radius: 7,
                color: "#ffffff",
                weight: 3,
                fillColor: "#1b64a0",
                fillOpacity: 1
            }).addTo(state.map);
        } else {
            state.gpsMarker.setLatLng(latLng);
        }

        if (!state.gpsCircle) {
            state.gpsCircle = L.circle(latLng, {
                radius: Math.max(accuracy, 8),
                color: "#1b64a0",
                weight: 2,
                opacity: 0.5,
                fillColor: "#1b64a0",
                fillOpacity: 0.16
            }).addTo(state.map);
        } else {
            state.gpsCircle.setLatLng(latLng);
            state.gpsCircle.setRadius(Math.max(accuracy, 8));
        }
    }

    function renderPresetButtons() {
        elements.presetButtons.innerHTML = "";

        state.bootstrap.presets.forEach(function (preset) {
            state.presetsById[preset.id] = preset;

            var button = document.createElement("button");
            button.className = "preset-button" + (preset.id === state.currentPresetId ? " active" : "");
            button.type = "button";
            button.textContent = preset.label;
            button.onclick = function () {
                state.currentPresetId = preset.id;
                applyPreset(false);
            };
            elements.presetButtons.appendChild(button);
        });
    }

    function updatePresetButtonState() {
        var currentPreset = state.presetsById[state.currentPresetId];
        var buttons = elements.presetButtons.querySelectorAll(".preset-button");
        Array.prototype.forEach.call(buttons, function (button) {
            button.classList.toggle("active", currentPreset && button.textContent === currentPreset.label);
        });
    }

    function applyPreset(preserveViewport) {
        var preset = state.presetsById[state.currentPresetId];
        var bounds = pickBounds(preset);
        var zoom = pickZoom(preset);
        var previousTime = state.frameTimes.length > 0 ? state.frameTimes[state.frameIndex] : null;

        ensureMap();
        clearLayer(state.baseOverlay);
        clearLayer(state.contourLayer);
        clearLayer(state.radarLayer);

        state.map.setMaxBounds(bounds);
        state.map.setMinZoom(zoom.min);
        state.map.setMaxZoom(zoom.max);

        if (!preserveViewport) {
            state.map.setView(preset.center, zoom.initial);
        }

        state.baseOverlay = L.imageOverlay(isCompactLayout() ? preset.mobileSvg : preset.desktopSvg, bounds, {
            opacity: 1.0,
            interactive: false,
            pane: "basePane"
        }).addTo(state.map);

        state.contourLayer = L.tileLayer.wms(wmsUrl(preset.layers.contours.baseUrl), {
            layers: preset.layers.contours.name,
            format: preset.layers.contours.format,
            transparent: true,
            version: preset.layers.contours.version,
            crs: WMS_CRS,
            opacity: 1
        }).addTo(state.map);

        state.radarLayer = L.tileLayer.wms(wmsUrl(preset.layers.radar.baseUrl), {
            layers: preset.layers.radar.name,
            format: preset.layers.radar.format,
            styles: preset.layers.radar.style,
            transparent: true,
            version: preset.layers.radar.version,
            crs: WMS_CRS,
            opacity: 1,
            tileSize: 512,
            keepBuffer: 2
        }).addTo(state.map);

        bindTileError(state.contourLayer);
        bindTileError(state.radarLayer);

        state.frameTimes = buildFrames(preset);
        state.frameIndex = previousTime ? findClosestFrameIndex(previousTime) : state.frameTimes.length - 1;

        elements.timelineSlider.min = "0";
        elements.timelineSlider.max = String(Math.max(state.frameTimes.length - 1, 0));
        elements.timelineSlider.value = String(state.frameIndex);

        updatePresetButtonState();
        drawCurrentFrame();
        updateGpsLayer();
        setStatus("Live radar ready.");
    }

    function findClosestFrameIndex(previousTime) {
        var previousValue = previousTime.getTime();
        var bestIndex = state.frameTimes.length - 1;
        var bestDistance = Number.MAX_VALUE;

        state.frameTimes.forEach(function (frame, index) {
            var distance = Math.abs(frame.getTime() - previousValue);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        });

        return bestIndex;
    }

    function formatFrameTime(date) {
        return date.toLocaleString(undefined, {
            year: "numeric",
            month: "numeric",
            day: "numeric",
            hour: "numeric",
            minute: "2-digit",
            second: "2-digit"
        });
    }

    function drawCurrentFrame() {
        var frame = state.frameTimes[state.frameIndex];
        if (!frame || !state.radarLayer) {
            return;
        }

        elements.timelineSlider.value = String(state.frameIndex);
        elements.timeLabel.textContent = formatFrameTime(frame);
        state.radarLayer.setParams({ time: frame.toISOString() }, false);
    }

    function stepFrame(direction) {
        if (!state.frameTimes.length) {
            return;
        }

        state.frameIndex += direction;
        if (state.frameIndex < 0) {
            state.frameIndex = state.frameTimes.length - 1;
        } else if (state.frameIndex >= state.frameTimes.length) {
            state.frameIndex = 0;
        }
        drawCurrentFrame();
    }

    function playbackDelay() {
        var speed = parseFloat(elements.speedSlider.value || "1");
        return Math.max(250, Math.round(PLAYBACK_BASE_DELAY_MS / speed));
    }

    function schedulePlayback() {
        clearPlaybackTimer();
        if (!state.playing) {
            return;
        }
        state.playbackTimer = window.setTimeout(function () {
            stepFrame(1);
            schedulePlayback();
        }, playbackDelay());
    }

    function setPlaying(playing) {
        state.playing = playing;
        elements.playButton.textContent = playing ? "Pause" : "Play";
        schedulePlayback();
    }

    function scheduleTokenRefresh() {
        clearRefreshTimer();
        state.refreshTimer = window.setInterval(function () {
            requestNativeRefresh("Refreshing radar session...");
        }, TOKEN_REFRESH_INTERVAL_MS);
    }

    function bindControls() {
        elements.prevButton.onclick = function () {
            setPlaying(false);
            stepFrame(-1);
        };

        elements.nextButton.onclick = function () {
            setPlaying(false);
            stepFrame(1);
        };

        elements.playButton.onclick = function () {
            setPlaying(!state.playing);
        };

        elements.refreshButton.onclick = function () {
            requestNativeRefresh("Refreshing radar session...");
        };

        elements.timelineSlider.oninput = function () {
            setPlaying(false);
            state.frameIndex = parseInt(elements.timelineSlider.value, 10) || 0;
            drawCurrentFrame();
        };

        elements.speedSlider.oninput = function () {
            var speed = parseFloat(elements.speedSlider.value || "1");
            elements.speedValue.textContent = speed.toFixed(1) + "x";
            if (state.playing) {
                schedulePlayback();
            }
        };

        elements.speedValue.textContent = parseFloat(elements.speedSlider.value).toFixed(1) + "x";
    }

    function initialize() {
        bindControls();
        scheduleTokenRefresh();
        notifyViewerReady();
    }

    window.AxiomRadar = {
        bootstrap: function (bootstrapJson) {
            var data = JSON.parse(bootstrapJson);
            var preserveViewport = !!state.map;

            state.bootstrap = data;
            state.presetsById = {};
            state.tokenRefreshPending = false;
            state.currentPresetId = state.currentPresetId || data.defaultPresetId;

            renderPresetButtons();
            applyPreset(preserveViewport);

            if (!state.map._axiomInitialized) {
                state.map._axiomInitialized = true;
                initialize();
            } else {
                refreshLayerUrls();
            }
        },

        showNativeError: function (message) {
            state.tokenRefreshPending = false;
            setStatus(message);
        },

        updateGpsPosition: function (gpsJson) {
            state.gpsPosition = JSON.parse(gpsJson);
            updateGpsLayer();
        },

        setGpsStatus: function (message) {
            setStatus(message);
        }
    };
}());
