package com.jamesm92.nomadportal.data.messaging

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The full, real Material Design Icons (MDI) catalog — ~7400 icons —
 * bundled as a single compact name-to-SVG-path-data JSON asset
 * (`assets/mdi_icons.json`, generated from the official `@mdi/js` npm
 * package, Apache-2.0 licensed — confirmed before bundling, cleanly
 * compatible with this app's own PolyForm Noncommercial license, no
 * copyleft/attribution conflict). Real MeshChat/Sideband users can pick
 * *any* MDI icon (confirmed against MeshChat's own source: its picker
 * is literally `Object.keys(mdi)` from `@mdi/js`), so [materialIconFor]'s
 * small hand-picked map alone — deliberately kept as the fast, zero-
 * parsing path for common names — can never cover every name an inbound
 * contact might actually be using. This is the fallback that closes
 * that gap for real, not by guessing more names to hand-pick, but by
 * having the actual data.
 *
 * Loaded once, lazily, off the main thread — [initialize] is called
 * once at app startup ([com.jamesm92.nomadportal.NomadPortalApp]) to
 * pre-warm it before it's likely to be needed. [get] is always safe to
 * call even before that finishes: it returns null until loaded, the
 * same as any other unresolved name — the next recomposition/poll
 * (every screen that renders a [ContactIcon.Appearance] already re-
 * fetches its data on a normal interval) picks the real icon up once
 * loading completes, rather than this ever blocking a composition.
 */
object MdiIconRepository {
    // name -> raw SVG path data ("d" attribute) — not parsed into an
    // ImageVector until first actually requested (see vectorCache),
    // so a ~7400-entry JSON parse doesn't also mean eagerly building
    // ~7400 ImageVectors nobody asked for.
    @Volatile private var paths: Map<String, String>? = null
    @Volatile private var sortedNames: List<String>? = null
    private val vectorCache = ConcurrentHashMap<String, ImageVector>()

    fun initialize(context: Context, scope: CoroutineScope) {
        if (paths != null) return
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) { load(appContext) }
    }

    @Synchronized
    private fun load(context: Context) {
        if (paths != null) return
        paths = try {
            val text = context.assets.open("mdi_icons.json").bufferedReader().use { it.readText() }
            val obj = JSONObject(text)
            val map = HashMap<String, String>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = obj.getString(key)
            }
            map
        } catch (e: Exception) {
            // Missing/corrupt asset shouldn't crash the app — every
            // lookup just falls back to a letter glyph instead, same as
            // any other genuinely-unmapped name.
            emptyMap()
        }
    }

    /** Null if [initialize] hasn't finished loading yet, or [name]
     * (already real MDI kebab-case — see [materialIconFor]'s own
     * doc comment for the normalization split) isn't a real icon. */
    fun get(name: String): ImageVector? {
        vectorCache[name]?.let { return it }
        val pathData = paths?.get(name) ?: return null
        val vector = buildVector(name, pathData)
        vectorCache[name] = vector
        return vector
    }

    /** True once [load] has actually populated [paths] (whether or not
     * the asset read itself succeeded) — lets a caller (the icon
     * picker) fall back to a smaller always-available list rather than
     * showing an empty screen during the brief startup window before
     * [initialize]'s background load finishes. */
    fun isLoaded(): Boolean = paths != null

    /** Every real MDI name this device has data for (~7400), sorted —
     * empty until [isLoaded]. Backs the icon picker's full catalog (see
     * `HomeScreen.kt`'s `IconAppearanceEditor`) so a user can pick
     * literally any icon a real MeshChat/Sideband contact might be
     * using, not just [ICON_APPEARANCE_MAP]'s curated ~180-name subset. */
    fun names(): List<String> {
        sortedNames?.let { return it }
        val current = paths ?: return emptyList()
        return current.keys.sorted().also { sortedNames = it }
    }

    /** MDI's SVGs all share a standard 24x24 viewBox — the same
     * convention Google's own Material Icons use, so no scaling
     * mismatch against the rest of this app's icons. [fill] is a
     * placeholder; every real call site renders via Compose's `Icon()`,
     * which always applies its own `tint` as a ColorFilter over
     * whatever's baked in here regardless. */
    private fun buildVector(name: String, pathData: String): ImageVector {
        val nodes = PathParser().parsePathString(pathData).toNodes()
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(pathData = nodes, fill = SolidColor(Color.Black)).build()
    }
}
