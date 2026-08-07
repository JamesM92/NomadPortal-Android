package com.jamesm92.nomadportal.data.messaging

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Anchor
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Maps a LXMF `FIELD_ICON_APPEARANCE` icon name to a Compose Material
 * Icons Extended [ImageVector], where we have a reasonable match.
 *
 * Real-world LXMF clients (Sideband — the LXMF library's own reference
 * client, confirmed directly against its own source:
 * `DEFAULT_APPEARANCE = ["account", [0,0,0,1], [1,1,1,1]]`) pick icon
 * names from Material Design Icons (MDI, materialdesignicons.com,
 * kebab/snake-case names like `"account"`, `"hiking"`, `"signal"`) — a
 * different catalog/namespace than Google's Material Icons Extended,
 * which this app already depends on (Apache-2.0, no new asset — see
 * build.gradle.kts). The two catalogs overlap substantially in *meaning*
 * even where naming differs, so this is a curated best-effort map
 * covering common everyday choices, not an exhaustive or automatic
 * translation — an unmapped name (or a name from Google's own newer,
 * differently-named Material *Symbols* catalog) falls back to an
 * initial-letter glyph, never a blank/broken icon (see
 * [com.jamesm92.nomadportal.ui.components.ContactAvatar]).
 *
 * Extending this table is always safe and non-breaking: an added entry
 * only ever upgrades an existing letter-glyph contact to a real icon.
 */
fun materialIconFor(glyphName: String): ImageVector? {
    val key = glyphName.trim().lowercase().replace(' ', '_').replace('-', '_')
    return ICON_APPEARANCE_MAP[key]
}

/** Names offered by the Home screen's own glyph editor — every key this
 * app can actually resolve to a real icon, sorted for a stable picker
 * order. Intentionally a subset of what [materialIconFor] *reads* (an
 * inbound contact's icon name can be anything; this is what the user is
 * offered to *pick* when setting their own).
 *
 * `by lazy` (not a plain `val`) because this file declares it before
 * [ICON_APPEARANCE_MAP] — Kotlin initializes top-level properties in
 * file order, so a direct reference here would read the map before it's
 * populated. */
val ICON_APPEARANCE_NAMES: List<String> by lazy { ICON_APPEARANCE_MAP.keys.sorted() }

private val ICON_APPEARANCE_MAP: Map<String, ImageVector> = mapOf(
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
)

/** '#rrggbb' -> [Color], tolerant of a missing '#'. Falls back to
 * [fallback] for anything unparseable — mirrors messaging.py's own
 * `_rgba_to_hex`/`_hex_to_rgba` defensiveness on the Python side. */
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
