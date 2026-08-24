package com.jamesm92.nomadportal.data.browsing

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/** One entry in [DefaultFavoriteSites.ALL] — see that object's doc comment. */
data class DefaultFavoriteSite(
    val nodeHash: String,
    val displayName: String,
    /** Path under `assets/`, holding a real `.mu` snapshot of this site's
     * `/page/index.mu` captured at seed-list-authoring time (not
     * generated/fabricated — see this file's own doc comment). */
    val assetPath: String,
)

/**
 * A short, curated list of sites seeded as favorites (with an instantly-
 * available cached copy) on every fresh install — real, working sites the
 * user picked by hand (2026-08-23), not auto-discovered or arbitrary.
 * Deliberately small and explicit rather than config-driven: adding a site
 * later means capturing a real snapshot via [com.jamesm92.nomadportal.data.browsing.PageCacheStore]'s
 * own on-device cache (see the nomadportal-android-micron2compose-integration
 * memory's "read the raw cached .mu source on a real device" note for the
 * `adb run-as ... cat` recipe used to capture these two) and adding one
 * entry here — no other wiring needed.
 *
 * [seedInto] is called exactly once, from [com.jamesm92.nomadportal.nav.NomadNavHost]'s
 * onboarding-`onComplete` callback — **not** on every launch. That one-shot
 * call site is what makes "user can always defavorite them after start
 * up" hold: [BrowserRepository.seedDefaultFavorite] only ever runs here,
 * so a user who un-favorites a seeded default never gets it silently
 * re-favorited on a later launch.
 */
object DefaultFavoriteSites {
    val ALL: List<DefaultFavoriteSite> = listOf(
        DefaultFavoriteSite(
            nodeHash = "a96df95d57f7156032b15061c59fb842",
            displayName = "Mirador",
            assetPath = "default_sites/a96df95d57f7156032b15061c59fb842.mu",
        ),
        DefaultFavoriteSite(
            nodeHash = "8df9fffe5548121d15664a86dbfcca32",
            displayName = "Amber Pages",
            assetPath = "default_sites/8df9fffe5548121d15664a86dbfcca32.mu",
        ),
    )

    /**
     * Writes each site's bundled snapshot into [pageCacheStore] under
     * [identityId] (so it renders instantly on first open, same as any
     * other cache hit — see [PageCacheStore]'s own doc comment) and
     * favorites it via [BrowserRepository.seedDefaultFavorite] (not
     * [BrowserRepository.setFavorite], which declines a node this device
     * has never actually heard announce — every default site, by
     * definition, on a fresh install).
     *
     * Best-effort per site: a missing/unreadable asset or a favorite call
     * that fails shouldn't block the others or fail onboarding itself —
     * this is a nice-to-have first-launch convenience, not a step onboarding
     * completion should ever be gated on.
     */
    suspend fun seedInto(
        context: Context,
        browserRepository: BrowserRepository,
        pageCacheStore: PageCacheStore,
        identityId: String,
    ) {
        for (site in ALL) {
            try {
                val content = context.assets.open(site.assetPath).use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                }
                val address = PageAddress(nodeHash = site.nodeHash, path = "/page/index.mu")
                pageCacheStore.write(identityId, address, content)
            } catch (_: Exception) {
                // Missing/corrupt bundled asset shouldn't block the rest of
                // seeding — the favorite below can still succeed even
                // without a pre-cached copy, just without the instant paint.
            }

            try {
                browserRepository.seedDefaultFavorite(site.nodeHash, site.displayName)
            } catch (_: Exception) {
                // Best-effort, same reasoning as above.
            }
        }
    }
}
