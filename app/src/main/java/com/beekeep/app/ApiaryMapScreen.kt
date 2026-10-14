package com.beekeep.app

import android.Manifest
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.beekeep.app.data.Apiary
import com.beekeep.app.location.LocationController
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

@Composable
internal fun ApiaryMapScreen(
    apiaries: List<Apiary>,
    padding: PaddingValues,
    initialFocusApiaryId: Long?,
    locationController: LocationController,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    var focusedApiaryId by rememberSaveable(initialFocusApiaryId) {
        mutableStateOf(initialFocusApiaryId)
    }
    var currentLocation by remember { mutableStateOf<LocationController.Result?>(null) }
    var locationMessage by remember { mutableStateOf<String?>(null) }

    fun applyCurrentLocation(location: LocationController.Result?) {
        if (location == null) {
            currentLocation = null
            locationMessage = "Couldn't get your location. Make sure Location is enabled and try again."
        } else {
            currentLocation = location
            focusedApiaryId = null
            locationMessage = null
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            locationController.current { location -> applyCurrentLocation(location) }
        } else {
            locationMessage = "Location permission is needed to show your current position."
        }
    }

    fun showMyLocation() {
        locationMessage = "Finding your location…"
        if (locationController.hasPermission()) {
            locationController.current { location -> applyCurrentLocation(location) }
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val locatedApiaries = apiaries.filter { it.latitude != null && it.longitude != null }
    val focusedId = focusedApiaryId?.takeIf { id -> locatedApiaries.any { it.id == id } }

    // The map is the full screen content layer. Controls are overlays, not rows
    // that take height away from the actual AndroidView/WebView canvas.
    Box(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .background(MaterialTheme.colorScheme.background)
    ) {
        ApiarySatelliteMap(
            apiaries = locatedApiaries,
            focusedApiaryId = focusedId,
            currentLocation = currentLocation,
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(22.dp))
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            tonalElevation = 4.dp,
            shadowElevation = 3.dp
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, "Back")
                }
                Column(
                    Modifier.weight(1f).padding(vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Text(
                        "Apiary Map",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        "${locatedApiaries.size} of ${apiaries.size} apiaries have GPS pins",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    focusedApiaryId = null
                    currentLocation = null
                    locationMessage = null
                }) {
                    Icon(Icons.Rounded.GpsFixed, "Show all saved apiary locations")
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            tonalElevation = 5.dp,
            shadowElevation = 4.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            IconButton(
                onClick = { showMyLocation() },
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    Icons.Rounded.MyLocation,
                    contentDescription = "My location",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(25.dp)
                )
            }
        }

        if (locationMessage != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 150.dp, start = 16.dp, end = 16.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                tonalElevation = 4.dp
            ) {
                Text(
                    locationMessage.orEmpty(),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        if (locatedApiaries.isEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                tonalElevation = 4.dp,
                shadowElevation = 3.dp
            ) {
                Column(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text("No saved GPS pins yet", fontWeight = FontWeight.Bold)
                    Text(
                        "Edit an apiary and capture its current GPS to place it on the map.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    // Place apiary cards immediately below the floating title bar so
                    // they never cover MapLibre's bottom attribution control.
                    .padding(start = 10.dp, end = 10.dp, top = 82.dp, bottom = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                locatedApiaries.forEach { apiary ->
                    val isFocused = focusedId == apiary.id
                    Surface(
                        modifier = Modifier
                            .width(174.dp)
                            .clickable {
                                currentLocation = null
                                locationMessage = null
                                focusedApiaryId = apiary.id
                            },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isFocused) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.97f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                        },
                        border = if (isFocused) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                        tonalElevation = 3.dp,
                        shadowElevation = 2.dp
                    ) {
                        Column(
                            Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                apiary.name,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1
                            )
                            Text(
                                "${"%.4f".format(Locale.US, apiary.latitude)}, ${"%.4f".format(Locale.US, apiary.longitude)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class ApiaryMapWebViewState(
    val htmlHash: Int,
    val currentLocation: LocationController.Result?
)

@Composable
internal fun ApiarySatelliteMap(
    apiaries: List<Apiary>,
    focusedApiaryId: Long?,
    currentLocation: LocationController.Result?,
    modifier: Modifier = Modifier
) {
    // Keep the WebView and MapLibre map alive when only the GPS position changes.
    // The location button should move the existing map, not reload the entire page.
    val html = remember(apiaries, focusedApiaryId) {
        createApiaryMapHtml(apiaries, focusedApiaryId)
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        // Re-apply the latest GPS after any map HTML refresh. The JS
                        // function queues the location if MapLibre has not loaded yet.
                        val location = (view.tag as? ApiaryMapWebViewState)?.currentLocation
                        if (location != null) {
                            view.evaluateJavascript(
                                "window.beekeepQueueLocation && window.beekeepQueueLocation(${location.latitude}, ${location.longitude});",
                                null
                            )
                        }
                    }
                }
                setBackgroundColor(android.graphics.Color.rgb(231, 232, 228))
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                tag = ApiaryMapWebViewState(html.hashCode(), currentLocation)
                // Compose can resize AndroidView without firing a browser window resize.
                // Notify MapLibre whenever the native WebView viewport changes size.
                addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                    if ((right - left) != (oldRight - oldLeft) ||
                        (bottom - top) != (oldBottom - oldTop)
                    ) {
                        view.post {
                            (view as? WebView)?.evaluateJavascript(
                                "window.beekeepResizeMap && window.beekeepResizeMap();",
                                null
                            )
                        }
                    }
                }
                loadDataWithBaseURL("https://beekeep.local/map/", html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            val htmlHash = html.hashCode()
            val previous = webView.tag as? ApiaryMapWebViewState
            if (previous == null || previous.htmlHash != htmlHash) {
                // A changed apiary list or selected apiary requires fresh map content.
                // Store the latest GPS so onPageFinished can restore it after loading.
                webView.tag = ApiaryMapWebViewState(htmlHash, currentLocation)
                webView.loadDataWithBaseURL("https://beekeep.local/map/", html, "text/html", "UTF-8", null)
            } else if (previous.currentLocation != currentLocation) {
                webView.tag = ApiaryMapWebViewState(htmlHash, currentLocation)
                if (currentLocation != null) {
                    webView.evaluateJavascript(
                        "window.beekeepQueueLocation && window.beekeepQueueLocation(${currentLocation.latitude}, ${currentLocation.longitude});",
                        null
                    )
                } else {
                    webView.evaluateJavascript(
                        "window.beekeepClearCurrentLocation && window.beekeepClearCurrentLocation();",
                        null
                    )
                }
            }
        }
    )
}

private fun createApiaryMapHtml(
    apiaries: List<Apiary>,
    focusedApiaryId: Long?
): String {
    val located = apiaries.filter { it.latitude != null && it.longitude != null }
    val markers = JSONArray()
    located.forEach { apiary ->
        markers.put(
            JSONObject()
                .put("id", apiary.id.toString())
                .put("name", apiary.name)
                .put("lat", apiary.latitude)
                .put("lon", apiary.longitude)
        )
    }
    val focus = located.firstOrNull { it.id == focusedApiaryId }
    val centerLat = focus?.latitude
        ?: if (located.isNotEmpty()) located.mapNotNull { it.latitude }.average() else 20.0
    val centerLon = focus?.longitude
        ?: if (located.isNotEmpty()) located.mapNotNull { it.longitude }.average() else 0.0
    val initialZoom = when {
        focus != null -> 17
        located.size == 1 -> 16
        located.size > 1 -> 5
        else -> 2
    }
    val focusIdJson = focusedApiaryId?.takeIf { id -> located.any { it.id == id } }
        ?.let { JSONObject.quote(it.toString()) } ?: "null"

    return """
        <!doctype html>
        <html lang="en">
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
            <meta charset="utf-8">
            <link rel="stylesheet"
                  href="https://unpkg.com/maplibre-gl@5.12.0/dist/maplibre-gl.css"
                  onerror="this.onerror=null;this.href='https://cdn.jsdelivr.net/npm/maplibre-gl@5.12.0/dist/maplibre-gl.css';">
            <style>
                /*
                 * Use the standard full-viewport sizing expected by MapLibre GL JS.
                 * The native AndroidView already sizes this WebView to the map box;
                 * a fixed child anchored to the WebView viewport then fills that box.
                 */
                html, body {
                    width: 100%; height: 100%;
                    padding: 0; margin: 0; overflow: hidden;
                }
                body { font-family: Arial, sans-serif; }
                #map-shell {
                    position: fixed; left: 0; top: 0; right: 0; bottom: 0;
                    display: block; width: 100%; height: 100%;
                    padding: 0; margin: 0;
                    background: #e7e8e4; border-radius: 22px; overflow: hidden;
                }
                /* MapLibre requires its target container to have no child elements. */
                #map {
                    position: absolute; left: 0; top: 0; right: 0; bottom: 0;
                    display: block; width: 100%; height: 100%;
                    padding: 0; margin: 0; background: #e7e8e4; overflow: hidden;
                }
                .map-error {
                    position: absolute; inset: 0; z-index: 20;
                    display: flex; padding: 24px; box-sizing: border-box;
                    align-items: center; justify-content: center;
                    text-align: center; color: #2d332d; background: #f2f1eb;
                    font-size: 14px; line-height: 1.5;
                }
                .apiary-marker { position: relative; width: 36px; height: 42px; cursor: pointer; }
                .apiary-ping {
                    position: absolute; left: 5px; top: 2px; width: 26px; height: 26px;
                    border: 2px solid #d97706; border-radius: 50%; box-sizing: border-box;
                    animation: apiaryPing 1.8s ease-out infinite; pointer-events: none;
                }
                .apiary-pin {
                    position: absolute; left: 7px; top: 4px; width: 22px; height: 22px;
                    border-radius: 50% 50% 50% 0; transform: rotate(-45deg);
                    background: #f59e0b; border: 3px solid #fff;
                    box-shadow: 0 2px 7px rgba(0,0,0,.55); box-sizing: border-box;
                }
                .apiary-pin:after {
                    content: ''; position: absolute; width: 7px; height: 7px;
                    top: 4px; left: 4px; background: #fff; border-radius: 50%;
                }
                @keyframes apiaryPing {
                    0% { transform: scale(.65); opacity: .95; }
                    100% { transform: scale(1.9); opacity: 0; }
                }
                .apiary-popup-title { font-weight: 700; margin-bottom: 4px; }
                .apiary-popup-coords { color: #60645e; font-size: 12px; }
                .maplibregl-ctrl-attrib { font-size: 10px !important; }
            </style>
        </head>
        <body>
            <div id="map-shell">
                <div id="map"></div>
                <div id="map-error" class="map-error" role="status">
                    Connecting to OpenFreeMap…
                </div>
            </div>
            <script>
                (function () {
                    const apiaries = $markers;
                    const focusedId = $focusIdJson;
                    const errorPanel = document.getElementById('map-error');
                    let mapReady = false;
                    let tileErrors = 0;
                    window.beekeepPendingLocation = null;

                    // This entry point is available even while MapLibre's library/style is
                    // still loading. The camera move is applied as soon as the map is ready.
                    window.beekeepQueueLocation = function (latitude, longitude) {
                        const lat = Number(latitude);
                        const lon = Number(longitude);
                        if (!Number.isFinite(lat) || !Number.isFinite(lon) ||
                            lat < -85.051129 || lat > 85.051129 || lon < -180 || lon > 180) return;
                        window.beekeepPendingLocation = { lat: lat, lon: lon };
                        if (typeof window.beekeepApplyCurrentLocation === 'function') {
                            window.beekeepApplyCurrentLocation(window.beekeepPendingLocation, true);
                        }
                    };
                    window.beekeepClearCurrentLocation = function () {
                        window.beekeepPendingLocation = null;
                        if (typeof window.beekeepRemoveCurrentLocation === 'function') {
                            window.beekeepRemoveCurrentLocation();
                        }
                    };

                    function showMapError(message) {
                        errorPanel.textContent = message;
                        errorPanel.style.display = 'flex';
                    }

                    function initializeMap() {
                        if (!window.maplibregl) {
                            showMapError('The map controls could not be loaded. Check your internet connection and reopen this map.');
                            return;
                        }
                        if (typeof window.maplibregl.supported === 'function' && !window.maplibregl.supported()) {
                            showMapError('This device does not support the graphics features needed for the map. Please update Android System WebView and try again.');
                            return;
                        }

                        try {
                            const map = new maplibregl.Map({
                                container: 'map',
                                style: 'https://tiles.openfreemap.org/styles/liberty',
                                center: [$centerLon, $centerLat],
                                zoom: $initialZoom,
                                attributionControl: true,
                                fadeDuration: 0,
                                // Let MapLibre use the device pixel ratio and its default
                                // 4096px canvas safety limit; a smaller fixed backing canvas
                                // can leave part of the viewport unrendered on some devices.
                                maxCanvasSize: [4096, 4096],
                                trackResize: true
                            });
                            // Keep apiary coordinates in a single GeoJSON source. Pins are
                            // rendered by MapLibre's WebGL layers rather than separate DOM
                            // elements, preventing marker/canvas drift during zoom animations.
                            const points = [];
                            const apiaryFeatures = apiaries.map(function (apiary) {
                                const longitude = Number(apiary.lon);
                                const latitude = Number(apiary.lat);
                                if (!Number.isFinite(longitude) || !Number.isFinite(latitude) ||
                                    longitude < -180 || longitude > 180 ||
                                    latitude < -85.051129 || latitude > 85.051129) return null;
                                const point = [longitude, latitude];
                                points.push(point);
                                return {
                                    type: 'Feature',
                                    properties: {
                                        id: String(apiary.id),
                                        name: String(apiary.name || '')
                                    },
                                    geometry: {
                                        type: 'Point',
                                        coordinates: point
                                    }
                                };
                            }).filter(Boolean);

                            function createApiaryPopup(name, longitude, latitude) {
                                const popupContent = document.createElement('div');
                                const title = document.createElement('div');
                                title.className = 'apiary-popup-title';
                                title.textContent = name;
                                const coordinates = document.createElement('div');
                                coordinates.className = 'apiary-popup-coords';
                                coordinates.textContent = latitude.toFixed(5) + ', ' + longitude.toFixed(5);
                                popupContent.appendChild(title);
                                popupContent.appendChild(coordinates);
                                return popupContent;
                            }

                            map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right');

                            function resizeMapViewport() {
                                // Wait for Android/Compose, CSS layout, and the WebGL canvas
                                // to settle, then remeasure the entire available map viewport.
                                window.requestAnimationFrame(function () {
                                    const container = map.getContainer();
                                    // Let MapLibre size its internal canvas from the measured
                                    // container. Manually setting its wrapper can prevent style
                                    // reloads and responsive sizing from recovering correctly.
                                    if (container && container.clientWidth > 0 && container.clientHeight > 0) {
                                        map.resize();
                                    }
                                    window.requestAnimationFrame(function () {
                                        map.resize();
                                    });
                                });
                            }
                            window.beekeepResizeMap = resizeMapViewport;

                            function applyCurrentLocation(location, animate) {
                                if (!location) return;
                                const longitude = Number(location.lon);
                                const latitude = Number(location.lat);
                                if (!Number.isFinite(longitude) || !Number.isFinite(latitude) ||
                                    longitude < -180 || longitude > 180 ||
                                    latitude < -85.051129 || latitude > 85.051129) return;

                                const userPoint = [longitude, latitude];
                                const locationData = {
                                    type: 'Feature',
                                    properties: {},
                                    geometry: { type: 'Point', coordinates: userPoint }
                                };
                                const locationSource = map.getSource('beekeep-current-location');
                                if (locationSource) {
                                    locationSource.setData(locationData);
                                } else {
                                    map.addSource('beekeep-current-location', {
                                        type: 'geojson',
                                        data: locationData
                                    });
                                    map.addLayer({
                                        id: 'beekeep-current-location-halo',
                                        type: 'circle',
                                        source: 'beekeep-current-location',
                                        paint: {
                                            'circle-radius': 15,
                                            'circle-color': '#2563EB',
                                            'circle-opacity': 0.20
                                        }
                                    });
                                    map.addLayer({
                                        id: 'beekeep-current-location-dot',
                                        type: 'circle',
                                        source: 'beekeep-current-location',
                                        paint: {
                                            'circle-radius': 7,
                                            'circle-color': '#2563EB',
                                            'circle-stroke-color': '#FFFFFF',
                                            'circle-stroke-width': 2.5
                                        }
                                    });
                                }

                                window.beekeepPendingLocation = { lat: latitude, lon: longitude };
                                if (animate) {
                                    map.flyTo({ center: userPoint, zoom: 15, duration: 650 });
                                } else {
                                    map.jumpTo({ center: userPoint, zoom: 15 });
                                }
                            }
                            window.beekeepApplyCurrentLocation = function (location, animate) {
                                if (!mapReady) {
                                    window.beekeepPendingLocation = location;
                                    return;
                                }
                                applyCurrentLocation(location, animate);
                            };
                            window.beekeepRemoveCurrentLocation = function () {
                                if (!mapReady) return;
                                if (map.getLayer('beekeep-current-location-dot')) {
                                    map.removeLayer('beekeep-current-location-dot');
                                }
                                if (map.getLayer('beekeep-current-location-halo')) {
                                    map.removeLayer('beekeep-current-location-halo');
                                }
                                if (map.getSource('beekeep-current-location')) {
                                    map.removeSource('beekeep-current-location');
                                }
                            };

                            if (window.ResizeObserver) {
                                const resizeObserver = new ResizeObserver(resizeMapViewport);
                                resizeObserver.observe(document.getElementById('map-shell'));
                                resizeObserver.observe(document.getElementById('map'));
                            }

                            map.on('load', function () {
                                mapReady = true;
                                errorPanel.style.display = 'none';
                                resizeMapViewport();
                                window.setTimeout(resizeMapViewport, 80);
                                window.setTimeout(resizeMapViewport, 250);
                                window.setTimeout(resizeMapViewport, 600);

                                // Draw pins in the map's own projection/render loop so they
                                // remain attached to their real geographic coordinates at all zooms.
                                const pinCanvas = document.createElement('canvas');
                                pinCanvas.width = 72;
                                pinCanvas.height = 84;
                                const ctx = pinCanvas.getContext('2d');
                                if (ctx) {
                                    ctx.lineJoin = 'round';
                                    ctx.lineCap = 'round';
                                    ctx.beginPath();
                                    ctx.moveTo(36, 82);
                                    ctx.bezierCurveTo(29, 70, 7, 48, 7, 30);
                                    ctx.arc(36, 30, 28, Math.PI, 0, false);
                                    ctx.bezierCurveTo(65, 48, 43, 70, 36, 82);
                                    ctx.closePath();
                                    ctx.fillStyle = '#F59E0B';
                                    ctx.fill();
                                    ctx.lineWidth = 5;
                                    ctx.strokeStyle = '#FFFFFF';
                                    ctx.stroke();
                                    ctx.beginPath();
                                    ctx.arc(36, 30, 8, 0, Math.PI * 2);
                                    ctx.fillStyle = '#FFFFFF';
                                    ctx.fill();
                                }
                                if (!map.hasImage('beekeep-apiary-pin')) {
                                    // MapLibre's addImage accepts ImageData (not a raw canvas).
                                    map.addImage(
                                        'beekeep-apiary-pin',
                                        ctx.getImageData(0, 0, pinCanvas.width, pinCanvas.height),
                                        { pixelRatio: 2 }
                                    );
                                }

                                map.addSource('beekeep-apiaries', {
                                    type: 'geojson',
                                    data: {
                                        type: 'FeatureCollection',
                                        features: apiaryFeatures
                                    }
                                });
                                map.addLayer({
                                    id: 'beekeep-apiary-halos',
                                    type: 'circle',
                                    source: 'beekeep-apiaries',
                                    paint: {
                                        'circle-radius': 15,
                                        'circle-color': '#F59E0B',
                                        'circle-opacity': 0.22
                                    }
                                });
                                map.addLayer({
                                    id: 'beekeep-apiary-pins',
                                    type: 'symbol',
                                    source: 'beekeep-apiaries',
                                    layout: {
                                        'icon-image': 'beekeep-apiary-pin',
                                        'icon-anchor': 'bottom',
                                        'icon-size': 1,
                                        'icon-allow-overlap': true,
                                        'icon-ignore-placement': true
                                    }
                                });

                                map.on('click', 'beekeep-apiary-pins', function (event) {
                                    const feature = event.features && event.features[0];
                                    if (!feature || !feature.geometry || feature.geometry.type !== 'Point') return;
                                    const coordinates = feature.geometry.coordinates.slice();
                                    const properties = feature.properties || {};
                                    const longitude = Number(coordinates[0]);
                                    const latitude = Number(coordinates[1]);
                                    new maplibregl.Popup({ offset: 36 })
                                        .setLngLat(coordinates)
                                        .setDOMContent(createApiaryPopup(
                                            String(properties.name || 'Apiary'),
                                            longitude,
                                            latitude
                                        ))
                                        .addTo(map);
                                });
                                map.on('mouseenter', 'beekeep-apiary-pins', function () {
                                    map.getCanvas().style.cursor = 'pointer';
                                });
                                map.on('mouseleave', 'beekeep-apiary-pins', function () {
                                    map.getCanvas().style.cursor = '';
                                });

                                const focused = focusedId === null ? null : apiaries.find(function (a) {
                                    return String(a.id) === String(focusedId);
                                });
                                if (window.beekeepPendingLocation) {
                                    // A GPS request can arrive before the style finishes loading;
                                    // consume the queued position now and move the camera last.
                                    applyCurrentLocation(window.beekeepPendingLocation, false);
                                } else if (focused && Number.isFinite(Number(focused.lon)) && Number.isFinite(Number(focused.lat))) {
                                    const focusedCoordinates = [Number(focused.lon), Number(focused.lat)];
                                    map.flyTo({
                                        center: focusedCoordinates,
                                        zoom: 17,
                                        duration: 450
                                    });
                                    new maplibregl.Popup({ offset: 36 })
                                        .setLngLat(focusedCoordinates)
                                        .setDOMContent(createApiaryPopup(
                                            String(focused.name || 'Apiary'),
                                            focusedCoordinates[0],
                                            focusedCoordinates[1]
                                        ))
                                        .addTo(map);
                                } else if (points.length === 1) {
                                    map.jumpTo({ center: points[0], zoom: 16 });
                                } else if (points.length > 1) {
                                    const bounds = new maplibregl.LngLatBounds();
                                    points.forEach(function (point) { bounds.extend(point); });
                                    map.fitBounds(bounds, { padding: 44, maxZoom: 15, duration: 400 });
                                }
                            });

                            map.on('error', function (event) {
                                const detail = event && event.error && event.error.message
                                    ? event.error.message : 'Unknown map loading error';
                                console.warn('BeeKeep OpenFreeMap error: ' + detail);
                                if (!mapReady) {
                                    tileErrors += 1;
                                    if (tileErrors >= 3) {
                                        showMapError('OpenFreeMap could not load its map style or tiles. Check your internet connection, then reopen the map. Details: ' + detail);
                                    }
                                }
                            });

                            window.setTimeout(function () {
                                if (!mapReady) {
                                    showMapError('OpenFreeMap is taking too long to load. Check your internet connection and try again.');
                                }
                            }, 15000);
                            window.addEventListener('resize', resizeMapViewport);
                            window.addEventListener('orientationchange', function () {
                                window.setTimeout(resizeMapViewport, 100);
                                window.setTimeout(resizeMapViewport, 450);
                            });
                            window.setTimeout(resizeMapViewport, 100);
                            window.setTimeout(resizeMapViewport, 300);
                            window.setTimeout(resizeMapViewport, 800);
                        } catch (error) {
                            console.error('BeeKeep could not initialize the map', error);
                            showMapError('The map could not be started on this device. Update Android System WebView and try again.');
                        }
                    }

                    function loadMapLibrary(index) {
                        const sources = [
                            'https://unpkg.com/maplibre-gl@5.12.0/dist/maplibre-gl.js',
                            'https://cdn.jsdelivr.net/npm/maplibre-gl@5.12.0/dist/maplibre-gl.js'
                        ];
                        if (index >= sources.length) {
                            showMapError('The map library could not be downloaded. Check your internet connection and reopen this map.');
                            return;
                        }
                        const script = document.createElement('script');
                        script.src = sources[index];
                        script.onload = function () {
                            if (window.maplibregl) initializeMap();
                            else loadMapLibrary(index + 1);
                        };
                        script.onerror = function () { loadMapLibrary(index + 1); };
                        document.head.appendChild(script);
                    }

                    loadMapLibrary(0);
                })();
            </script>
        </body>
        </html>
    """.trimIndent()
}
