package app.organicmaps.wear.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import app.organicmaps.sdk.R as SdkR
import app.organicmaps.sdk.Framework
import app.organicmaps.wear.MapTileKey
import app.organicmaps.wear.MapTileStateHolder
import app.organicmaps.wear.Mercator
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.ParsedMapTile
import app.organicmaps.wear.MapFeaturePath
import app.organicmaps.wear.MapFeaturePoint
import app.organicmaps.wear.LocalAmbientMode
import app.organicmaps.wear.WearApplication
import app.organicmaps.wear.WearCommandService
import app.organicmaps.wear.NavigationIcons
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import app.organicmaps.wear.presentation.search.PlacePage
import android.view.KeyCharacterMap
import android.content.pm.PackageManager
import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.sp

import androidx.lifecycle.viewmodel.compose.viewModel
import app.organicmaps.wear.presentation.navigation.SensorViewModel

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import app.organicmaps.wear.SearchResultItem
import app.organicmaps.wear.presentation.search.ModeSelectionScreen
import app.organicmaps.sdk.bookmarks.data.MapObject
import app.organicmaps.sdk.bookmarks.data.Metadata
import app.organicmaps.sdk.Router
import app.organicmaps.sdk.routing.RoutingController
import app.organicmaps.sdk.routing.RoutingOptions
import app.organicmaps.sdk.settings.RoadType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope

import androidx.wear.compose.material.dialog.Dialog

@Composable
fun MapPanel(modifier: Modifier = Modifier, mainViewModel: MainViewModel = viewModel(), isVisible: Boolean = true) {
    val context = LocalContext.current
    val isAmbient = LocalAmbientMode.current
    val app = context.applicationContext as WearApplication
    val navState by NavigationStateHolder.state.collectAsState()
    val isSystemDark = isSystemInDarkTheme()
    val isDark = remember(navState.mapStyle, isSystemDark) {
        when (navState.mapStyle) {
            "night" -> true
            "default" -> false
            else -> isSystemDark
        }
    }
    val streamedTile by MapTileStateHolder.mapTile.collectAsState()
    val allTiles by MapTileStateHolder.cachedTilesFlow.collectAsState()
    val sensorViewModel: SensorViewModel = viewModel()
    val compassHeading by sensorViewModel.heading.collectAsState()
    val focusRequester = remember { FocusRequester() }
    
    // CRITICAL: Request focus for hardware buttons
    LaunchedEffect(Unit) {
        if (isVisible) focusRequester.requestFocus()
    }
    
    var showQuickMenu by remember { mutableStateOf(false) }
    var tappedDestination by remember { mutableStateOf<SearchResultItem?>(null) }

    val effectiveLat = if (navState.isMapUnlocked) navState.manualCenterLat else navState.lat
    val effectiveLon = if (navState.isMapUnlocked) navState.manualCenterLon else navState.lon
    
    // PERSISTENT VALID LOCATION to prevent flickering on 0.0 jumps
    var lastValidLat by remember { mutableStateOf(if (navState.lat != 0.0) navState.lat else 0.0) }
    var lastValidLon by remember { mutableStateOf(if (navState.lon != 0.0) navState.lon else 0.0) }
    
    LaunchedEffect(navState.lat, navState.lon) {
        if (navState.lat != 0.0) {
            lastValidLat = navState.lat
            lastValidLon = navState.lon
        }
    }
    
    val currentLat = if (navState.isMapUnlocked) effectiveLat else lastValidLat
    val currentLon = if (navState.isMapUnlocked) effectiveLon else lastValidLon

    val requestKeys = remember { mutableMapOf<Long, MapTileKey>() }

    // Interaction is now allowed whenever NOT actively building a route
    val canInteract = !navState.isRouteBuilding
    
    // Auto-disable unlock mode if navigation starts in companion mode and we want to follow
    // But allow user to re-enable it manually.
    LaunchedEffect(navState.isActive, navState.standaloneMode) {
        if (navState.isActive && !navState.standaloneMode && navState.isMapUnlocked && navState.lastSettingsInteractionTime < System.currentTimeMillis() - 5000) {
            // Only auto-disable if no recent user interaction
            // NavigationStateHolder.update(navState.copy(isMapUnlocked = false))
        }
    }

    // IMPROVED DYNAMIC AUTO-ZOOM (Matches df::CalculateZoomBySpeed)
    val targetViewSpan = remember(navState.speedMps, navState.routerType, navState.isActive, navState.isMapUnlocked, navState.manualViewSpan) {
        if (navState.isMapUnlocked) return@remember navState.manualViewSpan.toDouble()
        
        // Base scales in meters per pixel (from native core df::CalculateZoomBySpeed)
        // Pedestrian: ~0.7 m/px (Zoom 18-19)
        // Vehicle at low speed: ~0.7 m/px
        // Vehicle at 100km/h: ~4.0 m/px (Zoom 14-15)
        
        val vs = 1.0 // Visual scale simplification
        val speedKmpH = if (navState.speedMps >= 0) navState.speedMps * 3.6 else 0.0
        
        val scales2d = listOf(
            20.0 to 0.70,
            40.0 to 1.25,
            60.0 to 2.25,
            75.0 to 3.00,
            85.0 to 3.75,
            95.0 to 6.00
        )
        
        val baseScale = when {
            navState.routerType == 1 -> 0.70 // Pedestrian always close
            speedKmpH <= scales2d.first().first -> scales2d.first().second
            speedKmpH >= scales2d.last().first -> scales2d.last().second
            else -> {
                var idx = 1
                while (idx < scales2d.size && scales2d[idx].first < speedKmpH) idx++
                val s1 = scales2d[idx-1]
                val s2 = scales2d[idx]
                val k = (speedKmpH - s1.first) / (s2.first - s1.first)
                s1.second + k * (s2.second - s1.second)
            }
        } / vs
        
        // Convert meters-per-pixel to viewSpan (degrees)
        // ~111,000 meters per degree lat
        // 1000 pixels (watch size ref) * baseScale = total meters across screen
        val targetSpan = (1000.0 * baseScale) / 111000.0
        targetSpan
    }
    
    val viewSpan by animateFloatAsState(
        targetValue = targetViewSpan.toFloat(),
        animationSpec = if (navState.isMapUnlocked) tween(80) else tween(durationMillis = 2500, easing = FastOutSlowInEasing),
        label = "zoom"
    )

    // Fix: Clamp viewSpan to avoid 0.0 or negative values in calculations
    val clampedViewSpan = viewSpan.coerceAtLeast(0.0001f)

    // CORRECTED SCALE: min span 0.0001 for extreme urban detail (benches, fountains)
    val currentScale = remember(clampedViewSpan) {
        (log2(360.0 / (clampedViewSpan * 2.0)).toInt() + 1).coerceIn(1, 19)
    }

    var isUsingGpsBearing by remember { mutableStateOf(false) }

    // SAFE STABILIZED ROTATION with Speed-based Hysteresis
    LaunchedEffect(navState.bearing, navState.speedMps, compassHeading, navState.isActive, navState.isMapUnlocked) {
        if (navState.speedMps > 2.0f) {
            isUsingGpsBearing = true
        } else if (navState.speedMps < 0.8f || navState.bearing < 0f) {
            isUsingGpsBearing = false
        }
        val targetDeg = when {
            navState.isMapUnlocked -> 0f
            navState.isActive && isUsingGpsBearing && navState.bearing >= 0f -> -navState.bearing
            else -> -compassHeading
        }
        
        var diff = targetDeg - sensorViewModel.mapRotationAnimatable.value
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        
        sensorViewModel.mapRotationAnimatable.animateTo(
            targetValue = sensorViewModel.mapRotationAnimatable.value + diff,
            animationSpec = spring(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioNoBouncy)
        )
    }

    val verticalOffsetFractionTarget = if (navState.routerType == 0 && navState.isActive && !navState.isMapUnlocked) 0.25f else 0.0f
    val verticalOffsetFraction by animateFloatAsState(
        targetValue = verticalOffsetFractionTarget,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "offset"
    )

    val effectivelyStandalone = navState.isEffectivelyStandalone

    val useOfflineMaps = navState.watchLocalMode || !navState.isPhoneConnected || navState.standaloneMode

    var isMapDownloaded by remember { mutableStateOf(true) }
    LaunchedEffect(currentLat, currentLon, useOfflineMaps) {
        if (useOfflineMaps && app.isFullyInitialized) {
            isMapDownloaded = Framework.nativeIsDownloadedMapAtLocation(currentLat, currentLon)
        } else {
            isMapDownloaded = true
        }
    }

    // Hide POIs while navigating/planning as target is already set
    val effectivePoiMask = if (navState.isActive) 0 else navState.poiCategoriesMask

    val scope = rememberCoroutineScope()

    // PERFORMANCE: Load icons individually to avoid function call overhead in mapOf
    val iconFood = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_food) else null
    val iconCafe = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_cafe) else null
    val iconHotel = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_hotel) else null
    val iconMoney = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_money) else null
    val iconParking = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_parking) else null
    val iconMountain = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_mountain) else null
    val iconPark = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_park) else null
    val iconTransport = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_transport) else null
    val iconAirport = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_airport) else null
    val iconPharmacy = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_pharmacy) else null
    val iconShop = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_shop) else null
    val iconBank = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_bank) else null
    val iconGas = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_gas) else null
    val iconMedicine = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_medicine) else null
    val iconPub = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_pub) else null
    val iconBar = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_bar) else null
    val iconMuseum = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_museum) else null
    val iconTheatre = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_theatre) else null
    val iconEntertainment = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_entertainment) else null
    val iconViewpoint = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_viewpoint) else null
    val iconSights = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_sights) else null
    val iconBench = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_bench) else null
    val iconNone = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_none) else null
    val iconWater = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_water) else null
    val iconFastFood = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_fast_food) else null
    val iconAtm = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_money) else null
    val iconCinema = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_entertainment) else null
    val iconAttraction = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_sights) else null
    val iconReligious = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_christianity) else null
    val iconBicycleParking = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_bicycle_parking) else null
    val iconChargingStation = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_charging_station) else null
    val iconArt = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_art) else null
    val iconAnimals = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_animals) else null
    val iconSport = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_sport) else null
    val iconSwim = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_swim) else null
    val iconInformation = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_information) else null
    val iconBuilding = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_building) else null
    val iconFountain = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_fountain) else null
    val iconPicnicTable = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_picnic_table) else null
    val iconWasteBasket = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_waste_basket) else null
    val iconToilets = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_toilets) else null
    val iconDrinkingWater = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_drinking_water) else null
    val iconPostbox = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_postbox) else null
    val iconRecycling = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_recycling) else null
    val iconShelter = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_shelter) else null
    val iconPlayground = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_playground) else null
    val iconClinic = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_clinic) else null
    val iconHostel = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_hostel) else null
    val iconBakery = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_bakery) else null
    val iconSupermarket = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_supermarket) else null
    val iconConvenience = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_convenience) else null
    val iconCemetery = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_cemetery) else null
    val iconLighthouse = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_lighthouse) else null
    val iconVending = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_vending) else null
    val iconLaundry = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_laundry) else null
    val iconCarWash = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_car_wash) else null
    val iconFireHydrant = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_firehydrant) else null
    val iconHospital = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_hospital) else null
    val iconBus = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_bus) else null
    val iconTrain = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_train) else null
    val iconTram = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_tram) else null
    val iconSubway = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_subway) else null
    val iconTaxi = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_taxi) else null
    val iconGarden = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_garden) else null
    val iconSoccer = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_soccer) else null
    val iconBasketball = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_basketball) else null
    val iconTennis = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_tennis) else null
    val iconZoo = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_zoo) else null
    val iconBeach = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_beach) else null
    val iconBbq = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_bbq) else null
    val iconFireStation = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_fire_station) else null
    val iconDentist = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_dentist) else null
    val iconCollege = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_college) else null
    val iconStatue = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_statue) else null
    val iconMonument = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_monument) else null
    val iconThemePark = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_theme_park) else null

    val poiIcons = remember(isAmbient, iconFood, iconCafe, iconHotel, iconMoney, iconParking, iconMountain, iconPark, iconTransport, iconAirport, iconPharmacy, iconShop, iconBank, iconGas, iconMedicine, iconPub, iconBar, iconMuseum, iconTheatre, iconEntertainment, iconViewpoint, iconSights, iconBench, iconNone, iconWater, iconFastFood, iconAtm, iconCinema, iconAttraction, iconReligious, iconBicycleParking, iconChargingStation, iconArt, iconAnimals, iconSport, iconSwim, iconInformation, iconBuilding, iconFountain, iconPicnicTable, iconWasteBasket, iconToilets, iconDrinkingWater, iconPostbox, iconRecycling, iconShelter, iconPlayground, iconClinic, iconHostel, iconBakery, iconSupermarket, iconConvenience, iconCemetery, iconLighthouse, iconVending, iconLaundry, iconCarWash, iconFireHydrant, iconHospital, iconBus, iconTrain, iconTram, iconSubway, iconTaxi, iconGarden, iconSoccer, iconBasketball, iconTennis, iconZoo, iconBeach, iconBbq, iconFireStation, iconDentist, iconCollege, iconStatue, iconMonument, iconThemePark) {
        if (isAmbient) emptyMap()
        else mapOf(
            "food" to iconFood!!, "cafe" to iconCafe!!, "hotel" to iconHotel!!, "money" to iconMoney!!,
            "parking" to iconParking!!, "mountain" to iconMountain!!, "park" to iconPark!!, "transport" to iconTransport!!,
            "airport" to iconAirport!!, "pharmacy" to iconPharmacy!!, "shop" to iconShop!!, "bank" to iconBank!!,
            "gas" to iconGas!!, "medicine" to iconMedicine!!, "pub" to iconPub!!, "bar" to iconBar!!,
            "museum" to iconMuseum!!, "theatre" to iconTheatre!!, "entertainment" to iconEntertainment!!,
            "viewpoint" to iconViewpoint!!, "sights" to iconSights!!, "bench" to iconBench!!, "fountain" to iconFountain!!,
            "fast_food" to iconFastFood!!, "atm" to iconAtm!!, "cinema" to iconCinema!!, "attraction" to iconAttraction!!,
            "christianity" to iconReligious!!, "islam" to iconReligious!!, "judaism" to iconReligious!!, "buddhism" to iconReligious!!,
            "bicycle_parking" to iconBicycleParking!!, "charging_station" to iconChargingStation!!,
            "art" to iconArt!!, "animals" to iconAnimals!!, "sport" to iconSport!!, "swim" to iconSwim!!,
            "information" to iconInformation!!, "building" to iconBuilding!!, "none" to iconNone!!,
            "picnic_table" to iconPicnicTable!!, "waste_basket" to iconWasteBasket!!, "toilets" to iconToilets!!,
            "drinking_water" to iconDrinkingWater!!, "postbox" to iconPostbox!!, "recycling" to iconRecycling!!,
            "shelter" to iconShelter!!, "playground" to iconPlayground!!, "clinic" to iconClinic!!,
            "hostel" to iconHostel!!, "bakery" to iconBakery!!, "supermarket" to iconSupermarket!!, "convenience" to iconConvenience!!,
            "cemetery" to iconCemetery!!, "lighthouse" to iconLighthouse!!, "vending" to iconVending!!,
            "laundry" to iconLaundry!!, "car_wash" to iconCarWash!!, "fire_hydrant" to iconFireHydrant!!,
            "hospital" to iconHospital!!, "bus" to iconBus!!, "train" to iconTrain!!, "tram" to iconTram!!,
            "subway" to iconSubway!!, "taxi" to iconTaxi!!, "garden" to iconGarden!!, "soccer" to iconSoccer!!,
            "basketball" to iconBasketball!!, "tennis" to iconTennis!!, "zoo" to iconZoo!!, "beach" to iconBeach!!,
            "bbq" to iconBbq!!, "fire_station" to iconFireStation!!, "dentist" to iconDentist!!, "college" to iconCollege!!,
            "statue" to iconStatue!!, "monument" to iconMonument!!, "theme_park" to iconThemePark!!
        )
    }

    // Hardware button detection
    val isEmulator = remember {
        android.os.Build.FINGERPRINT.contains("generic") ||
        android.os.Build.MODEL.contains("google_sdk") ||
        android.os.Build.MODEL.contains("Emulator") ||
        android.os.Build.DEVICE.contains("generic") ||
        android.os.Build.HARDWARE.contains("goldfish") ||
        android.os.Build.HARDWARE.contains("ranchu") ||
        android.os.Build.PRODUCT.contains("sdk_gwear")
    }
    // Sync manual center when map is locked to avoid "jumps"
    LaunchedEffect(lastValidLat, lastValidLon, navState.isMapUnlocked) {
        if (!navState.isMapUnlocked && lastValidLat != 0.0) {
            NavigationStateHolder.update { it.copy(
                manualCenterLat = lastValidLat,
                manualCenterLon = lastValidLon
            ) }
        }
    }

    // Performance: Quantized viewSpan for requests to improve responsiveness
    var localRequestLat by remember { mutableStateOf(currentLat) }
    var localRequestLon by remember { mutableStateOf(currentLon) }
    var localRequestSpan by remember { mutableStateOf(clampedViewSpan) }

    LaunchedEffect(currentLat, currentLon, clampedViewSpan, verticalOffsetFraction) {
        delay(100)
        
        // Calculate the visual center of the screen in lat/lon
        // currentLat/Lon is where the user marker is (if VerticalOffset is active) or the map center
        // VerticalOffset shifts the map DOWN, so the "visual center" is slightly ABOVE the marker
        // We need to account for rotation as well for the loading center
        val screenCenterLat = if (!navState.isMapUnlocked && navState.isActive) {
            val rotationRad = Math.toRadians(sensorViewModel.mapRotationAnimatable.value.toDouble())
            currentLat + (verticalOffsetFraction * clampedViewSpan * 2.0 * cos(rotationRad))
        } else currentLat
        
        val screenCenterLon = if (!navState.isMapUnlocked && navState.isActive) {
            val rotationRad = Math.toRadians(sensorViewModel.mapRotationAnimatable.value.toDouble())
            currentLon - (verticalOffsetFraction * clampedViewSpan * 2.0 * sin(rotationRad))
        } else currentLon
        
        if (abs(screenCenterLat - localRequestLat) > clampedViewSpan * 0.3 || 
            abs(screenCenterLon - localRequestLon) > clampedViewSpan * 0.3 ||
            abs(clampedViewSpan - localRequestSpan) / localRequestSpan > 0.2) {
            localRequestLat = screenCenterLat
            localRequestLon = screenCenterLon
            localRequestSpan = clampedViewSpan
        }
    }

    // PERFORMANCE: Limited dispatcher for JNI calls to avoid lagginess during roaming
    val jniDispatcher = remember { Dispatchers.Default.limitedParallelism(4) }

    // SMART SPIRAL PRE-FETCHING LOGIC
    LaunchedEffect(localRequestLat, localRequestLon, localRequestSpan, useOfflineMaps, effectivePoiMask, navState.isMapUnlocked, currentScale) {
        val currentKey = MapTileKey(Mercator.lonToTileX(localRequestLon, 16), Mercator.latToTileY(localRequestLat, 16), currentScale)
        
        // Generate a 3x3 grid and sort by distance from center (Spiral Loading)
        val grid = mutableListOf<MapTileKey>()
        for (dx in -1..1) {
            for (dy in -1..1) {
                grid.add(MapTileKey(currentKey.x + dx, currentKey.y + dy, currentScale))
            }
        }
        
        // Distance-based sorting for spiral effect
        val sortedGrid = grid.sortedBy { key ->
            hypot((key.x - currentKey.x).toDouble(), (key.y - currentKey.y).toDouble())
        }

        sortedGrid.forEach { key ->
            if (MapTileStateHolder.getCachedTileByKey(key) == null) {
                launch(jniDispatcher) {
                    if (app.isFullyInitialized) {
                        val tileLeftLon = Mercator.tileXToLon(key.x, 16)
                        val tileTopLat = Mercator.tileYToLat(key.y, 16)
                        val tileRightLon = Mercator.tileXToLon(key.x + 1, 16)
                        val tileBottomLat = Mercator.tileYToLat(key.y + 1, 16)
                        
                        val localFeatures = Framework.nativeGetWearMapFeatures(
                            minOf(tileTopLat, tileBottomLat) - 0.001, tileLeftLon - 0.001,
                            maxOf(tileTopLat, tileBottomLat) + 0.001, tileRightLon + 0.001,
                            currentScale, navState.routerType, effectivePoiMask
                        )
                        if (localFeatures.isNotEmpty()) {
                            val parsed = MapTileStateHolder.parseTile(localFeatures, key, 1000f, 1000f)
                            withContext(Dispatchers.Main) {
                                MapTileStateHolder.updateCache(key, parsed)
                            }
                            return@launch
                        }
                    }
                    
                    if (!useOfflineMaps || navState.isPhoneConnected) {
                        val requestId = System.nanoTime()
                        requestKeys[requestId] = key
                        
                        val tileLeftLon = Mercator.tileXToLon(key.x, 16)
                        val tileTopLat = Mercator.tileYToLat(key.y, 16)
                        val tileRightLon = Mercator.tileXToLon(key.x + 1, 16)
                        val tileBottomLat = Mercator.tileYToLat(key.y + 1, 16)
                        
                        WearCommandService.requestMapTile(
                            context, requestId, 
                            minOf(tileTopLat, tileBottomLat), minOf(tileLeftLon, tileRightLon),
                            maxOf(tileTopLat, tileBottomLat), maxOf(tileLeftLon, tileRightLon),
                            currentScale, navState.routerType, effectivePoiMask
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(streamedTile) {
        val tile = streamedTile ?: return@LaunchedEffect
        val key = requestKeys[tile.requestId] ?: MapTileKey(Mercator.lonToTileX(currentLon, 16), Mercator.latToTileY(currentLat, 16), currentScale)
        
        scope.launch(Dispatchers.Default) {
            val parsed = MapTileStateHolder.parseTile(tile.features, key, 1000f, 1000f)
            withContext(Dispatchers.Main) {
                MapTileStateHolder.updateCache(key, parsed)
                requestKeys.remove(tile.requestId)
            }
        }
    }

    Box(
        modifier = Modifier
            .then(modifier)
            .fillMaxSize()
            .clipToBounds()
            .background(if (isAmbient) Color.Black else if (isDark) Color(0xFF1B1B1B) else Color(0xFFF1EEE8)) // Dynamic background
            .focusRequester(focusRequester)
            .onKeyEvent {
                if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_STEM_1) {
                    showQuickMenu = true
                    true
                } else if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_STEM_2) {
                    val current = NavigationStateHolder.state.value
                    NavigationStateHolder.update(current.copy(
                        isMapUnlocked = !current.isMapUnlocked,
                        manualCenterLat = if (!current.isMapUnlocked) currentLat else current.manualCenterLat,
                        manualCenterLon = if (!current.isMapUnlocked) currentLon else current.manualCenterLon,
                        manualViewSpan = viewSpan,
                        lastSettingsInteractionTime = System.currentTimeMillis()
                    ))
                    true
                } else false
            }
            .onRotaryScrollEvent {
                val currentState = NavigationStateHolder.state.value
                val factor = if (it.verticalScrollPixels > 0) 1.25f else 0.75f
                val currentSpan = if (currentState.isMapUnlocked) currentState.manualViewSpan else viewSpan
                val newSpan = (currentSpan * factor).coerceIn(0.0001f, 0.05f)
                
                NavigationStateHolder.update(currentState.copy(
                    isMapUnlocked = true,
                    isMapUnlockedBeforeNav = currentState.isMapUnlocked, // Remember previous state
                    manualViewSpan = newSpan,
                    manualCenterLat = if (currentState.manualCenterLat == 0.0) currentLat else currentState.manualCenterLat,
                    manualCenterLon = if (currentState.manualCenterLon == 0.0) currentLon else currentState.manualCenterLon,
                    lastSettingsInteractionTime = System.currentTimeMillis()
                ))
                true
            }
            .pointerInput(navState.isMapUnlocked, navState.isRouteBuilding) {
                // PAN AND ZOOM - ONLY ACTIVE WHEN UNLOCKED
                if (navState.isMapUnlocked && !navState.isRouteBuilding) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val currentState = NavigationStateHolder.state.value
                        val currentSpan = currentState.manualViewSpan
                        val newSpan = (currentSpan / zoom).coerceIn(0.0001f, 0.05f)
                        
                        // "Drag the paper" logic: drag down (+pan.y) -> move center North (+lat)
                        val latStep = (pan.y / size.height) * (currentSpan * 2)
                        val lonStep = -(pan.x / size.width) * (currentSpan * 2)
                        
                        NavigationStateHolder.update(currentState.copy(
                            manualViewSpan = newSpan,
                            manualCenterLat = currentState.manualCenterLat + latStep.toDouble(),
                            manualCenterLon = currentState.manualCenterLon + lonStep.toDouble(),
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        ))
                    }
                }
            }
            .pointerInput(Unit) {
                // TAPS AND LONG PRESS - ALWAYS ACTIVE
                detectTapGestures(
                    onLongPress = {
                        showQuickMenu = true
                    },
                    onTap = { offset ->
                        val currentState = NavigationStateHolder.state.value
                        // POI tapping now works in all modes as long as not actively building a route
                        if (!currentState.isRouteBuilding) {
                            val curViewSpan = if (currentState.isMapUnlocked) currentState.manualViewSpan else viewSpan
                            val cLat = if (currentState.isMapUnlocked) currentState.manualCenterLat else currentLat
                            val cLon = if (currentState.isMapUnlocked) currentState.manualCenterLon else currentLon

                            val offsetValPx = verticalOffsetFraction * size.height
                            val rotationRad = Math.toRadians(sensorViewModel.mapRotationAnimatable.value.toDouble())
                            val cosR = cos(rotationRad).toFloat()
                            val sinR = sin(rotationRad).toFloat()

                            // Offset relative to screen center
                            val relX = offset.x - size.width / 2
                            val relY = offset.y - size.height / 2

                            // Rotate back (un-rotate map rotation)
                            val unRotX = relX * cosR + relY * sinR
                            val unRotY = -relX * sinR + relY * cosR

                            val dx = unRotX / size.width * (curViewSpan * 2)
                            val dy = (unRotY - offsetValPx) / size.height * (curViewSpan * 2)
                            
                            val tappedLat = cLat - dy
                            val tappedLon = cLon + dx
                            
                            val density = context.resources.displayMetrics.density
                            val tapRadiusPx = 16f * density
                            var nearestPoi: MapFeaturePoint? = null
                            var minDistPx = tapRadiusPx
                            val curSpanVal = (abs(Mercator.latToY(cLat) - Mercator.latToY(cLat + curViewSpan)) * 2.0).coerceAtLeast(1e-9)

                            MapTileStateHolder.getAllCachedTiles().forEach { tile ->
                                val tx = ((Mercator.lonToX(tappedLon) - (tile.mercatorX - tile.mercatorSpan / 2.0)) / tile.mercatorSpan * 1000.0).toFloat()
                                val ty = ((Mercator.latToY(tappedLat) - (tile.mercatorY - tile.mercatorSpan / 2.0)) / tile.mercatorSpan * 1000.0).toFloat()
                                tile.pointsByType.values.flatten().forEach { poi ->
                                    val dist = hypot(poi.point.x - tx, poi.point.y - ty)
                                    val screenDist = dist * (tile.mercatorSpan / curSpanVal * size.height / 1000f)
                                    if (screenDist < minDistPx) {
                                        // PRIORITIZE features with names
                                        if (nearestPoi == null || (poi.name.isNotEmpty() && nearestPoi!!.name.isEmpty())) {
                                            minDistPx = screenDist.toFloat()
                                            nearestPoi = poi
                                        } else if (poi.name.isEmpty() == nearestPoi!!.name.isEmpty() && screenDist < minDistPx) {
                                            minDistPx = screenDist.toFloat()
                                            nearestPoi = poi
                                        }
                                    }
                                }
                            }

                            val resultItem = if (nearestPoi != null) {
                                val mapObject = if (app.isFullyInitialized) {
                                    Framework.nativeGetMapObjectForLocation(tappedLat, tappedLon)
                                } else null
                                SearchResultItem(
                                    name = mapObject?.title ?: nearestPoi!!.name,
                                    description = if (mapObject?.subtitle?.isNotEmpty() == true) mapObject.subtitle else "POI",
                                    lat = tappedLat,
                                    lon = tappedLon,
                                    type = 2,
                                    openingHours = mapObject?.getMetadata(Metadata.MetadataType.FMD_OPEN_HOURS) ?: "",
                                    website = mapObject?.getMetadata(Metadata.MetadataType.FMD_WEBSITE) ?: "",
                                    phone = mapObject?.getMetadata(Metadata.MetadataType.FMD_PHONE_NUMBER) ?: "",
                                    address = mapObject?.address ?: "",
                                    cuisine = mapObject?.getMetadata(Metadata.MetadataType.FMD_CUISINE) ?: "",
                                    operator = mapObject?.getMetadata(Metadata.MetadataType.FMD_OPERATOR) ?: "",
                                    brand = mapObject?.getMetadata(Metadata.MetadataType.FMD_BRAND) ?: "",
                                    stars = mapObject?.getMetadata(Metadata.MetadataType.FMD_STARS) ?: ""
                                )
                            } else {
                                // Try native search even if no POI icon was near
                                val mapObject = if (app.isFullyInitialized) {
                                    Framework.nativeGetMapObjectForLocation(tappedLat, tappedLon)
                                } else null

                                if (mapObject != null) {
                                    SearchResultItem(
                                        name = mapObject.title,
                                        description = if (mapObject.subtitle.isNotEmpty()) mapObject.subtitle else "Dropped Pin",
                                        lat = tappedLat,
                                        lon = tappedLon,
                                        type = 2,
                                        openingHours = mapObject.getMetadata(Metadata.MetadataType.FMD_OPEN_HOURS),
                                        website = mapObject.getMetadata(Metadata.MetadataType.FMD_WEBSITE),
                                        phone = mapObject.getMetadata(Metadata.MetadataType.FMD_PHONE_NUMBER),
                                        address = mapObject.address,
                                        cuisine = mapObject.getMetadata(Metadata.MetadataType.FMD_CUISINE),
                                        operator = mapObject.getMetadata(Metadata.MetadataType.FMD_OPERATOR),
                                        brand = mapObject.getMetadata(Metadata.MetadataType.FMD_BRAND),
                                        stars = mapObject.getMetadata(Metadata.MetadataType.FMD_STARS)
                                    )
                                } else {
                                    SearchResultItem(
                                        name = "Dropped Pin",
                                        description = "",
                                        lat = tappedLat,
                                        lon = tappedLon,
                                        type = 2
                                    )
                                }
                            }
                            tappedDestination = resultItem
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        val currentTiles = allTiles
        
        // PERSISTENT ROUTE state with optimization
        val routeToDraw by remember {
            derivedStateOf {
                if (navState.isActive && navState.routePoints.isNotEmpty()) {
                    navState.routePoints
                } else {
                    emptyList()
                }
            }
        }

        val curX = Mercator.lonToX(currentLon)
        val curY = Mercator.latToY(currentLat)
        val topY = Mercator.latToY(currentLat + clampedViewSpan)
        val curSpan = (abs(curY - topY) * 2.0).coerceAtLeast(1e-9)

        // PERFORMANCE: Filter visible tiles in background and memoize
        val visibleTiles by remember(allTiles, curX, curY, curSpan) {
            derivedStateOf {
                val threshold = (curSpan + (1.0 / (1 shl 16))) * 1.5
                allTiles.filter { 
                    abs(it.mercatorX - curX) < threshold && abs(it.mercatorY - curY) < threshold 
                }
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()
        ) {
            val offsetValPx = verticalOffsetFraction * size.height
            
            // UNIFIED MERCATOR COORDINATE SYSTEM
            withTransform({
                translate(top = offsetValPx)
                rotate(sensorViewModel.mapRotationAnimatable.value, pivot = Offset(size.width / 2, size.height / 2))
            }) {
                // GLOBAL TYPE-CENTRIC RENDERING (The Professional Way)
                // Pass 1: Background features
                val bgOrder = listOf(9, 3, 2) // Green, Water, Buildings
                bgOrder.forEach { type ->
                    visibleTiles.forEach { tile ->
                        DrawPassInternal(tile, curX, curY, curSpan, 1, isAmbient, clampedViewSpan, isDark, this, emptyMap(), type)
                    }
                }
                
                // Pass 2: Road Outlines (Ordered lowest to highest)
                val roadOrder = listOf(1, 8, 7, 6, 5, 4)
                roadOrder.forEach { type ->
                    visibleTiles.forEach { tile ->
                        DrawPassInternal(tile, curX, curY, curSpan, 2, isAmbient, clampedViewSpan, isDark, this, emptyMap(), type)
                    }
                }
                
                // Pass 3: Road Fills (Ordered lowest to highest)
                roadOrder.forEach { type ->
                    visibleTiles.forEach { tile ->
                        DrawPassInternal(tile, curX, curY, curSpan, 3, isAmbient, clampedViewSpan, isDark, this, emptyMap(), type)
                    }
                }
                
                // Pass 4: Labels & POIs
                visibleTiles.forEach { tile ->
                    DrawPassInternal(tile, curX, curY, curSpan, 4, isAmbient, clampedViewSpan, isDark, this, poiIcons)
                }

                // DRAW ROUTE
                if (routeToDraw.isNotEmpty()) {
                    withTransform({
                        translate(size.width / 2, size.height / 2)
                        // Scale 1:1 with screen pixels for route drawing
                    }) {
                        val routePath = Path()
                        routeToDraw.forEachIndexed { i, (lat, lon) ->
                            val rx = ((Mercator.lonToX(lon) - curX) / curSpan * size.height).toFloat()
                            val ry = ((Mercator.latToY(lat) - curY) / curSpan * size.height).toFloat()
                            if (i == 0) routePath.moveTo(rx, ry)
                            else routePath.lineTo(rx, ry)
                        }
                        
                        drawPath(
                            path = routePath, 
                            color = Color(0x33000000),
                            style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                        drawPath(
                            path = routePath, 
                            color = if (isAmbient) Color.White else Color(0xFF249CF2),
                            style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                        
                        // DRAW MORPHED TURN ARROW (Overlay on route)
                        if (!isAmbient && navState.isActive && navState.turnLat != 0.0) {
                            if (routeToDraw.size >= 2) {
                                var bestTurnIdx = 0
                                var minDist = Double.MAX_VALUE
                                for (i in routeToDraw.indices) {
                                    val d = hypot(
                                        Mercator.lonToX(routeToDraw[i].second) - Mercator.lonToX(navState.turnLon),
                                        Mercator.latToY(routeToDraw[i].first) - Mercator.latToY(navState.turnLat)
                                    )
                                    if (d < minDist) {
                                        minDist = d
                                        bestTurnIdx = i
                                    }
                                }

                                // Extract segment around the turn for morphing (e.g., +/- 5 points)
                                val startIdx = (bestTurnIdx - 5).coerceAtLeast(0)
                                val endIdx = (bestTurnIdx + 5).coerceAtMost(routeToDraw.size - 1)
                                val segment = routeToDraw.subList(startIdx, endIdx + 1)
                                
                                if (segment.size >= 2) {
                                    val turnPath = Path()
                                    segment.forEachIndexed { i, (lat, lon) ->
                                        val rx = ((Mercator.lonToX(lon) - curX) / curSpan * size.height).toFloat()
                                        val ry = ((Mercator.latToY(lat) - curY) / curSpan * size.height).toFloat()
                                        if (i == 0) turnPath.moveTo(rx, ry)
                                        else turnPath.lineTo(rx, ry)
                                    }
                                    
                                    val turnColor = Color(0xFFFFC30A) // Organic Maps Yellow
                                    
                                    // Draw the thick arrow body
                                    drawPath(
                                        path = turnPath,
                                        color = Color.White,
                                        style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                    )
                                    drawPath(
                                        path = turnPath,
                                        color = turnColor, 
                                        style = Stroke(width = 10.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                    )
                                    
                                    // Draw the LARGE arrowhead at the end of the segment
                                    val lastP = segment.last()
                                    val prevP = segment[segment.size - 2]
                                    val lx = ((Mercator.lonToX(lastP.second) - curX) / curSpan * size.height).toFloat()
                                    val ly = ((Mercator.latToY(lastP.first) - curY) / curSpan * size.height).toFloat()
                                    val px = ((Mercator.lonToX(prevP.second) - curX) / curSpan * size.height).toFloat()
                                    val py = ((Mercator.latToY(prevP.first) - curY) / curSpan * size.height).toFloat()
                                    
                                    val angle = atan2((ly - py).toDouble(), (lx - px).toDouble()).toFloat()
                                    withTransform({
                                        translate(lx, ly)
                                        rotate(Math.toDegrees(angle.toDouble()).toFloat() + 90f)
                                    }) {
                                        val tipPath = Path().apply {
                                            moveTo(0f, -16.dp.toPx())
                                            lineTo(-13.dp.toPx(), 10.dp.toPx())
                                            lineTo(13.dp.toPx(), 10.dp.toPx())
                                            close()
                                        }
                                        drawPath(tipPath, Color.White, style = Stroke(width = 4.dp.toPx(), join = StrokeJoin.Round))
                                        drawPath(tipPath, turnColor)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Navigation Instructions Overlay
        if (navState.isActive && navState.distToTurn.isNotEmpty() && !navState.isMapUnlocked) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 35.dp) // More inward for round watches
                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(30.dp)) {
                        Icon(
                            painter = painterResource(id = NavigationIcons.getTurnIcon(navState.carDirection, navState.pedestrianDirection, navState.exitNum)),
                            contentDescription = null, modifier = Modifier.fillMaxSize(), tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = navState.distToTurn, style = MaterialTheme.typography.title3.copy(fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = Color.White)
                        if (navState.nextStreet.isNotEmpty()) {
                            Text(text = navState.nextStreet, style = MaterialTheme.typography.caption2.copy(fontSize = 10.sp), color = Color.White.copy(alpha = 0.9f), maxLines = 1)
                        }
                    }
                }
            }
        }

        // USER MARKER (Unified 3D-style Arrow)
        Canvas(modifier = Modifier.fillMaxSize()
        ) {
            val offsetValPx = verticalOffsetFraction * size.height
            
            // Map parameters
            val curX = Mercator.lonToX(currentLon)
            val curY = Mercator.latToY(currentLat)
            val topY = Mercator.latToY(currentLat + clampedViewSpan)
            val curSpan = (abs(curY - topY) * 2.0).coerceAtLeast(1e-9)

            val userScreenX: Float
            val userScreenY: Float

            if (navState.isMapUnlocked) {
                // Project user location relative to the map's current center
                val rawDx = ((Mercator.lonToX(lastValidLon) - curX) / curSpan * size.height).toFloat()
                val rawDy = ((Mercator.latToY(lastValidLat) - curY) / curSpan * size.height).toFloat()

                // Apply map rotation and vertical offset to the relative coordinates
                val rotationRad = Math.toRadians(sensorViewModel.mapRotationAnimatable.value.toDouble())
                val cosR = cos(rotationRad).toFloat()
                val sinR = sin(rotationRad).toFloat()

                // Rotate the (dx, dy) vector
                val rotatedDx = rawDx * cosR - rawDy * sinR
                val rotatedDy = rawDx * sinR + rawDy * cosR

                userScreenX = size.width / 2 + rotatedDx
                userScreenY = size.height / 2 + offsetValPx + rotatedDy
            } else {
                userScreenX = size.width / 2
                userScreenY = size.height / 2 + offsetValPx
            }

            // USER MARKER (Unified 3D-style Arrow)
            if (userScreenX in -100f..(size.width + 100f) && userScreenY in -100f..(size.height + 100f)) {
                val arrowBlue = if (isDark) Color(0xFF1E88E5) else Color(0xFF249CF2) 
                
                withTransform({
                    translate(userScreenX, userScreenY)
                    // If exploring, arrow shows actual compass. If navigating, it shows relative rotation.
                    rotate(if (navState.isMapUnlocked) compassHeading + sensorViewModel.mapRotationAnimatable.value else 0f)
                }) {
                    val arrowPath = Path().apply {
                        moveTo(0f, -14.dp.toPx())
                        lineTo(-8.5.dp.toPx(), 4.dp.toPx())
                        lineTo(0f, 0.dp.toPx()) // Inner notch for 3D look
                        lineTo(8.5.dp.toPx(), 4.dp.toPx())
                        close()
                    }
                    drawPath(arrowPath, Color.White, style = Stroke(width = 2.8.dp.toPx(), join = StrokeJoin.Round))
                    drawPath(arrowPath, arrowBlue)
                }
            }
        }

        if (!isMapDownloaded && useOfflineMaps) {
            // ... (no change to this box)
        }

        if (navState.isMapUnlocked) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (navState.isRouteBuilt && !navState.isNavigating) 60.dp else 24.dp)
            ) {
                androidx.wear.compose.material.CompactChip(
                    onClick = {
                        NavigationStateHolder.update { it.copy(isMapUnlocked = false, lastSettingsInteractionTime = System.currentTimeMillis()) }
                    },
                    label = { Text("Recenter") },
                    icon = { Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
        }

        // NO FIX INDICATOR
        if (effectivelyStandalone && navState.lat == 0.0 && !isAmbient) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Searching for GPS...", style = MaterialTheme.typography.caption3, color = Color.White)
                }
            }
        }
        
        // STANDALONE MODE INDICATOR is now handled in Omaps.kt (Global overlay)
        
        // STANDALONE START BUTTON
        if (navState.isRouteBuilt && !navState.isNavigating) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (navState.isMapUnlocked) 60.dp else 24.dp)
            ) {
                androidx.wear.compose.material.Button(
                    onClick = {
                        RoutingController.get().start()
                    },
                    modifier = Modifier.height(40.dp).fillMaxWidth(0.5f),
                    colors = ButtonDefaults.primaryButtonColors()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Start")
                    }
                }
            }
        }
        
        if (showQuickMenu) {
            QuickMenu(
                currentLat = currentLat,
                currentLon = currentLon,
                viewSpan = viewSpan,
                onDismiss = { showQuickMenu = false }
            )
        }

        if (tappedDestination != null) {
            val dest = tappedDestination!!
            Dialog(
                showDialog = true,
                onDismissRequest = { tappedDestination = null }
            ) {
                val scope = rememberCoroutineScope()
                PlacePage(
                    result = dest,
                                    onNavigate = { routerType, avoidTolls, avoidMotorways, avoidFerries, avoidUnpaved ->
                                        scope.launch {
                                            if (navState.standaloneMode || (!navState.isPhoneConnected && navState.watchLocalMode)) {
                                                // PRE-CHECK Map Material
                                                if (app.isFullyInitialized && !Framework.nativeIsDownloadedMapAtLocation(dest.lat, dest.lon)) {
                                                    android.widget.Toast.makeText(context, "Map not downloaded for destination", android.widget.Toast.LENGTH_LONG).show()
                                                    return@launch
                                                }

                                                try {
                                                    app.waitForInitializationSuspend()

                                                    // RESET ROUTE BUILDING STATE FIRST to avoid any overlap
                                                    NavigationStateHolder.update { it.copy(
                                                        isActive = true,
                                                        isNavigating = false,
                                                        routeBuildProgress = 0,
                                                        isRouteBuilding = true,
                                                        isRouteReady = false,
                                                        routePoints = emptyList(),
                                                        lastRouteError = 0,
                                                        isMapUnlockedBeforeNav = it.isMapUnlocked,
                                                        isMapUnlocked = true // LOCK SWIPE during route building
                                                    ) }

                                                    // APPLY ROUTING OPTIONS
                                                    val roadTypes = RoadType.values()
                                                    roadTypes.forEach { RoutingOptions.removeOption(it) }
                                                    if (avoidTolls) RoutingOptions.addOption(RoadType.Toll)
                                                    if (avoidMotorways) RoutingOptions.addOption(RoadType.Motorway)
                                                    if (avoidFerries) RoutingOptions.addOption(RoadType.Ferry)
                                                    if (avoidUnpaved) RoutingOptions.addOption(RoadType.Dirty)

                                                    // FALLBACK FOR STANDALONE ROUTING START POINT
                                                    val startPoint = app.organicMaps.locationHelper.myPosition 
                                                        ?: app.organicMaps.locationHelper.savedLocation?.let { 
                                                            MapObject.createMapObject(MapObject.MY_POSITION, "My Location", "", it.latitude, it.longitude)
                                                        }
                                                        ?: let {
                                                            val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
                                                            val lastLat = prefs.getFloat("last_known_lat", 0f).toDouble()
                                                            val lastLon = prefs.getFloat("last_known_lon", 0f).toDouble()
                                                            if (lastLat != 0.0) {
                                                                MapObject.createMapObject(MapObject.MY_POSITION, "Previous Fix", "", lastLat, lastLon)
                                                            } else null
                                                        }

                                                    if (startPoint == null) {
                                                        android.util.Log.e("MapPanel", "No GPS position for routing")
                                                        android.widget.Toast.makeText(context, "No GPS position for routing", android.widget.Toast.LENGTH_LONG).show()
                                                        NavigationStateHolder.update { it.copy(isRouteBuilding = false) }
                                                        return@launch
                                                    }
                                                    val destination = MapObject.createMapObject(MapObject.POI, dest.name, dest.description, dest.lat, dest.lon)
                                                    val router = when (routerType) {
                                                        0 -> Router.Vehicle
                                                        1 -> Router.Pedestrian
                                                        2 -> Router.Bicycle
                                                        else -> Router.Transit
                                                    }
                                                    val controller = RoutingController.get()
                                                    controller.prepare(startPoint, destination, router)
                                                    controller.checkAndBuildRoute()
                                                    
                                                    // Update meta info only, building state already set
                                                                    NavigationStateHolder.update { it.copy(
                                                        distToTurn = "",
                                                        nextStreet = "",
                                                        distToTarget = "",
                                                        eta = 0,
                                                        completionPercent = 0.0,
                                                        turnLat = 0.0,
                                                        turnLon = 0.0,
                                                        isMapUnlocked = false,
                                                        avoidTolls = avoidTolls,
                                                        avoidMotorways = avoidMotorways,
                                                        avoidFerries = avoidFerries,
                                                        avoidUnpaved = avoidUnpaved
                                                    ) }
                                                } catch (e: Exception) {
                                                    android.util.Log.e("MapPanel", "Route planning failed: ${e.message}")
                                                    android.widget.Toast.makeText(context, "Routing failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                                    NavigationStateHolder.update { it.copy(isRouteBuilding = false) }
                                                }
                            } else {
                                WearCommandService.selectSearchResult(context, dest, routerType)
                                // Transition to route preview state locally too
                                NavigationStateHolder.update { it.copy(
                                    isActive = true,
                                    isNavigating = false,
                                    destinationName = dest.name,
                                    isMapUnlockedBeforeNav = it.isMapUnlocked,
                                    isMapUnlocked = true, // LOCK SWIPE
                                    isRouteBuilding = true
                                ) }
                            }
                            tappedDestination = null
                        }
                    },
                    onDismiss = { 
                        tappedDestination = null 
                        val state = NavigationStateHolder.state.value
                        if (state.isRouteBuilding || (state.isActive && !state.isNavigating)) {
                            RoutingController.get().cancel()
                            NavigationStateHolder.update(state.copy(
                                isRouteBuilding = false, 
                                isActive = false,
                                isMapUnlocked = state.isMapUnlockedBeforeNav
                            ), force = true)
                        } else {
                            NavigationStateHolder.update(state.copy(
                                isRouteBuilding = false
                            ), force = true)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun QuickMenu(currentLat: Double, currentLon: Double, viewSpan: Float, onDismiss: () -> Unit) {
    androidx.wear.compose.material.dialog.Dialog(
        showDialog = true,
        onDismissRequest = onDismiss
    ) {
        val navState by NavigationStateHolder.state.collectAsState()
        val context = LocalContext.current
        
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp, start = 8.dp, end = 8.dp)
        ) {
            item {
                Text("Quick Menu", style = MaterialTheme.typography.caption1, color = Color(0xFF00E5FF))
            }
            
            item {
                Chip(
                    onClick = {
                        val newSpan = (viewSpan * 0.75f).coerceAtLeast(0.0001f)
                        NavigationStateHolder.update(navState.copy(
                            isMapUnlocked = true,
                            manualViewSpan = newSpan,
                            manualCenterLat = if (!navState.isMapUnlocked) currentLat else navState.manualCenterLat,
                            manualCenterLon = if (!navState.isMapUnlocked) currentLon else navState.manualCenterLon,
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        ))
                        onDismiss()
                    },
                    label = { Text("Zoom In") },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
            
            item {
                Chip(
                    onClick = {
                        val newSpan = (viewSpan * 1.33f).coerceAtMost(0.05f)
                        NavigationStateHolder.update(navState.copy(
                            isMapUnlocked = true,
                            manualViewSpan = newSpan,
                            manualCenterLat = if (!navState.isMapUnlocked) currentLat else navState.manualCenterLat,
                            manualCenterLon = if (!navState.isMapUnlocked) currentLon else navState.manualCenterLon,
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        ))
                        onDismiss()
                    },
                    label = { Text("Zoom Out") },
                    icon = { Icon(Icons.Default.Remove, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
            
            item {
                Chip(
                    onClick = {
                        NavigationStateHolder.update { it.copy(isMapUnlocked = false, lastSettingsInteractionTime = System.currentTimeMillis()) }
                        onDismiss()
                    },
                    label = { Text("Follow Position") },
                    icon = { Icon(Icons.Default.MyLocation, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
            
            item {
                ToggleChip(
                    checked = !navState.isMapUnlocked,
                    onCheckedChange = { newVal ->
                        // CRITICAL: Fetch absolute latest state value
                        val current = NavigationStateHolder.state.value
                        NavigationStateHolder.update(current.copy(
                            isMapUnlocked = !newVal,
                            // If unlocking, initialize manual center to exactly where we are looking now
                            manualCenterLat = if (current.lat != 0.0) current.lat else currentLat,
                            manualCenterLon = if (current.lon != 0.0) current.lon else currentLon,
                            manualViewSpan = 0.003f,
                            lastSettingsInteractionTime = System.currentTimeMillis()
                        ))
                        onDismiss()
                    },
                    label = { Text("Follow Position") },
                    toggleControl = {
                        Switch(checked = !navState.isMapUnlocked)
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                )
            }
            
            item {
                Chip(
                    onClick = onDismiss,
                    label = { Text("Close") },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }
    }
}

fun DrawPassInternal(
    tile: ParsedMapTile,
    curX: Double,
    curY: Double,
    curSpan: Double,
    pass: Int,
    isAmbient: Boolean,
    viewSpan: Float,
    isDark: Boolean,
    drawScope: DrawScope,
    poiIcons: Map<String, Painter>,
    specificType: Int? = null
) {
    with(drawScope) {
        withTransform({
            translate(size.width / 2, size.height / 2)
            val scale = (tile.mercatorSpan / curSpan).toFloat()
            scale(scale, scale, Offset.Zero)
            
            val canonicalScale = size.width / 1000f
            val dx = ((tile.mercatorX - curX) / tile.mercatorSpan * 1000.0 * canonicalScale).toFloat()
            val dy = ((tile.mercatorY - curY) / tile.mercatorSpan * 1000.0 * canonicalScale).toFloat()
            translate(dx, dy)
            scale(canonicalScale, canonicalScale, Offset.Zero)
            translate(-500f, -500f)
        }) {
            // Pass 1-3 are Background and Roads - apply strict clipping to fix stitching
            if (pass in 1..3) {
                drawIntoCanvas { canvas ->
                    canvas.clipRect(0f, 0f, 1000f, 1000f)
                    DrawTilePassInternal(tile, pass, isAmbient, viewSpan, isDark, this, poiIcons, specificType)
                }
            } else {
                DrawTilePassInternal(tile, pass, isAmbient, viewSpan, isDark, this, poiIcons, specificType)
            }
        }
    }
}

fun DrawTilePassInternal(tile: ParsedMapTile, pass: Int, isAmbient: Boolean = false, viewSpan: Float = 0.003f, isDark: Boolean = false, drawScope: DrawScope, poiIcons: Map<String, Painter>, specificType: Int? = null) {
    with(drawScope) {
        val typesToDraw = if (specificType != null) listOf(specificType) else tile.pathsByType.keys.toList()

        // Performance: Dynamic Detail Reduction (LOD)
        val hideMinorRoads = viewSpan > 0.035f
        val hideBuildings = viewSpan > 0.025f
        val hideLabels = viewSpan > 0.015f

        for (type in typesToDraw) {
            if (type == 2 && hideBuildings) continue
            if (type == 8 && hideMinorRoads) continue

            val features = tile.pathsByType[type] ?: continue
            for (f in features) {
                val mapPath = f.path
                if (isAmbient) {
                    if (pass != 3) continue // Only draw fills in ambient
                    // In ambient mode, only draw water and roads in grayscale
                    when (type) {
                        3 -> drawPath(mapPath, Color.Gray) // Water
                        4, 5, 6, 7, 8, 1 -> drawPath(mapPath, Color.White, style = Stroke(width = 8f)) // Roads
                    }
                    continue
                }
                
                // PRETTIER COLORS (Organic Maps Inspired)
                val roadBorderColor = if (isDark) Color(0xFF333333) else Color(0xFF808080) // Darker border for day mode
                when (type) {
                    1 -> { // Residential
                        if (pass == 2) drawPath(mapPath, roadBorderColor, style = Stroke(width = 24f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        if (pass == 3) drawPath(mapPath, if (isDark) Color(0xFF444444) else Color.White, style = Stroke(width = 15f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    2 -> if (pass == 1) drawPath(mapPath, if (isDark) Color(0xFF2A2A2A) else Color(0xFFDEDBD0)) // Buildings
                    3 -> if (pass == 1) drawPath(mapPath, if (isDark) Color(0xFF1F2D3D) else Color(0xFF90CAF9)) // Water
                    4 -> { // Motorway/Trunk (Orange)
                        if (pass == 2) drawPath(mapPath, if (isDark) Color(0xFFE67E22) else Color(0xFFE67E22), style = Stroke(width = 36f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        if (pass == 3) drawPath(mapPath, if (isDark) Color(0xFFD35400) else Color(0xFFFF9800), style = Stroke(width = 26f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    5 -> { // Primary (Yellow)
                        if (pass == 2) drawPath(mapPath, if (isDark) Color(0xFFF1C40F) else Color(0xFFF1C40F), style = Stroke(width = 32f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        if (pass == 3) drawPath(mapPath, if (isDark) Color(0xFFF39C12) else Color(0xFFFBC02D), style = Stroke(width = 22f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    6 -> { // Secondary
                        if (pass == 2) drawPath(mapPath, roadBorderColor, style = Stroke(width = 28f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        if (pass == 3) drawPath(mapPath, if (isDark) Color(0xFF444444) else Color.White, style = Stroke(width = 19f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    7 -> { // Tertiary
                        if (pass == 2) drawPath(mapPath, roadBorderColor, style = Stroke(width = 24f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        if (pass == 3) drawPath(mapPath, if (isDark) Color(0xFF3A3A3A) else Color.White, style = Stroke(width = 15f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    8 -> { // Service/Minor
                        if (pass == 2) drawPath(mapPath, roadBorderColor, style = Stroke(width = 20f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        if (pass == 3) drawPath(mapPath, if (isDark) Color(0xFF333333) else Color(0xFFF5F5F5), style = Stroke(width = 13f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    9 -> if (pass == 1) drawPath(mapPath, if (isDark) Color(0xFF212D21) else Color(0xFFD4E3A9)) // Greenery
                    else -> {
                        if (pass == 2) drawPath(mapPath, if (isDark) Color(0xFF222222) else Color(0xFF808080), style = Stroke(width = 18f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        if (pass == 3) drawPath(mapPath, if (isDark) Color(0xFF333333) else Color(0xFFFAFAFA), style = Stroke(width = 11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                
                // Pass 4: Labels
                if (pass == 4 && !hideLabels && f.name.isNotEmpty() && type in listOf(4, 5, 6, 7, 1, 8)) {
                    // Show road names for residential (1) and service (8) only when zoomed in closely
                    if (type in listOf(1, 8) && viewSpan > 0.015f) continue
                    
                    drawIntoCanvas { canvas ->
                        val textPaint = android.graphics.Paint().apply {
                            this.color = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                            textSize = 8.dp.toPx()
                            isAntiAlias = true
                            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                            setShadowLayer(2.dp.toPx(), 0f, 0f, if (isDark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                        }
                        canvas.nativeCanvas.drawTextOnPath(f.name, f.path.asAndroidPath(), 0f, 3.dp.toPx(), textPaint)
                    }
                }
            }
        }
        
        // Pass 4: POIs & House Numbers
        if (pass == 4 && !isAmbient && !hideLabels) {
            // Draw House Numbers for buildings (Type 2) when zoomed in closely
            if (viewSpan < 0.008f) {
                val buildingFeatures = tile.pathsByType[2] ?: emptyList()
                for (f in buildingFeatures) {
                    if (f.name.isEmpty()) continue
                    // Extract just the number if it's "Name (12a)" or use full if it's just "12a"
                    val label = if (f.name.contains("(")) {
                        f.name.substringAfter("(").substringBefore(")")
                    } else f.name
                    
                    f.labelPos?.let { p ->
                        drawIntoCanvas { canvas ->
                            val textPaint = android.graphics.Paint().apply {
                                color = if (isDark) android.graphics.Color.GRAY else android.graphics.Color.DKGRAY
                                textSize = 7.dp.toPx()
                                textAlign = android.graphics.Paint.Align.CENTER
                                isAntiAlias = true
                            }
                            canvas.nativeCanvas.drawText(label, p.x, p.y + 3.dp.toPx(), textPaint)
                        }
                    }
                }
            }

            for ((type, features) in tile.pointsByType) {
                val color = when (type) {
                    100, 101 -> Color(0xFFE67E22) // Eat / Cafe
                    102 -> Color(0xFF9B59B6) // Hotel
                    103 -> Color(0xFF2ECC71) // ATM
                    107 -> Color(0xFF3498DB) // Parking
                    108 -> Color(0xFF7F8C8D) // Peak
                    109 -> Color(0xFF27AE60) // Camping
                    111, 112 -> Color(0xFFF1C40F) // Transport
                    else -> Color(0xFF95A5A6)
                }

                val groupIcon = when (type) {
                    100 -> "food"
                    101 -> "cafe"
                    102 -> "hotel"
                    103 -> "money"
                    107 -> "parking"
                    108 -> "mountain"
                    109 -> "park"
                    111, 112 -> "transport"
                    113 -> "airport"
                    else -> "sights"
                }

                for (f in features) {
                    val p = f.point
                    
                    val iconKey = if (f.iconName.isNotEmpty()) {
                        when {
                            f.iconName.contains("bench") -> "bench"
                            f.iconName.contains("fountain") -> "fountain"
                            f.iconName.contains("picnic_table") -> "picnic_table"
                            f.iconName.contains("waste_basket") || f.iconName.contains("bin") -> "waste_basket"
                            f.iconName.contains("toilets") -> "toilets"
                            f.iconName.contains("drinking_water") -> "drinking_water"
                            f.iconName.contains("postbox") -> "postbox"
                            f.iconName.contains("recycling") -> "recycling"
                            f.iconName.contains("shelter") -> "shelter"
                            f.iconName.contains("playground") -> "playground"
                            f.iconName.contains("clinic") -> "clinic"
                            f.iconName.contains("hostel") -> "hostel"
                            f.iconName.contains("bakery") -> "bakery"
                            f.iconName.contains("supermarket") -> "supermarket"
                            f.iconName.contains("convenience") -> "convenience"
                            f.iconName.contains("cemetery") || f.iconName.contains("tomb") -> "cemetery"
                            f.iconName.contains("lighthouse") -> "lighthouse"
                            f.iconName.contains("vending") -> "vending"
                            f.iconName.contains("laundry") -> "laundry"
                            f.iconName.contains("car_wash") -> "car_wash"
                            f.iconName.contains("fire_hydrant") || f.iconName.contains("firehydrant") -> "fire_hydrant"
                            f.iconName.contains("hospital") -> "hospital"
                            f.iconName.contains("bus") -> "bus"
                            f.iconName.contains("train") -> "train"
                            f.iconName.contains("railway") -> "train"
                            f.iconName.contains("tram") -> "tram"
                            f.iconName.contains("subway") -> "subway"
                            f.iconName.contains("taxi") -> "taxi"
                            f.iconName.contains("garden") -> "garden"
                            f.iconName.contains("soccer") -> "soccer"
                            f.iconName.contains("basketball") -> "basketball"
                            f.iconName.contains("tennis") -> "tennis"
                            f.iconName.contains("zoo") -> "zoo"
                            f.iconName.contains("beach") -> "beach"
                            f.iconName.contains("bbq") -> "bbq"
                            f.iconName.contains("fire_station") -> "fire_station"
                            f.iconName.contains("dentist") -> "dentist"
                            f.iconName.contains("doctor") -> "medicine"
                            f.iconName.contains("college") || f.iconName.contains("university") -> "college"
                            f.iconName.contains("museum") -> "museum"
                            f.iconName.contains("cinema") -> "cinema"
                            f.iconName.contains("stadium") -> "stadium"
                            f.iconName.contains("statue") -> "statue"
                            f.iconName.contains("monument") -> "monument"
                            f.iconName.contains("attraction") -> "attraction"
                            f.iconName.contains("theme_park") -> "theme_park"
                            f.iconName.contains("restaurant") || f.iconName.contains("food") -> "food"
                            f.iconName.contains("fast_food") -> "fast_food"
                            f.iconName.contains("cafe") -> "cafe"
                            f.iconName.contains("hotel") || f.iconName.contains("motel") || f.iconName.contains("guest_house") -> "hotel"
                            f.iconName.contains("atm") -> "atm"
                            f.iconName.contains("bank") -> "bank"
                            f.iconName.contains("fuel") || f.iconName.contains("gas") || f.iconName.contains("petrol") -> "gas"
                            f.iconName.contains("hospital") || f.iconName.contains("pharmacy") || f.iconName.contains("medicine") || f.iconName.contains("clinic") || f.iconName.contains("doctor") || f.iconName.contains("dentist") -> "medicine"
                            f.iconName.contains("peak") || f.iconName.contains("mountain") -> "mountain"
                            f.iconName.contains("camp_site") || f.iconName.contains("pitch") || f.iconName.contains("caravan") -> "park"
                            f.iconName.contains("aerodrome") || f.iconName.contains("airport") -> "airport"
                            f.iconName.contains("cinema") -> "cinema"
                            f.iconName.contains("theatre") -> "theatre"
                            f.iconName.contains("museum") || f.iconName.contains("gallery") || f.iconName.contains("art") -> "museum"
                            f.iconName.contains("church") || f.iconName.contains("cathedral") || f.iconName.contains("temple") || f.iconName.contains("christian") || f.iconName.contains("religious") || f.iconName.contains("synagogue") || f.iconName.contains("mosque") -> "christianity"
                            f.iconName.contains("attraction") || f.iconName.contains("monument") || f.iconName.contains("viewpoint") || f.iconName.contains("tourism") || f.iconName.contains("memorial") -> "viewpoint"
                            f.iconName.contains("bicycle_parking") || f.iconName.contains("bicycle_rental") -> "bicycle_parking"
                            f.iconName.contains("charging_station") -> "charging_station"
                            f.iconName.contains("parking") -> "parking"
                            f.iconName.contains("shop") -> "shop"
                            f.iconName.contains("bar") || f.iconName.contains("pub") || f.iconName.contains("biergarten") -> "bar"
                            f.iconName.contains("zoo") || f.iconName.contains("aquarium") || f.iconName.contains("park") || f.iconName.contains("garden") || f.iconName.contains("forest") -> "animals"
                            f.iconName.contains("stadium") || f.iconName.contains("sport") || f.iconName.contains("leisure") -> "sport"
                            f.iconName.contains("swimming") || f.iconName.contains("beach") -> "swim"
                            f.iconName.contains("information") || f.iconName.contains("guide") -> "information"
                            f.iconName.contains("building") || f.iconName.contains("castle") || f.iconName.contains("fortress") || f.iconName.contains("palace") || f.iconName.contains("manor") -> "building"
                            f.iconName.contains("bus") || f.iconName.contains("subway") || f.iconName.contains("train") || f.iconName.contains("railway") || f.iconName.contains("tram") || f.iconName.contains("station") -> "transport"
                            else -> f.iconName
                        }
                    } else groupIcon

                    val icon = poiIcons[iconKey] ?: poiIcons[groupIcon] ?: poiIcons["none"]
                    
                    // Simple, performant POI Pin
                    val pinSize = 10.dp.toPx()
                    drawCircle(
                        color = color,
                        radius = pinSize,
                        center = p
                    )
                    drawCircle(
                        color = Color.White,
                        radius = pinSize,
                        center = p,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                    
                    // Draw Icon
                    if (icon != null) {
                        val iconSizePx = 13.dp.toPx()
                        withTransform({
                            translate(p.x - iconSizePx / 2f, p.y - iconSizePx / 2f)
                        }) {
                            with(icon) {
                                draw(
                                    size = androidx.compose.ui.geometry.Size(iconSizePx, iconSizePx),
                                    colorFilter = ColorFilter.tint(Color.White)
                                )
                            }
                        }
                    }
                    
                    // Labels only when zoomed in very closely
                    if (f.name.isNotEmpty() && viewSpan < 0.004f) {
                        drawIntoCanvas { canvas ->
                            val textPaint = android.graphics.Paint().apply {
                                this.color = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                                textSize = 9.dp.toPx()
                                textAlign = android.graphics.Paint.Align.CENTER
                                isAntiAlias = true
                                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                                setShadowLayer(2f, 0f, 0f, if (isDark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                            }
                            val bgPaint = android.graphics.Paint().apply {
                                this.color = if (isDark) android.graphics.Color.argb(180, 40, 40, 40) else android.graphics.Color.argb(180, 255, 255, 255)
                            }
                            val bounds = android.graphics.Rect()
                            textPaint.getTextBounds(f.name, 0, f.name.length, bounds)
                            val bgRect = android.graphics.RectF(
                                p.x - bounds.width()/2f - 3.dp.toPx(),
                                p.y + 11.dp.toPx() - bounds.height() - 1.dp.toPx(),
                                p.x + bounds.width()/2f + 3.dp.toPx(),
                                p.y + 11.dp.toPx() + 2.dp.toPx()
                            )
                            canvas.nativeCanvas.drawRoundRect(bgRect, 3.dp.toPx(), 3.dp.toPx(), bgPaint)
                            canvas.nativeCanvas.drawText(f.name, p.x, p.y + 11.dp.toPx(), textPaint)
                        }
                    }
                }
            }
        }
    }
}
