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
import androidx.compose.ui.geometry.Rect

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

object LabelOverlapManager {
    private val boxes = mutableListOf<Rect>()
    private val lock = Any()
    private var screenWidth = 0f
    private var screenHeight = 0f

    fun clear(width: Float, height: Float) {
        synchronized(lock) {
            boxes.clear()
            screenWidth = width
            screenHeight = height
        }
    }

    fun canDraw(rect: Rect, marginDp: Float = 4f, density: Float = 1f): Boolean {
        synchronized(lock) {
            val margin = marginDp * density
            val expandedRect = Rect(rect.left - margin, rect.top - margin, rect.right + margin, rect.bottom + margin)

            // Circular clipping check: Skip if label is too close to screen edges
            val centerX = screenWidth / 2f
            val centerY = screenHeight / 2f
            val radius = minOf(centerX, centerY)
            val clipLimit = radius * 0.92f // 8% safety margin from edge

            fun isInside(x: Float, y: Float): Boolean =
                hypot(x - centerX, y - centerY) < clipLimit

            // Check four corners for circular clipping
            if (!isInside(rect.left, rect.top) || !isInside(rect.right, rect.top) ||
                !isInside(rect.left, rect.bottom) || !isInside(rect.right, rect.bottom)) return false

            for (box in boxes) {
                if (box.overlaps(expandedRect)) return false
            }
            boxes.add(rect)
            return true
        }
    }
}

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
    val allTiles by MapTileStateHolder.cachedTilesFlow.collectAsState()
    val sensorViewModel: SensorViewModel = viewModel()
    val compassHeading by sensorViewModel.heading.collectAsState()
    val focusRequester = remember { FocusRequester() }
    
    LaunchedEffect(Unit) {
        if (isVisible) focusRequester.requestFocus()
    }
    
    var showQuickMenu by remember { mutableStateOf(false) }
    var tappedDestination by remember { mutableStateOf<SearchResultItem?>(null) }

    val effectiveLat = if (navState.isMapUnlocked) navState.manualCenterLat else navState.lat
    val effectiveLon = if (navState.isMapUnlocked) navState.manualCenterLon else navState.lon
    
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

    val targetViewSpan = remember(navState.speedMps, navState.routerType, navState.isActive, navState.isMapUnlocked, navState.manualViewSpan) {
        if (navState.isMapUnlocked) return@remember navState.manualViewSpan.toDouble()
        val vs = 1.0 
        val speedKmpH = if (navState.speedMps >= 0) navState.speedMps * 3.6 else 0.0
        val scales2d = listOf(20.0 to 0.70, 40.0 to 1.25, 60.0 to 2.25, 75.0 to 3.00, 85.0 to 3.75, 95.0 to 6.00)
        val baseScale = when {
            navState.routerType == 1 -> 0.70 
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
        (1000.0 * baseScale) / 111000.0
    }
    
    val viewSpan by animateFloatAsState(
        targetValue = targetViewSpan.toFloat(),
        animationSpec = if (navState.isMapUnlocked) tween(80) else tween(durationMillis = 2500, easing = FastOutSlowInEasing),
        label = "zoom"
    )
    val clampedViewSpan = viewSpan.coerceAtLeast(0.0001f)
    val currentScale = (log2(360.0 / (clampedViewSpan * 2.0)).toInt()).coerceIn(1, 19)

    var loadingScale by remember { mutableIntStateOf(currentScale) }
    LaunchedEffect(currentScale) {
        if (abs(currentScale - loadingScale) >= 1) loadingScale = currentScale
    }

    var isUsingGpsBearing by remember { mutableStateOf(false) }

    LaunchedEffect(navState.bearing, navState.speedMps, compassHeading, navState.isActive, navState.isMapUnlocked) {
        if (navState.speedMps > 2.0f) isUsingGpsBearing = true
        else if (navState.speedMps < 0.8f || navState.bearing < 0f) isUsingGpsBearing = false
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
    val verticalOffsetFraction by animateFloatAsState(targetValue = verticalOffsetFractionTarget, animationSpec = spring(stiffness = Spring.StiffnessLow), label = "offset")

    val effectivelyStandalone = navState.isEffectivelyStandalone
    val useOfflineMaps = navState.watchLocalMode || !navState.isPhoneConnected || navState.standaloneMode
    var isMapDownloaded by remember { mutableStateOf(true) }
    LaunchedEffect(currentLat, currentLon, useOfflineMaps) {
        if (useOfflineMaps && app.isFullyInitialized) isMapDownloaded = Framework.nativeIsDownloadedMapAtLocation(currentLat, currentLon)
        else isMapDownloaded = true
    }

    val effectivePoiMask = if (navState.isActive) 0 else navState.poiCategoriesMask
    val scope = rememberCoroutineScope()

    val iconArt = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_art) else null
    val iconAtm = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_atm) else null
    val iconBar = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_bar) else null
    val iconBbq = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_bbq) else null
    val iconBus = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_bus) else null
    val iconGas = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_gas) else null
    val iconPub = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_pub) else null
    val iconZoo = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_zoo) else null
    val iconBank = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_bank) else null
    val iconCafe = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_cafe) else null
    val iconFood = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_food) else null
    val iconMail = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_mail) else null
    val iconPark = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_park) else null
    val iconShop = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_shop) else null
    val iconSwim = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_swim) else null
    val iconTaxi = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_taxi) else null
    val iconTram = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_tram) else null
    val iconBeach = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_beach) else null
    val iconBench = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_bench) else null
    val iconHotel = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_hotel) else null
    val iconIslam = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_islam) else null
    val iconMoney = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_money) else null
    val iconSport = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_sport) else null
    val iconTrain = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_train) else null
    val iconWater = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_water) else null
    val iconBakery = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_bakery) else null
    val iconCinema = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_cinema) else null
    val iconClinic = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_clinic) else null
    val iconGarden = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_garden) else null
    val iconHostel = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_hostel) else null
    val iconMuseum = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_museum) else null
    val iconPolice = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_police) else null
    val iconSchool = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_school) else null
    val iconSights = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_sights) else null
    val iconSoccer = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_soccer) else null
    val iconStatue = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_statue) else null
    val iconSubway = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_subway) else null
    val iconTennis = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_tennis) else null
    val iconAirport = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_airport) else null
    val iconAnimals = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_animals) else null
    val iconCollege = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_college) else null
    val iconDentist = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_dentist) else null
    val iconJudaism = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_judaism) else null
    val iconLaundry = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_laundry) else null
    val iconLibrary = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_library) else null
    val iconParking = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_parking) else null
    val iconPostbox = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_postbox) else null
    val iconShelter = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_shelter) else null
    val iconStadium = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_stadium) else null
    val iconTheatre = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_theatre) else null
    val iconToilets = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_toilets) else null
    val iconVending = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_vending) else null
    val iconBuddhism = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_buddhism) else null
    val iconBuilding = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_building) else null
    val iconCarWash = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_car_wash) else null
    val iconCemetery = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_cemetery) else null
    val iconExchange = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_exchange) else null
    val iconFountain = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_fountain) else null
    val iconHospital = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_hospital) else null
    val iconMedicine = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_medicine) else null
    val iconMonument = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_monument) else null
    val iconMountain = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_mountain) else null
    val iconPharmacy = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_pharmacy) else null
    val iconFastFood = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_fast_food) else null
    val iconRecycling = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_recycling) else null
    val iconTransport = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_transport) else null
    val iconViewpoint = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_viewpoint) else null
    val iconAttraction = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_attraction) else null
    val iconBasketball = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_basketball) else null
    val iconLighthouse = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_lighthouse) else null
    val iconPlayground = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_playground) else null
    val iconThemePark = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_theme_park) else null
    val iconConvenience = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_convenience) else null
    val iconFirehydrant = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_firehydrant) else null
    val iconInformation = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_information) else null
    val iconSupermarket = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_supermarket) else null
    val iconChristianity = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_christianity) else null
    val iconFireStation = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_fire_station) else null
    val iconPicnicTable = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_picnic_table) else null
    val iconWasteBasket = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_waste_basket) else null
    val iconEntertainment = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_entertainment) else null
    val iconBicycleRental = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_bicycle_rental) else null
    val iconDrinkingWater = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_drinking_water) else null
    val iconBicycleParking = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_bicycle_parking) else null
    val iconChargingStation = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_charging_station) else null
    val iconBicycleParkingCovered = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_bicycle_parking_covered) else null
    val iconNone = if (!isAmbient) painterResource(id = SdkR.drawable.ic_bookmark_none) else null

    val poiIcons = remember(isAmbient, iconArt, iconAtm, iconBar, iconBbq, iconBus, iconGas, iconPub, iconZoo, iconBank, iconCafe, iconFood, iconMail, iconPark, iconShop, iconSwim, iconTaxi, iconTram, iconBeach, iconBench, iconHotel, iconIslam, iconMoney, iconSport, iconTrain, iconWater, iconBakery, iconCinema, iconClinic, iconGarden, iconHostel, iconMuseum, iconPolice, iconSchool, iconSights, iconSoccer, iconStatue, iconSubway, iconTennis, iconAirport, iconAnimals, iconCollege, iconDentist, iconJudaism, iconLaundry, iconLibrary, iconParking, iconPostbox, iconShelter, iconStadium, iconTheatre, iconToilets, iconVending, iconBuddhism, iconBuilding, iconCarWash, iconCemetery, iconExchange, iconFountain, iconHospital, iconMedicine, iconMonument, iconMountain, iconPharmacy, iconFastFood, iconRecycling, iconTransport, iconViewpoint, iconAttraction, iconBasketball, iconLighthouse, iconPlayground, iconThemePark, iconConvenience, iconFirehydrant, iconInformation, iconSupermarket, iconChristianity, iconFireStation, iconPicnicTable, iconWasteBasket, iconEntertainment, iconBicycleRental, iconDrinkingWater, iconBicycleParking, iconChargingStation, iconBicycleParkingCovered, iconNone) {
        if (isAmbient) emptyMap()
        else mapOf(
            "art" to iconArt!!, "atm" to iconAtm!!, "bar" to iconBar!!, "bbq" to iconBbq!!, "bus" to iconBus!!, "gas" to iconGas!!, "pub" to iconPub!!, "zoo" to iconZoo!!,
            "bank" to iconBank!!, "cafe" to iconCafe!!, "food" to iconFood!!, "mail" to iconMail!!, "park" to iconPark!!, "shop" to iconShop!!, "swim" to iconSwim!!,
            "taxi" to iconTaxi!!, "tram" to iconTram!!, "beach" to iconBeach!!, "bench" to iconBench!!, "hotel" to iconHotel!!, "islam" to iconIslam!!, "money" to iconMoney!!,
            "sport" to iconSport!!, "train" to iconTrain!!, "water" to iconWater!!, "bakery" to iconBakery!!, "cinema" to iconCinema!!, "clinic" to iconClinic!!,
            "garden" to iconGarden!!, "hostel" to iconHostel!!, "museum" to iconMuseum!!, "police" to iconPolice!!, "school" to iconSchool!!, "sights" to iconSights!!,
            "soccer" to iconSoccer!!, "statue" to iconStatue!!, "subway" to iconSubway!!, "tennis" to iconTennis!!, "airport" to iconAirport!!, "animals" to iconAnimals!!,
            "college" to iconCollege!!, "dentist" to iconDentist!!, "judaism" to iconJudaism!!, "laundry" to iconLaundry!!, "library" to iconLibrary!!, "parking" to iconParking!!,
            "postbox" to iconPostbox!!, "shelter" to iconShelter!!, "stadium" to iconStadium!!, "theatre" to iconTheatre!!, "toilets" to iconToilets!!, "vending" to iconVending!!,
            "buddhism" to iconBuddhism!!, "building" to iconBuilding!!, "car_wash" to iconCarWash!!, "cemetery" to iconCemetery!!, "exchange" to iconExchange!!,
            "fountain" to iconFountain!!, "hospital" to iconHospital!!, "medicine" to iconMedicine!!, "monument" to iconMonument!!, "mountain" to iconMountain!!,
            "pharmacy" to iconPharmacy!!, "fast_food" to iconFastFood!!, "recycling" to iconRecycling!!, "transport" to iconTransport!!, "viewpoint" to iconViewpoint!!,
            "attraction" to iconAttraction!!, "basketball" to iconBasketball!!, "lighthouse" to iconLighthouse!!, "playground" to iconPlayground!!, "theme_park" to iconThemePark!!,
            "convenience" to iconConvenience!!, "firehydrant" to iconFirehydrant!!, "information" to iconInformation!!, "supermarket" to iconSupermarket!!,
            "christianity" to iconChristianity!!, "fire_station" to iconFireStation!!, "picnic_table" to iconPicnicTable!!, "waste_basket" to iconWasteBasket!!,
            "entertainment" to iconEntertainment!!, "bicycle_rental" to iconBicycleRental!!, "drinking_water" to iconDrinkingWater!!, "bicycle_parking" to iconBicycleParking!!,
            "charging_station" to iconChargingStation!!, "bicycle_parking_covered" to iconBicycleParkingCovered!!, "none" to iconNone!!
        )
    }

    val jniDispatcher = remember { Dispatchers.Default.limitedParallelism(4) }
    LaunchedEffect(Unit) {
        MapTileStateHolder.mapTileFlow.collect { tile ->
            val key = requestKeys[tile.requestId] ?: MapTileKey(Mercator.lonToTileX(currentLon, loadingScale), Mercator.latToTileY(currentLat, loadingScale), loadingScale)
            scope.launch(Dispatchers.Default) {
                val parsed = MapTileStateHolder.parseTile(tile.features, key, 1000f, 1000f)
                withContext(Dispatchers.Main) { MapTileStateHolder.updateCache(key, parsed); requestKeys.remove(tile.requestId) }
            }
        }
    }

    // Performance: Quantized viewSpan for requests to improve responsiveness
    var localRequestLat by remember { mutableStateOf(currentLat) }
    var localRequestLon by remember { mutableStateOf(currentLon) }
    var localRequestSpan by remember { mutableStateOf(clampedViewSpan) }

    LaunchedEffect(currentLat, currentLon, clampedViewSpan, verticalOffsetFraction) {
        delay(100)
        val screenCenterLat = if (!navState.isMapUnlocked && navState.isActive) { val rotationRad = Math.toRadians(sensorViewModel.mapRotationAnimatable.value.toDouble()); currentLat + (verticalOffsetFraction * clampedViewSpan * 2.0 * cos(rotationRad)) } else currentLat
        val screenCenterLon = if (!navState.isMapUnlocked && navState.isActive) { val rotationRad = Math.toRadians(sensorViewModel.mapRotationAnimatable.value.toDouble()); currentLon - (verticalOffsetFraction * clampedViewSpan * 2.0 * sin(rotationRad)) } else currentLon
        if (abs(screenCenterLat - localRequestLat) > clampedViewSpan * 0.3 || abs(screenCenterLon - localRequestLon) > clampedViewSpan * 0.3 || abs(clampedViewSpan - localRequestSpan) / localRequestSpan > 0.2) {
            localRequestLat = screenCenterLat; localRequestLon = screenCenterLon; localRequestSpan = clampedViewSpan
        }
    }

    LaunchedEffect(localRequestLat, localRequestLon, localRequestSpan, useOfflineMaps, effectivePoiMask, navState.isMapUnlocked, loadingScale) {
        val currentKey = MapTileKey(Mercator.lonToTileX(localRequestLon, loadingScale), Mercator.latToTileY(localRequestLat, loadingScale), loadingScale)
        val grid = mutableListOf<MapTileKey>()
        for (dx in -3..3) for (dy in -3..3) grid.add(MapTileKey(currentKey.x + dx, currentKey.y + dy, loadingScale))
        val sortedGrid = grid.sortedBy { hypot((it.x - currentKey.x).toDouble(), (it.y - currentKey.y).toDouble()) }
        sortedGrid.forEach { key ->
            if (MapTileStateHolder.getCachedTileByKey(key) == null) {
                launch(jniDispatcher) {
                    if (app.isFullyInitialized) {
                        val tileLeftLon = Mercator.tileXToLon(key.x, loadingScale); val tileTopLat = Mercator.tileYToLat(key.y, loadingScale)
                        val tileRightLon = Mercator.tileXToLon(key.x + 1, loadingScale); val tileBottomLat = Mercator.tileYToLat(key.y + 1, loadingScale)
                        val localFeatures = Framework.nativeGetWearMapFeatures(minOf(tileTopLat, tileBottomLat) - 0.001, minOf(tileLeftLon, tileRightLon) - 0.001, maxOf(tileTopLat, tileBottomLat) + 0.001, maxOf(tileLeftLon, tileRightLon) + 0.001, loadingScale, navState.routerType, effectivePoiMask)
                        if (localFeatures.isNotEmpty()) {
                            val parsed = MapTileStateHolder.parseTile(localFeatures, key, 1000f, 1000f)
                            withContext(Dispatchers.Main) { MapTileStateHolder.updateCache(key, parsed) }
                            return@launch
                        }
                    }
                    if (!useOfflineMaps || navState.isPhoneConnected) {
                        val requestId = System.nanoTime(); requestKeys[requestId] = key
                        val tileLeftLon = Mercator.tileXToLon(key.x, loadingScale); val tileTopLat = Mercator.tileYToLat(key.y, loadingScale)
                        val tileRightLon = Mercator.tileXToLon(key.x + 1, loadingScale); val tileBottomLat = Mercator.tileYToLat(key.y + 1, loadingScale)
                        WearCommandService.requestMapTile(context, requestId, minOf(tileTopLat, tileBottomLat), minOf(tileLeftLon, tileRightLon), maxOf(tileTopLat, tileBottomLat), maxOf(tileLeftLon, tileRightLon), loadingScale, navState.routerType, effectivePoiMask)
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier.then(modifier).fillMaxSize().clipToBounds().background(if (isAmbient) Color.Black else if (isDark) Color(0xFF1B1B1B) else Color(0xFFF1EEE8)).focusRequester(focusRequester)
            .onKeyEvent {
                if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_STEM_1) { showQuickMenu = true; true }
                else if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_STEM_2) {
                    val current = NavigationStateHolder.state.value
                    NavigationStateHolder.update(current.copy(isMapUnlocked = !current.isMapUnlocked, manualCenterLat = if (!current.isMapUnlocked) currentLat else current.manualCenterLat, manualCenterLon = if (!current.isMapUnlocked) currentLon else current.manualCenterLon, manualViewSpan = viewSpan, lastSettingsInteractionTime = System.currentTimeMillis()))
                    true
                } else false
            }
            .onRotaryScrollEvent {
                val currentState = NavigationStateHolder.state.value
                val factor = if (it.verticalScrollPixels > 0) 1.25f else 0.75f
                val currentSpan = if (currentState.isMapUnlocked) currentState.manualViewSpan else viewSpan
                val newSpan = (currentSpan * factor).coerceIn(0.0001f, 0.05f)
                NavigationStateHolder.update(currentState.copy(isMapUnlocked = true, isMapUnlockedBeforeNav = currentState.isMapUnlocked, manualViewSpan = newSpan, manualCenterLat = if (currentState.manualCenterLat == 0.0) currentLat else currentState.manualCenterLat, manualCenterLon = if (currentState.manualCenterLon == 0.0) currentLon else currentState.manualCenterLon, lastSettingsInteractionTime = System.currentTimeMillis()))
                true
            }
            .pointerInput(navState.isMapUnlocked, navState.isRouteBuilding) {
                if (navState.isMapUnlocked && !navState.isRouteBuilding) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val currentState = NavigationStateHolder.state.value
                        val currentSpan = currentState.manualViewSpan
                        val newSpan = (currentSpan / zoom).coerceIn(0.0001f, 0.05f)
                        val latStep = (pan.y / size.height) * (currentSpan * 2); val lonStep = -(pan.x / size.width) * (currentSpan * 2)
                        NavigationStateHolder.update(currentState.copy(manualViewSpan = newSpan, manualCenterLat = currentState.manualCenterLat + latStep.toDouble(), manualCenterLon = currentState.manualCenterLon + lonStep.toDouble(), lastSettingsInteractionTime = System.currentTimeMillis()))
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { showQuickMenu = true },
                    onTap = { offset ->
                        val currentState = NavigationStateHolder.state.value
                        if (!currentState.isRouteBuilding) {
                            val curViewSpan = if (currentState.isMapUnlocked) currentState.manualViewSpan else viewSpan
                            val cLat = if (currentState.isMapUnlocked) currentState.manualCenterLat else currentLat
                            val cLon = if (currentState.isMapUnlocked) currentState.manualCenterLon else currentLon
                            val offsetValPx = verticalOffsetFraction * size.height
                            val rotationRad = Math.toRadians(sensorViewModel.mapRotationAnimatable.value.toDouble())
                            val cosR = cos(rotationRad).toFloat(); val sinR = sin(rotationRad).toFloat()
                            val relX = offset.x - size.width / 2; val relY = offset.y - size.height / 2
                            val unRotX = relX * cosR + relY * sinR; val unRotY = -relX * sinR + relY * cosR
                            val dx = unRotX / size.width * (curViewSpan * 2); val dy = (unRotY - offsetValPx) / size.height * (curViewSpan * 2)
                            val tappedLat = cLat - dy; val tappedLon = cLon + dx
                            
                            val density = context.resources.displayMetrics.density
                            val tapRadiusPx = 20f * density
                            var nearestPoi: MapFeaturePoint? = null
                            var minDistPx = tapRadiusPx
                            val curSpanVal = (abs(Mercator.latToY(cLat) - Mercator.latToY(cLat + curViewSpan)) * 2.0).coerceAtLeast(1e-9)

                            MapTileStateHolder.getAllCachedTiles().forEach { tile ->
                                val tx = ((Mercator.lonToX(tappedLon) - (tile.mercatorX - tile.mercatorSpan / 2.0)) / tile.mercatorSpan * 1000.0).toFloat()
                                val ty = ((Mercator.latToY(tappedLat) - (tile.mercatorY - tile.mercatorSpan / 2.0)) / tile.mercatorSpan * 1000.0).toFloat()
                                tile.pointsByType.values.flatten().forEach { poi ->
                                    val dist = hypot(poi.x - tx, poi.y - ty)
                                    val screenDist = dist * (tile.mercatorSpan / curSpanVal * size.height / 1000f)
                                    if (screenDist < minDistPx) {
                                        if (nearestPoi == null || (poi.name.isNotEmpty() && nearestPoi!!.name.isEmpty())) { minDistPx = screenDist.toFloat(); nearestPoi = poi }
                                        else if (poi.name.isEmpty() == nearestPoi!!.name.isEmpty() && screenDist < minDistPx) { minDistPx = screenDist.toFloat(); nearestPoi = poi }
                                    }
                                }
                            }

                            val resultItem = if (nearestPoi != null && app.isFullyInitialized) {
                                val mapObject = Framework.nativeGetMapObjectForLocation(tappedLat, tappedLon)
                                SearchResultItem(
                                    name = mapObject?.title ?: nearestPoi!!.name,
                                    description = if (mapObject?.subtitle?.isNotEmpty() == true) mapObject.subtitle else nearestPoi!!.iconName,
                                    lat = tappedLat, lon = tappedLon, type = 2,
                                    openingHours = mapObject?.getMetadata(Metadata.MetadataType.FMD_OPEN_HOURS) ?: "",
                                    website = mapObject?.getMetadata(Metadata.MetadataType.FMD_WEBSITE) ?: "",
                                    phone = mapObject?.getMetadata(Metadata.MetadataType.FMD_PHONE_NUMBER) ?: "",
                                    address = mapObject?.address ?: "",
                                    cuisine = mapObject?.getMetadata(Metadata.MetadataType.FMD_CUISINE) ?: "",
                                    operator = mapObject?.getMetadata(Metadata.MetadataType.FMD_OPERATOR) ?: "",
                                    brand = mapObject?.getMetadata(Metadata.MetadataType.FMD_BRAND) ?: "",
                                    stars = mapObject?.getMetadata(Metadata.MetadataType.FMD_STARS) ?: ""
                                )
                            } else if (app.isFullyInitialized) {
                                val mapObject = Framework.nativeGetMapObjectForLocation(tappedLat, tappedLon)
                                if (mapObject != null) SearchResultItem(name = mapObject.title, description = if (mapObject.subtitle.isNotEmpty()) mapObject.subtitle else "Dropped Pin", lat = tappedLat, lon = tappedLon, type = 2, openingHours = mapObject.getMetadata(Metadata.MetadataType.FMD_OPEN_HOURS), website = mapObject.getMetadata(Metadata.MetadataType.FMD_WEBSITE), phone = mapObject.getMetadata(Metadata.MetadataType.FMD_PHONE_NUMBER), address = mapObject.address, cuisine = mapObject.getMetadata(Metadata.MetadataType.FMD_CUISINE), operator = mapObject.getMetadata(Metadata.MetadataType.FMD_OPERATOR), brand = mapObject.getMetadata(Metadata.MetadataType.FMD_BRAND), stars = mapObject.getMetadata(Metadata.MetadataType.FMD_STARS))
                                else SearchResultItem(name = "Dropped Pin", description = "", lat = tappedLat, lon = tappedLon, type = 2)
                            } else SearchResultItem(name = "Dropped Pin", description = "", lat = tappedLat, lon = tappedLon, type = 2)
                            tappedDestination = resultItem
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val routeToDraw by remember { derivedStateOf { if (navState.isActive && navState.routePoints.isNotEmpty()) navState.routePoints else emptyList() } }
        val curX = Mercator.lonToX(currentLon); val curY = Mercator.latToY(currentLat)
        val topY = Mercator.latToY(currentLat + clampedViewSpan); val curSpan = (abs(curY - topY) * 2.0).coerceAtLeast(1e-9)
        val visibleTiles by remember(allTiles, curX, curY, curSpan) { derivedStateOf { val threshold = curSpan * 1.5; allTiles.filter { abs(it.mercatorX - curX) < threshold && abs(it.mercatorY - curY) < threshold } } }

        Canvas(modifier = Modifier.fillMaxSize()) {
            LabelOverlapManager.clear(size.width, size.height)
            val offsetValPx = verticalOffsetFraction * size.height
            withTransform({ translate(top = offsetValPx); rotate(sensorViewModel.mapRotationAnimatable.value, pivot = Offset(size.width / 2, size.height / 2)) }) {
                val bgOrder = listOf(9, 3, 2)
                bgOrder.forEach { type -> visibleTiles.forEach { tile -> DrawPassInternal(tile, curX, curY, curSpan, 1, isAmbient, clampedViewSpan, isDark, this, emptyMap(), context.resources.displayMetrics.density, type) } }
                visibleTiles.forEach { tile -> DrawPassInternal(tile, curX, curY, curSpan, 2, isAmbient, clampedViewSpan, isDark, this, emptyMap(), context.resources.displayMetrics.density) }
                visibleTiles.forEach { tile -> DrawPassInternal(tile, curX, curY, curSpan, 4, isAmbient, clampedViewSpan, isDark, this, poiIcons, context.resources.displayMetrics.density) }

                if (routeToDraw.isNotEmpty()) {
                    withTransform({ translate(size.width / 2, size.height / 2) }) {
                        val routePath = Path()
                        routeToDraw.forEachIndexed { i, (lat, lon) ->
                            val rx = ((Mercator.lonToX(lon) - curX) / curSpan * size.height).toFloat(); val ry = ((Mercator.latToY(lat) - curY) / curSpan * size.height).toFloat()
                            if (i == 0) routePath.moveTo(rx, ry) else routePath.lineTo(rx, ry)
                        }
                        drawPath(path = routePath, color = Color(0x33000000), style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        drawPath(path = routePath, color = if (isAmbient) Color.White else Color(0xFF249CF2), style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        if (!isAmbient && navState.isActive && navState.turnLat != 0.0 && routeToDraw.size >= 2) {
                            var bestTurnIdx = 0; var minDist = Double.MAX_VALUE
                            for (i in routeToDraw.indices) {
                                val d = hypot(Mercator.lonToX(routeToDraw[i].second) - Mercator.lonToX(navState.turnLon), Mercator.latToY(routeToDraw[i].first) - Mercator.latToY(navState.turnLat))
                                if (d < minDist) { minDist = d; bestTurnIdx = i }
                            }
                            val startIdx = (bestTurnIdx - 5).coerceAtLeast(0); val endIdx = (bestTurnIdx + 5).coerceAtMost(routeToDraw.size - 1); val segment = routeToDraw.subList(startIdx, endIdx + 1)
                            if (segment.size >= 2) {
                                val turnPath = Path(); segment.forEachIndexed { i, (lat, lon) -> val rx = ((Mercator.lonToX(lon) - curX) / curSpan * size.height).toFloat(); val ry = ((Mercator.latToY(lat) - curY) / curSpan * size.height).toFloat(); if (i == 0) turnPath.moveTo(rx, ry) else turnPath.lineTo(rx, ry) }
                                val turnColor = Color(0xFFFFC30A)
                                drawPath(path = turnPath, color = Color.White, style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                                drawPath(path = turnPath, color = turnColor, style = Stroke(width = 10.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                                val lastP = segment.last(); val prevP = segment[segment.size - 2]
                                val lx = ((Mercator.lonToX(lastP.second) - curX) / curSpan * size.height).toFloat(); val ly = ((Mercator.latToY(lastP.first) - curY) / curSpan * size.height).toFloat()
                                val px = ((Mercator.lonToX(prevP.second) - curX) / curSpan * size.height).toFloat(); val py = ((Mercator.latToY(prevP.first) - curY) / curSpan * size.height).toFloat()
                                val angle = atan2((ly - py).toDouble(), (lx - px).toDouble()).toFloat()
                                withTransform({ translate(lx, ly); rotate(Math.toDegrees(angle.toDouble()).toFloat() + 90f) }) {
                                    val tipPath = Path().apply { moveTo(0f, -16.dp.toPx()); lineTo(-13.dp.toPx(), 10.dp.toPx()); lineTo(13.dp.toPx(), 10.dp.toPx()); close() }
                                    drawPath(tipPath, Color.White, style = Stroke(width = 4.dp.toPx(), join = StrokeJoin.Round)); drawPath(tipPath, turnColor)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        if (navState.isActive && navState.distToTurn.isNotEmpty() && !navState.isMapUnlocked) {
            Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 35.dp).background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(30.dp)) { Icon(painter = painterResource(id = NavigationIcons.getTurnIcon(navState.carDirection, navState.pedestrianDirection, navState.exitNum)), contentDescription = null, modifier = Modifier.fillMaxSize(), tint = Color.White) }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = navState.distToTurn, style = MaterialTheme.typography.title3.copy(fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = Color.White)
                        if (navState.nextStreet.isNotEmpty()) Text(text = navState.nextStreet, style = MaterialTheme.typography.caption2.copy(fontSize = 10.sp), color = Color.White.copy(alpha = 0.9f), maxLines = 1)
                    }
                }
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val offsetValPx = verticalOffsetFraction * size.height
            val userScreenX: Float; val userScreenY: Float
            if (navState.isMapUnlocked) {
                val rawDx = ((Mercator.lonToX(lastValidLon) - curX) / curSpan * size.height).toFloat(); val rawDy = ((Mercator.latToY(lastValidLat) - curY) / curSpan * size.height).toFloat()
                val rotationRad = Math.toRadians(sensorViewModel.mapRotationAnimatable.value.toDouble()); val cosR = cos(rotationRad).toFloat(); val sinR = sin(rotationRad).toFloat()
                val rotatedDx = rawDx * cosR - rawDy * sinR; val rotatedDy = rawDx * sinR + rawDy * cosR
                userScreenX = size.width / 2 + rotatedDx; userScreenY = size.height / 2 + offsetValPx + rotatedDy
            } else { userScreenX = size.width / 2; userScreenY = size.height / 2 + offsetValPx }
            if (userScreenX in -100f..(size.width + 100f) && userScreenY in -100f..(size.height + 100f)) {
                val arrowBlue = if (isDark) Color(0xFF1E88E5) else Color(0xFF249CF2) 
                withTransform({ translate(userScreenX, userScreenY); rotate(if (navState.isMapUnlocked) compassHeading + sensorViewModel.mapRotationAnimatable.value else 0f) }) {
                    val arrowPath = Path().apply { moveTo(0f, -14.dp.toPx()); lineTo(-8.5.dp.toPx(), 4.dp.toPx()); lineTo(0f, 0.dp.toPx()); lineTo(8.5.dp.toPx(), 4.dp.toPx()); close() }
                    drawPath(arrowPath, Color.White, style = Stroke(width = 2.8.dp.toPx(), join = StrokeJoin.Round)); drawPath(arrowPath, arrowBlue)
                }
            }
        }

        if (navState.isMapUnlocked) {
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (navState.isRouteBuilt && !navState.isNavigating) 60.dp else 24.dp)) {
                androidx.wear.compose.material.CompactChip(onClick = { NavigationStateHolder.update { it.copy(isMapUnlocked = false, lastSettingsInteractionTime = System.currentTimeMillis()) } }, label = { Text("Recenter") }, icon = { Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp)) }, colors = ChipDefaults.secondaryChipColors())
            }
        }

        if (effectivelyStandalone && navState.lat == 0.0 && !isAmbient) {
            Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(modifier = Modifier.width(6.dp)); Text("Searching for GPS...", style = MaterialTheme.typography.caption3, color = Color.White) }
            }
        }
        
        if (navState.isRouteBuilt && !navState.isNavigating) {
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (navState.isMapUnlocked) 60.dp else 24.dp)) {
                androidx.wear.compose.material.Button(onClick = { RoutingController.get().start() }, modifier = Modifier.height(40.dp).fillMaxWidth(0.5f), colors = ButtonDefaults.primaryButtonColors()) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.PlayArrow, contentDescription = null); Spacer(modifier = Modifier.width(4.dp)); Text("Start") }
                }
            }
        }
        
        if (showQuickMenu) QuickMenu(currentLat = currentLat, currentLon = currentLon, viewSpan = viewSpan, onDismiss = { showQuickMenu = false })
        if (tappedDestination != null) {
            Dialog(showDialog = true, onDismissRequest = { tappedDestination = null }) {
                PlacePage(result = tappedDestination!!, onNavigate = { routerType, avoidTolls, avoidMotorways, avoidFerries, avoidUnpaved ->
                    scope.launch {
                        if (navState.standaloneMode || (!navState.isPhoneConnected && navState.watchLocalMode)) {
                            if (app.isFullyInitialized && !Framework.nativeIsDownloadedMapAtLocation(tappedDestination!!.lat, tappedDestination!!.lon)) { android.widget.Toast.makeText(context, "Map not downloaded for destination", android.widget.Toast.LENGTH_LONG).show(); return@launch }
                            try {
                                app.waitForInitializationSuspend()
                                NavigationStateHolder.update { it.copy(isActive = true, isNavigating = false, routeBuildProgress = 0, isRouteBuilding = true, isRouteReady = false, routePoints = emptyList(), lastRouteError = 0, isMapUnlockedBeforeNav = it.isMapUnlocked, isMapUnlocked = true) }
                                val startPoint = app.organicMaps.locationHelper.myPosition ?: app.organicMaps.locationHelper.savedLocation?.let { MapObject.createMapObject(MapObject.MY_POSITION, "My Location", "", it.latitude, it.longitude) } ?: let { val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE); val lastLat = prefs.getFloat("last_known_lat", 0f).toDouble(); val lastLon = prefs.getFloat("last_known_lon", 0f).toDouble(); if (lastLat != 0.0) MapObject.createMapObject(MapObject.MY_POSITION, "Previous Fix", "", lastLat, lastLon) else null }
                                if (startPoint == null) { android.util.Log.e("MapPanel", "No GPS position for routing"); android.widget.Toast.makeText(context, "No GPS position for routing", android.widget.Toast.LENGTH_LONG).show(); NavigationStateHolder.update { it.copy(isRouteBuilding = false) }; return@launch }
                                val destination = MapObject.createMapObject(MapObject.POI, tappedDestination!!.name, tappedDestination!!.description, tappedDestination!!.lat, tappedDestination!!.lon)
                                val router = when (routerType) { 0 -> Router.Vehicle; 1 -> Router.Pedestrian; 2 -> Router.Bicycle; else -> Router.Transit }; val controller = RoutingController.get(); controller.prepare(startPoint, destination, router); controller.checkAndBuildRoute()
                                NavigationStateHolder.update { it.copy(distToTurn = "", nextStreet = "", distToTarget = "", eta = 0, completionPercent = 0.0, turnLat = 0.0, turnLon = 0.0, isMapUnlocked = false, avoidTolls = avoidTolls, avoidMotorways = avoidMotorways, avoidFerries = avoidFerries, avoidUnpaved = avoidUnpaved) }
                            } catch (e: Exception) { android.util.Log.e("MapPanel", "Route planning failed: ${e.message}"); android.widget.Toast.makeText(context, "Routing failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show(); NavigationStateHolder.update { it.copy(isRouteBuilding = false) } }
                        } else { WearCommandService.selectSearchResult(context, tappedDestination!!, routerType); NavigationStateHolder.update { it.copy(isActive = true, isNavigating = false, destinationName = tappedDestination!!.name, isMapUnlockedBeforeNav = it.isMapUnlocked, isMapUnlocked = true, isRouteBuilding = true) } }
                        tappedDestination = null
                    }
                }, onDismiss = { val state = NavigationStateHolder.state.value; if (state.isRouteBuilding || (state.isActive && !state.isNavigating)) { RoutingController.get().cancel(); NavigationStateHolder.update(state.copy(isRouteBuilding = false, isActive = false, isMapUnlocked = state.isMapUnlockedBeforeNav), force = true) } else { NavigationStateHolder.update(state.copy(isRouteBuilding = false), force = true) }; tappedDestination = null })
            }
        }
    }
}

@Composable
fun QuickMenu(currentLat: Double, currentLon: Double, viewSpan: Float, onDismiss: () -> Unit) {
    androidx.wear.compose.material.dialog.Dialog(showDialog = true, onDismissRequest = onDismiss) {
        val navState by NavigationStateHolder.state.collectAsState(); val context = LocalContext.current
        ScalingLazyColumn(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp, start = 8.dp, end = 8.dp)) {
            item { Text("Quick Menu", style = MaterialTheme.typography.caption1, color = Color(0xFF00E5FF)) }
            item { Chip(onClick = { val newSpan = (viewSpan * 0.75f).coerceAtLeast(0.0001f); NavigationStateHolder.update(navState.copy(isMapUnlocked = true, manualViewSpan = newSpan, manualCenterLat = if (!navState.isMapUnlocked) currentLat else navState.manualCenterLat, manualCenterLon = if (!navState.isMapUnlocked) currentLon else navState.manualCenterLon, lastSettingsInteractionTime = System.currentTimeMillis())); onDismiss() }, label = { Text("Zoom In") }, icon = { Icon(Icons.Default.Add, contentDescription = null) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), colors = ChipDefaults.secondaryChipColors()) }
            item { Chip(onClick = { val newSpan = (viewSpan * 1.33f).coerceAtMost(0.05f); NavigationStateHolder.update(navState.copy(isMapUnlocked = true, manualViewSpan = newSpan, manualCenterLat = if (!navState.isMapUnlocked) currentLat else navState.manualCenterLat, manualCenterLon = if (!navState.isMapUnlocked) currentLon else navState.manualCenterLon, lastSettingsInteractionTime = System.currentTimeMillis())); onDismiss() }, label = { Text("Zoom Out") }, icon = { Icon(Icons.Default.Remove, contentDescription = null) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), colors = ChipDefaults.secondaryChipColors()) }
            item { Chip(onClick = { NavigationStateHolder.update { it.copy(isMapUnlocked = false, lastSettingsInteractionTime = System.currentTimeMillis()) }; onDismiss() }, label = { Text("Follow Position") }, icon = { Icon(Icons.Default.MyLocation, contentDescription = null) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), colors = ChipDefaults.secondaryChipColors()) }
            item { ToggleChip(checked = !navState.isMapUnlocked, onCheckedChange = { newVal -> val current = NavigationStateHolder.state.value; NavigationStateHolder.update(current.copy(isMapUnlocked = !newVal, manualCenterLat = if (current.lat != 0.0) current.lat else currentLat, manualCenterLon = if (current.lon != 0.0) current.lon else currentLon, manualViewSpan = 0.003f, lastSettingsInteractionTime = System.currentTimeMillis())); onDismiss() }, label = { Text("Follow Position") }, toggleControl = { Switch(checked = !navState.isMapUnlocked) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) }
            item { Chip(onClick = onDismiss, label = { Text("Close") }, colors = ChipDefaults.primaryChipColors(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) }
        }
    }
}

fun DrawPassInternal(tile: ParsedMapTile, curX: Double, curY: Double, curSpan: Double, pass: Int, isAmbient: Boolean, viewSpan: Float, isDark: Boolean, drawScope: DrawScope, poiIcons: Map<String, Painter>, density: Float, specificType: Int? = null) {
    with(drawScope) {
        val screenPxPerMercator = size.width / curSpan; val tilePxWidth = tile.mercatorSpan * screenPxPerMercator; val tileScale = (tilePxWidth / 1000.0).toFloat()
        val offsetX = size.width / 2 + ((tile.mercatorX - curX) * screenPxPerMercator).toFloat()
        val offsetY = size.height / 2 + ((tile.mercatorY - curY) * screenPxPerMercator).toFloat()
        withTransform({ translate(offsetX, offsetY); scale(tileScale, tileScale, Offset.Zero); translate(-500f, -500f) }) {
            if (pass in 1..3) { drawIntoCanvas { canvas -> val margin = 30f / tileScale; canvas.clipRect(-margin, -margin, 1000f + margin, 1000f + margin); DrawTilePassInternal(tile, pass, isAmbient, viewSpan, isDark, this, poiIcons, tileScale, density, specificType, Offset(offsetX - 500f * tileScale, offsetY - 500f * tileScale)) } }
            else DrawTilePassInternal(tile, pass, isAmbient, viewSpan, isDark, this, poiIcons, tileScale, density, specificType, Offset(offsetX - 500f * tileScale, offsetY - 500f * tileScale))
        }
    }
}

fun DrawTilePassInternal(tile: ParsedMapTile, pass: Int, isAmbient: Boolean = false, viewSpan: Float = 0.003f, isDark: Boolean = false, drawScope: DrawScope, poiIcons: Map<String, Painter>, totalScale: Float, density: Float, specificType: Int? = null, tileScreenOffset: Offset = Offset.Zero) {
    with(drawScope) {
        val hideLabels = viewSpan > 0.015f
        val zoom = (log2(360.0 / (viewSpan.toDouble() * 2.0))).toFloat()
        val roadScale = when { zoom < 14f -> 0.45f; zoom < 16f -> 0.65f; zoom < 17.5f -> 0.85f; else -> 1.0f }

        if (pass == 1) {
            val areas = (tile.pathsByType[2] ?: emptyList()) + (tile.pathsByType[3] ?: emptyList()) + (tile.pathsByType[9] ?: emptyList())
            val sortedAreas = areas.sortedBy { it.priority }
            for (f in sortedAreas) {
                if (isAmbient) { drawPath(f.path, Color.Gray); continue }
                val nativeColor = if (f.color != 0) Color(f.color or 0xFF000000.toInt()) else null
                val color = nativeColor ?: when { tile.pathsByType[3]?.contains(f) == true -> if (isDark) Color(0xFF1F2D3D) else Color(0xFF90CAF9); tile.pathsByType[9]?.contains(f) == true -> if (isDark) Color(0xFF212D21) else Color(0xFFD4E3A9); else -> if (isDark) Color(0xFF2A2A2A) else Color(0xFFDEDBD0) }
                drawPath(f.path, color)
            }
        } else if (pass == 2) {
            val roadTypes = listOf(4, 5, 6, 7, 1, 8); val allRoads = roadTypes.flatMap { tile.pathsByType[it] ?: emptyList() }
            val sortedRoads = allRoads.sortedWith(compareBy({ it.layer }, { it.priority }))
            for (f in sortedRoads) {
                if (isAmbient) { drawPath(f.path, Color.White, style = Stroke(width = (4f * roadScale * density) / totalScale)); continue }
                if (f.casingWidth > 0 && f.casingColor != 0) { val casingWidth = (f.casingWidth.toFloat() * roadScale * density * 0.9f) / totalScale; if (casingWidth > 0.5f) drawPath(f.path, Color(f.casingColor or 0xFF000000.toInt()), style = Stroke(width = casingWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                val nativeWidth = if (f.width > 0) f.width.toFloat() else 1.5f; val nativeColor = if (f.color != 0) Color(f.color or 0xFF000000.toInt()) else Color.Gray
                val finalWidth = (nativeWidth * roadScale * density * 0.9f) / totalScale
                if (finalWidth > 0.3f) drawPath(f.path, nativeColor, style = Stroke(width = finalWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        } else if (pass == 4) {
            if (isAmbient || hideLabels) return
            if (viewSpan < 0.005f) {
                val buildingFeatures = tile.pathsByType[2] ?: emptyList()
                for (f in buildingFeatures) {
                    if (f.name.isEmpty()) continue
                    val label = if (f.name.contains("(")) f.name.substringAfter("(").substringBefore(")") else f.name
                    f.labelPos?.let { p ->
                        drawIntoCanvas { canvas ->
                            val textPaint = android.graphics.Paint().apply { color = if (isDark) android.graphics.Color.GRAY else android.graphics.Color.DKGRAY; textSize = (5.5.dp.toPx() * density / 2f) / totalScale; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true }; val textWidth = textPaint.measureText(label)
                            val screenX = tileScreenOffset.x + p.x * totalScale
                            val screenY = tileScreenOffset.y + p.y * totalScale
                            val textWidthPx = textWidth * totalScale
                            val bounds = Rect(screenX - textWidthPx/2, screenY - 5f * density, screenX + textWidthPx/2, screenY + 5f * density)
                            if (LabelOverlapManager.canDraw(bounds, density = density)) canvas.nativeCanvas.drawText(label, p.x, p.y + 2.dp.toPx() / totalScale, textPaint)
                        }
                    }
                }
            }
            val roadTypes = listOf(4, 5, 6, 7, 1, 8); val roadFeatures = roadTypes.flatMap { tile.pathsByType[it] ?: emptyList() }
            for (f in roadFeatures) {
                if (f.name.isEmpty()) continue
                drawIntoCanvas { canvas -> val textPaint = android.graphics.Paint().apply { this.color = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK; textSize = (7.5.dp.toPx() * density / 2f) / totalScale; isAntiAlias = true; typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); setShadowLayer(1.5.dp.toPx() / totalScale, 0f, 0f, if (isDark) android.graphics.Color.BLACK else android.graphics.Color.WHITE) }; val textWidth = textPaint.measureText(f.name); val pathMeasure = android.graphics.PathMeasure(f.path.asAndroidPath(), false); val pathLen = pathMeasure.length
                    if (pathLen > textWidth * 1.4f && viewSpan < 0.01f) {
                        val anchor = f.labelPos ?: Offset(500f, 500f)
                        val screenX = tileScreenOffset.x + anchor.x * totalScale
                        val screenY = tileScreenOffset.y + anchor.y * totalScale
                        val textWidthPx = textWidth * totalScale
                        val bounds = Rect(screenX - textWidthPx/2, screenY - 10f * density, screenX + textWidthPx/2, screenY + 10f * density)
                        if (LabelOverlapManager.canDraw(bounds, density = density)) canvas.nativeCanvas.drawTextOnPath(f.name, f.path.asAndroidPath(), (pathLen - textWidth)/2, 2.dp.toPx() / totalScale, textPaint)
                    }
                }
            }
            val poiSorted = tile.pointsByType.flatMap { it.value }.sortedByDescending { it.priority }
            for (f in poiSorted) {
                val p = Offset(f.x, f.y)
                val iconKey = when {
                    f.iconName.contains("restaurant") || f.iconName.contains("food") -> "food"
                    f.iconName.contains("cafe") -> "cafe"
                    f.iconName.contains("hotel") || f.iconName.contains("motel") -> "hotel"
                    f.iconName.contains("bank") -> "bank"
                    f.iconName.contains("atm") -> "atm"
                    f.iconName.contains("money") || f.iconName.contains("exchange") -> "money"
                    f.iconName.contains("parking") -> "parking"
                    f.iconName.contains("pharmacy") -> "pharmacy"
                    f.iconName.contains("hospital") || f.iconName.contains("clinic") -> "hospital"
                    f.iconName.contains("medicine") || f.iconName.contains("doctor") -> "medicine"
                    f.iconName.contains("bakery") -> "bakery"
                    f.iconName.contains("shop") || f.iconName.contains("supermarket") || f.iconName.contains("convenience") -> "shop"
                    f.iconName.contains("bench") -> "bench"
                    f.iconName.contains("fountain") -> "fountain"
                    f.iconName.contains("toilets") -> "toilets"
                    f.iconName.contains("drinking_water") -> "drinking_water"
                    f.iconName.contains("peak") || f.iconName.contains("mountain") -> "mountain"
                    f.iconName.contains("park") || f.iconName.contains("garden") || f.iconName.contains("forest") -> "park"
                    f.iconName.contains("airport") -> "airport"
                    f.iconName.contains("cinema") -> "cinema"
                    f.iconName.contains("theatre") -> "theatre"
                    f.iconName.contains("museum") || f.iconName.contains("art") || f.iconName.contains("gallery") -> "museum"
                    f.iconName.contains("church") || f.iconName.contains("cathedral") || f.iconName.contains("christian") -> "christianity"
                    f.iconName.contains("mosque") || f.iconName.contains("islam") -> "islam"
                    f.iconName.contains("synagogue") || f.iconName.contains("jewish") || f.iconName.contains("judaism") -> "judaism"
                    f.iconName.contains("school") || f.iconName.contains("college") || f.iconName.contains("university") -> "school"
                    f.iconName.contains("police") -> "police"
                    f.iconName.contains("postbox") || f.iconName.contains("mail") -> "mail"
                    f.iconName.contains("subway") || f.iconName.contains("metro") -> "subway"
                    f.iconName.contains("railway") || f.iconName.contains("train") -> "train"
                    f.iconName.contains("bus") -> "bus"
                    f.iconName.contains("tram") -> "tram"
                    f.iconName.contains("soccer") -> "soccer"
                    f.iconName.contains("tennis") -> "tennis"
                    f.iconName.contains("basketball") -> "basketball"
                    f.iconName.contains("sport") -> "sport"
                    f.iconName.contains("statue") || f.iconName.contains("monument") || f.iconName.contains("viewpoint") -> "sights"
                    else -> "none"
                }
                val icon = poiIcons[iconKey] ?: poiIcons["none"]; val pinSize = 10.dp.toPx() / totalScale;
                val screenX = tileScreenOffset.x + f.x * totalScale
                val screenY = tileScreenOffset.y + f.y * totalScale
                val pinSizePx = pinSize * totalScale
                val bounds = Rect(screenX - pinSizePx, screenY - pinSizePx, screenX + pinSizePx, screenY + pinSizePx)
                if (LabelOverlapManager.canDraw(bounds, density = density)) {
                    val color = when (iconKey) { "food", "cafe", "bakery" -> Color(0xFFE67E22); "money", "atm", "bank" -> Color(0xFF2ECC71); "parking" -> Color(0xFF3498DB); "hospital", "pharmacy", "medicine" -> Color(0xFFE74C3C); "christianity", "islam", "judaism", "museum", "sights" -> Color(0xFF9B59B6); "airport", "subway", "train", "bus", "tram" -> Color(0xFFF1C40F); "park" -> Color(0xFF27AE60); else -> Color(0xFF95A5A6) }
                    drawCircle(color = color, radius = pinSize, center = p); drawCircle(color = Color.White, radius = pinSize, center = p, style = Stroke(width = 1.5.dp.toPx() / totalScale))
                    if (icon != null) { val iconSizePx = 13.dp.toPx() / totalScale; withTransform({ translate(f.x - iconSizePx / 2f, f.y - iconSizePx / 2f) }) { with(icon) { draw(size = androidx.compose.ui.geometry.Size(iconSizePx, iconSizePx), colorFilter = ColorFilter.tint(Color.White)) } } }
                }
            }
        }
    }
}
