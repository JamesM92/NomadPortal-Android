package com.jamesm92.nomadportal.data.messaging

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Anchor
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Bathtub
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DownhillSkiing
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Garage
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Icecream
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalPizza
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Sailing
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.SentimentDissatisfied
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Shower
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Snowboarding
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SportsFootball
import androidx.compose.material.icons.filled.SportsGolf
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.SportsTennis
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Surfing
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Maps a LXMF `FIELD_ICON_APPEARANCE` icon name to a Compose Material
 * Icons Extended [ImageVector], where we have a reasonable match.
 *
 * Real-world LXMF clients (Sideband, MeshChat) pick icon names from
 * Material Design Icons (MDI, materialdesignicons.com, kebab-case names
 * like `"account"`, `"hiking"`, `"weather-sunny"`) — a different
 * catalog/namespace than Google's Material Icons Extended, which this
 * app already depends on (Apache-2.0, no new asset — see
 * build.gradle.kts). MeshChat in particular offers its users the
 * *entire* `@mdi/js` package as a picker (confirmed directly against its
 * source, `src/frontend/components/profile/ProfileIconPage.vue`) —
 * several thousand icons — so this app can never resolve every possible
 * inbound name; the two catalogs overlap substantially in *meaning*
 * even where naming differs, so this remains a curated best-effort map,
 * not an exhaustive or automatic translation. An unmapped name falls
 * back to an initial-letter glyph, never a blank/broken icon (see
 * [com.jamesm92.nomadportal.ui.components.ContactAvatar]).
 *
 * Backs both directions: *reading* an inbound contact's icon name (so
 * more of what real MeshChat/Sideband users have actually picked
 * renders as a real glyph here, not a letter) and, via
 * [ICON_APPEARANCE_NAMES], *picking* this device's own icon — the
 * picker was originally a small curated subset of this map, widened to
 * the full map once the picker grew a search bar (see that property's
 * own doc comment) made browsing every entry practical.
 *
 * Extending this table is always safe and non-breaking: an added entry
 * only ever upgrades an existing letter-glyph contact to a real icon.
 */
fun materialIconFor(glyphName: String): ImageVector? {
    val key = glyphName.trim().lowercase().replace(' ', '_').replace('-', '_')
    return ICON_APPEARANCE_MAP[key]
}

/**
 * Names offered by the Home screen's own glyph editor. Was a small
 * fixed curated subset of [ICON_APPEARANCE_MAP]'s keys — reconsidered
 * per explicit follow-up direction once the editor grew a search bar
 * ("if were gonna have them all loaded we would just need to make them
 * searchable in our icon selector"): search makes browsing the *entire*
 * map viable, so there's no longer a reason to withhold the expanded
 * entries from the picker too. Every key in [ICON_APPEARANCE_MAP],
 * sorted for a stable list order — `by lazy` since this file declares
 * it before that map (Kotlin initializes top-level properties in file
 * order).
 */
val ICON_APPEARANCE_NAMES: List<String> by lazy { ICON_APPEARANCE_MAP.keys.sorted() }

private val ICON_APPEARANCE_MAP: Map<String, ImageVector> = mapOf(
    // --- Original curated 55 (also ICON_APPEARANCE_NAMES's exact source set) ---
    "account" to Icons.Filled.Person,
    "account_circle" to Icons.Filled.AccountCircle,
    "person" to Icons.Filled.Person,
    "face" to Icons.Filled.Face,
    "hiking" to Icons.Filled.Hiking,
    "directions_walk" to Icons.AutoMirrored.Filled.DirectionsWalk,
    "directions_run" to Icons.AutoMirrored.Filled.DirectionsRun,
    "directions_bike" to Icons.AutoMirrored.Filled.DirectionsBike,
    "directions_car" to Icons.Filled.DirectionsCar,
    "directions_boat" to Icons.Filled.DirectionsBoat,
    "home" to Icons.Filled.Home,
    "cabin" to Icons.Filled.Cabin,
    "terrain" to Icons.Filled.Terrain,
    "forest" to Icons.Filled.Forest,
    "park" to Icons.Filled.Park,
    "pets" to Icons.Filled.Pets,
    "star" to Icons.Filled.Star,
    "favorite" to Icons.Filled.Favorite,
    "wifi" to Icons.Filled.Wifi,
    "signal" to Icons.Filled.SignalCellularAlt,
    "router" to Icons.Filled.Router,
    "radio" to Icons.Filled.Radio,
    "bolt" to Icons.Filled.Bolt,
    "lock" to Icons.Filled.Lock,
    "shield" to Icons.Filled.Shield,
    "key" to Icons.Filled.Key,
    "mail" to Icons.Filled.Mail,
    "coffee" to Icons.Filled.Coffee,
    "local_cafe" to Icons.Filled.LocalCafe,
    "restaurant" to Icons.Filled.Restaurant,
    "campaign" to Icons.Filled.Campaign,
    "explore" to Icons.Filled.Explore,
    "map" to Icons.Filled.Map,
    "place" to Icons.Filled.Place,
    "public" to Icons.Filled.Public,
    "language" to Icons.Filled.Language,
    "science" to Icons.Filled.Science,
    "build" to Icons.Filled.Build,
    "code" to Icons.Filled.Code,
    "computer" to Icons.Filled.Computer,
    "smartphone" to Icons.Filled.Smartphone,
    "camera" to Icons.Filled.CameraAlt,
    "music_note" to Icons.Filled.MusicNote,
    "sports_esports" to Icons.Filled.SportsEsports,
    "anchor" to Icons.Filled.Anchor,
    "flight" to Icons.Filled.Flight,
    "train" to Icons.Filled.Train,
    "eco" to Icons.Filled.Eco,
    "flag" to Icons.Filled.Flag,
    "school" to Icons.Filled.School,
    "work" to Icons.Filled.Work,
    "medical_services" to Icons.Filled.MedicalServices,
    "security" to Icons.Filled.Security,
    "visibility" to Icons.Filled.Visibility,
    "sunny" to Icons.Filled.WbSunny,
    "cloud" to Icons.Filled.Cloud,
    "nightlight" to Icons.Filled.NightsStay,
    "ac_unit" to Icons.Filled.AcUnit,
    "whatshot" to Icons.Filled.Whatshot,

    // --- Expanded read-only coverage, per explicit request ("expand the
    // table for what can be loaded to match meshchat") — real MDI names
    // (materialdesignicons.com), not guessed English approximations,
    // mapped to the closest real Material Icons Extended equivalent.
    // Never offered by ICON_APPEARANCE_NAMES's own picker. ---
    // Weather
    "weather_sunny" to Icons.Filled.WbSunny,
    "weather_night" to Icons.Filled.NightsStay,
    "weather_cloudy" to Icons.Filled.Cloud,
    "weather_partly_cloudy" to Icons.Filled.Cloud,
    "weather_rainy" to Icons.Filled.Umbrella,
    "weather_pouring" to Icons.Filled.Umbrella,
    "weather_lightning" to Icons.Filled.Bolt,
    "weather_snowy" to Icons.Filled.AcUnit,
    "weather_windy" to Icons.Filled.Air,
    "snowflake" to Icons.Filled.AcUnit,
    "fire" to Icons.Filled.LocalFireDepartment,
    "water" to Icons.Filled.WaterDrop,
    "waves" to Icons.Filled.Waves,
    // Nature
    "leaf" to Icons.Filled.Eco,
    "tree" to Icons.Filled.Park,
    "flower" to Icons.Filled.LocalFlorist,
    // Animals
    "paw" to Icons.Filled.Pets,
    "dog" to Icons.Filled.Pets,
    "cat" to Icons.Filled.Pets,
    "spider" to Icons.Filled.Pets,
    // Transport
    "car" to Icons.Filled.DirectionsCar,
    "car_side" to Icons.Filled.DirectionsCar,
    "bike" to Icons.AutoMirrored.Filled.DirectionsBike,
    "walk" to Icons.AutoMirrored.Filled.DirectionsWalk,
    "run" to Icons.AutoMirrored.Filled.DirectionsRun,
    "run_fast" to Icons.AutoMirrored.Filled.DirectionsRun,
    "airplane" to Icons.Filled.Flight,
    "airplane_takeoff" to Icons.Filled.Flight,
    "train_car" to Icons.Filled.Train,
    "bus" to Icons.Filled.DirectionsBus,
    "sail_boat" to Icons.Filled.Sailing,
    "rocket" to Icons.Filled.Flight,
    "rocket_launch" to Icons.Filled.Flight,
    "taxi" to Icons.Filled.LocalTaxi,
    "truck" to Icons.Filled.LocalShipping,
    // Home/building
    "office_building" to Icons.Filled.Business,
    "store" to Icons.Filled.Store,
    "warehouse" to Icons.Filled.Warehouse,
    "garage" to Icons.Filled.Garage,
    "bed" to Icons.Filled.Bed,
    "sofa" to Icons.Filled.Weekend,
    "shower" to Icons.Filled.Shower,
    "bathtub" to Icons.Filled.Bathtub,
    "silverware_fork_knife" to Icons.Filled.Restaurant,
    // Food/drink
    "food" to Icons.Filled.Fastfood,
    "pizza" to Icons.Filled.LocalPizza,
    "cup" to Icons.Filled.LocalCafe,
    "beer" to Icons.Filled.LocalBar,
    "cake" to Icons.Filled.Cake,
    "cake_variant" to Icons.Filled.Cake,
    "ice_cream" to Icons.Filled.Icecream,
    // Communication
    "email" to Icons.Filled.Mail,
    "message" to Icons.AutoMirrored.Filled.Message,
    "chat" to Icons.AutoMirrored.Filled.Chat,
    "phone" to Icons.Filled.Phone,
    "cellphone" to Icons.Filled.Smartphone,
    "tablet" to Icons.Filled.Tablet,
    "laptop" to Icons.Filled.Laptop,
    "monitor" to Icons.Filled.Computer,
    "account_group" to Icons.Filled.Group,
    "account_multiple" to Icons.Filled.Groups,
    "forum" to Icons.Filled.Forum,
    // Tech
    "bluetooth" to Icons.Filled.Bluetooth,
    "router_wireless" to Icons.Filled.Router,
    "antenna" to Icons.Filled.SettingsInputAntenna,
    "microphone" to Icons.Filled.Mic,
    "video" to Icons.Filled.Videocam,
    "television" to Icons.Filled.Tv,
    "printer" to Icons.Filled.Print,
    "usb" to Icons.Filled.Usb,
    "sim" to Icons.Filled.SimCard,
    "memory" to Icons.Filled.Memory,
    "harddisk" to Icons.Filled.Storage,
    "keyboard" to Icons.Filled.Keyboard,
    "mouse" to Icons.Filled.Mouse,
    "headphones" to Icons.Filled.Headphones,
    "speaker" to Icons.Filled.Speaker,
    // Security/privacy
    "eye" to Icons.Filled.Visibility,
    "eye_off" to Icons.Filled.VisibilityOff,
    "fingerprint" to Icons.Filled.Fingerprint,
    "shield_lock" to Icons.Filled.Shield,
    // Health
    "medical_bag" to Icons.Filled.MedicalServices,
    "hospital_box" to Icons.Filled.LocalHospital,
    "stethoscope" to Icons.Filled.MedicalServices,
    "pill" to Icons.Filled.Medication,
    "needle" to Icons.Filled.Vaccines,
    "heart_pulse" to Icons.Filled.Favorite,
    "heart" to Icons.Filled.Favorite,
    // Sports
    "soccer" to Icons.Filled.SportsSoccer,
    "basketball" to Icons.Filled.SportsBasketball,
    "football" to Icons.Filled.SportsFootball,
    "tennis" to Icons.Filled.SportsTennis,
    "golf" to Icons.Filled.SportsGolf,
    "pool" to Icons.Filled.Pool,
    "swim" to Icons.Filled.Pool,
    "ski" to Icons.Filled.DownhillSkiing,
    "snowboard" to Icons.Filled.Snowboarding,
    "surfing" to Icons.Filled.Surfing,
    "trophy" to Icons.Filled.EmojiEvents,
    "medal" to Icons.Filled.MilitaryTech,
    // Emotions
    "emoticon_happy" to Icons.Filled.EmojiEmotions,
    "emoticon_sad" to Icons.Filled.SentimentDissatisfied,
    "emoticon_cool" to Icons.Filled.EmojiEmotions,
    // Time/money
    "clock" to Icons.Filled.AccessTime,
    "clock_outline" to Icons.Filled.AccessTime,
    "alarm" to Icons.Filled.Alarm,
    "calendar" to Icons.Filled.Event,
    "cash" to Icons.Filled.AttachMoney,
    "credit_card" to Icons.Filled.CreditCard,
    "bank" to Icons.Filled.AccountBalance,
    "wallet" to Icons.Filled.AccountBalanceWallet,
    "gift" to Icons.Filled.CardGiftcard,
    // Symbols/misc
    "compass" to Icons.Filled.Explore,
    "map_marker" to Icons.Filled.Place,
    "earth" to Icons.Filled.Public,
    "web" to Icons.Filled.Language,
    "flask" to Icons.Filled.Science,
    "diamond_stone" to Icons.Filled.Diamond,
    "robot" to Icons.Filled.SmartToy,
    "palette" to Icons.Filled.Palette,
    "brush" to Icons.Filled.Brush,
    "pencil" to Icons.Filled.Edit,
    "gamepad_variant" to Icons.Filled.SportsEsports,
    "controller_classic" to Icons.Filled.SportsEsports,
    "dumbbell" to Icons.Filled.FitnessCenter,
    "meditation" to Icons.Filled.SelfImprovement,
    "spa" to Icons.Filled.SelfImprovement,
    "wrench" to Icons.Filled.Build,
    "hammer" to Icons.Filled.Handyman,
    "cog" to Icons.Filled.Settings,
    "briefcase" to Icons.Filled.Work,
    "book" to Icons.AutoMirrored.Filled.MenuBook,
)

/** '#rrggbb' -> [Color], tolerant of a missing '#'. Falls back to
 * [fallback] for anything unparseable — mirrors messaging.py's own
 * `_rgba_to_hex`/`_hex_to_icon_bytes` defensiveness on the Python side. */
fun parseHexColor(hex: String?, fallback: Color): Color {
    if (hex.isNullOrBlank()) return fallback
    return try {
        Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
    } catch (e: IllegalArgumentException) {
        fallback
    }
}

/** [Color] -> '#rrggbb' (alpha dropped — FIELD_ICON_APPEARANCE's own
 * alpha channel is carried separately by messaging.py, always 1.0 from
 * this client). */
fun Color.toHexString(): String = String.format("#%06x", toArgb() and 0x00FFFFFF)
