package com.jamesm92.nomadportal.ui.browser

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jamesm92.micron2compose.compose.MicronPage
import com.jamesm92.micron2compose.parser.ConvertResult
import com.jamesm92.micron2compose.parser.LinkRun
import com.jamesm92.micron2compose.parser.LinkTarget
import com.jamesm92.micron2compose.parser.MicronConverter
import com.jamesm92.micron2compose.parser.TextRun
import com.jamesm92.nomadportal.data.SettingsRepository
import com.jamesm92.nomadportal.data.browsing.BrowserRepository
import com.jamesm92.nomadportal.data.browsing.PageAddress
import com.jamesm92.nomadportal.data.browsing.PageCacheStore
import com.jamesm92.nomadportal.data.identity.IdentityRepository
import com.jamesm92.nomadportal.panicwipe.PanicWipe
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.components.CompactIconButton
import com.jamesm92.nomadportal.ui.components.HorizontalScrollIndicator
import com.jamesm92.nomadportal.ui.components.PanicWipeLogo
import com.jamesm92.nomadportal.ui.components.StatusDot
import com.jamesm92.nomadportal.ui.components.VerticalScrollIndicator
import com.jamesm92.nomadportal.ui.components.dismissKeyboardOnTap
import com.jamesm92.nomadportal.ui.theme.NomadAccent2
import com.jamesm92.nomadportal.ui.theme.NomadMono
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A short, blank/near-empty page still needs *some* width — a phone-width
 * floor, not zero. */
private val MIN_MICRON_CONTENT_WIDTH = 320.dp

// Pull-to-refresh tuning (see `pullDistance`'s own doc comment). Distance
// pulled past the top before release triggers a real refresh, in dp —
// noticeably more than touch slop so a normal "start scrolling" drag never
// accidentally fires a refresh; converted to px via density at the one
// call site that needs it (LayoutDirection/density aren't available at
// file scope for a plain top-level val).
private val PULL_REFRESH_THRESHOLD = 72.dp

// < 1 — the visual pull lags behind the raw finger movement (a real
// "rubber band," not 1:1 tracking), same purpose as any native
// pull-to-refresh's own resistance curve.
private const val PULL_RESISTANCE = 0.5f

private const val PULL_SNAP_BACK_MS = 200

/**
 * The actual page-content browser: address bar, back/forward (an in-screen
 * history stack, separate from Compose Navigation's own back stack — the
 * nav-level back button in the top bar returns to the node list, these
 * arrows move within this node's browsing history), and [MicronPage]
 * rendering (in [NomadMono], per porting-notes.md §5's Braille/box-drawing
 * glyph requirement — micron2compose exposes `fontFamily`/
 * `monospaceFontFamily` for exactly this since v0.1.0).
 *
 * **Link handling covers all three "Link activation safety" cases** from
 * the handoff doc:
 * - Internal (`hash://...`) links navigate within this screen's history.
 * - External (`http(s)://...`) and file-download (`LinkTarget.isFileDownload`)
 *   links both show [LinkWarningDialog] before activating — never silent.
 *
 * File-download confirmation shows the filename but doesn't actually fetch
 * anything yet on "Download" — real file transfer needs the RNS Link layer
 * from the core extraction (sequencing step 1, not started).
 */
/** Where a currently-displayed page's content actually came from — drives
 * BrowserScreen's top-bar cached/loading/status-dot indicator. See that
 * screen's own [BrowserScreen] doc comment for the full stale-while-
 * revalidate flow this backs. */
private enum class PageFetchStatus { REFRESHING, LIVE, FAILED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    repository: BrowserRepository,
    pageCacheStore: PageCacheStore,
    identityRepository: IdentityRepository,
    settingsRepository: SettingsRepository,
    startAddress: PageAddress,
    onBack: () -> Unit,
) {
    val pageCacheEnabledSetting by settingsRepository.pageCacheEnabled.collectAsState(initial = true)
    // Page cache is identity-scoped (per explicit direction — see
    // PageCacheStore's own doc comment) — activeIdentity stays null
    // until the active identity's actually known, same "nothing to key
    // the cache off of yet" reasoning as pageCacheEnabled below.
    val identities by identityRepository.identities().collectAsState(initial = emptyList())
    val activeIdentity = identities.find { it.isActive }
    // Real caching only actually happens once both the user setting is
    // on AND an active identity is known to key it by — either missing
    // one degrades to "always fetch live, nothing shown until it
    // resolves," today's original (pre-cache) behavior.
    val pageCacheEnabled = pageCacheEnabledSetting && activeIdentity != null
    val converter = remember { MicronConverter() }
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // Node list's displayName (announce-derived, same source NodeListScreen
    // shows) — used for the top bar title instead of the raw hash. Falls
    // back to a truncated hash if this node hasn't been discovered via an
    // announce yet (e.g. navigated to directly by hash, or the announce
    // hasn't arrived since app start).
    val nodes by repository.discoveredNodes().collectAsState(initial = emptyList())

    var history by remember { mutableStateOf(listOf(startAddress)) }
    var historyIndex by remember { mutableStateOf(0) }
    val currentAddress = history[historyIndex]

    var addressBarText by remember(currentAddress) { mutableStateOf(currentAddress.toUrl()) }
    var result by remember { mutableStateOf<ConvertResult?>(null) }
    var rawSource by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    // Stale-while-revalidate status (per explicit direction) — REFRESHING
    // shows a small top-bar spinner ("you're looking at a cached copy, a
    // live fetch is in flight"); LIVE/FAILED show a green/red StatusDot
    // once that fetch resolves, staying put until the next navigation.
    // null before the very first status is known for this address (no
    // dot yet) — distinct from FAILED, which specifically means "a real
    // attempt came back negative."
    var fetchStatus by remember { mutableStateOf<PageFetchStatus?>(null) }
    var pendingWarning by remember { mutableStateOf<PendingLinkWarning?>(null) }
    var scrollToAnchor by remember { mutableStateOf<String?>(null) }
    // "Raw" per the reference NomadPortal web app's own address-bar
    // checkbox — swaps the rendered MicronPage for the page's actual
    // unrendered .mu text, already fetched into `rawSource` below either
    // way, so this is purely a view toggle, no extra fetch.
    var showRawView by remember { mutableStateOf(false) }
    // "Identify to this node" — porting-notes.md §4's real "fingerprint"
    // feature (a previous build of this icon misread that as a hash-
    // viewer popup instead; see IdentifySession's own doc comment for
    // the actual spec and why this is session-scoped, not persisted).
    // Re-read per node so switching nodes reflects that node's own
    // remembered on/off state, not whatever the last-viewed node had.
    var identified by remember(currentAddress.nodeHash) {
        mutableStateOf(IdentifySession.isIdentified(currentAddress.nodeHash))
    }
    val currentNodeFavorited = nodes.find { it.hash == currentAddress.nodeHash }?.isFavorite ?: false

    fun navigateTo(address: PageAddress) {
        history = history.take(historyIndex + 1) + address
        historyIndex = history.lastIndex
    }

    // Committing the address bar's typed URL — the keyboard's own "Go"
    // IME action is the only trigger for this (a separate visible Go
    // button was tried and then explicitly removed again: the IME action
    // is enough, and the button's horizontal space was worth reclaiming
    // for the field itself).
    fun goToAddressBarUrl() {
        PageAddress.fromUrl(addressBarText)?.let(::navigateTo)
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    fun handleLink(target: LinkTarget) {
        when {
            // Checked first: a file-download link is still a real hash://
            // (or http(s)://) URL under the hood — isFileDownload is what
            // distinguishes it, not the URL's own prefix.
            target.isFileDownload ->
                pendingWarning = PendingLinkWarning.FileDownload(
                    url = target.url,
                    fileName = target.url.substringAfterLast('/'),
                )

            target.url.startsWith("http://") || target.url.startsWith("https://") ->
                pendingWarning = PendingLinkWarning.ExternalWeb(target.url)

            target.url.startsWith("hash://") -> {
                val address = PageAddress.fromUrl(target.url)
                if (address != null) navigateTo(address)
            }

            target.url.startsWith("#") && target.url.length > 1 ->
                scrollToAnchor = target.url.removePrefix("#")

            // Bare "#" (jump to the next heading after this link) — no
            // block-index-lookup for "next heading from here" is exposed
            // by ConvertResult, so this is a no-op rather than a partial
            // implementation. Low priority: cosmetic, not a safety gap.
            else -> Unit
        }
    }

    // Also keyed on `identified` — toggling it mid-view re-fetches so the
    // change actually takes effect on the next request, not just next
    // navigation.
    //
    // Stale-while-revalidate (per explicit direction): if a cached copy
    // of this address exists, it's shown immediately — no blank spinner
    // for a page this device has already fetched before — while a real
    // fetch for the current version still runs in the background
    // (unconditionally; caching being off/no-cache-available never skips
    // the real fetch, it only changes what's on screen while waiting for
    // it). A successful fetch always replaces the on-screen content with
    // the fresh copy and marks LIVE (green dot); a failed fetch leaves
    // whatever's already showing alone and marks FAILED (red dot) —
    // there's no reason to blank out a still-valid cached page just
    // because *this* refresh attempt didn't land. Only when there was
    // never anything to show at all (no cache, and the fetch itself
    // failed) does this fall through to the existing loadError state.
    // Shared by the initial LaunchedEffect fetch below AND by pull-to-
    // refresh (see the `result != null` content branch's own pointerInput
    // — "continuing to pull a page down should trigger a refresh," per
    // explicit direction) — one real fetch-and-apply-result path, not two
    // copies drifting apart. `alreadyShowingContent`: false only for the
    // very first fetch of a never-cached address (nothing on screen yet,
    // so a failure has to surface as the full-screen loadError state);
    // true for every other case (a cached copy already on screen, or a
    // pull-to-refresh of an already-live page) — a failure there just
    // marks FAILED and leaves whatever's already showing alone.
    suspend fun refreshLive(alreadyShowingContent: Boolean) {
        val identityId = activeIdentity?.id
        try {
            val source = repository.fetchPage(currentAddress, identify = identified)
            rawSource = source
            result = converter.convert(source, nodeHash = currentAddress.nodeHash, basePath = currentAddress.path)
            fetchStatus = PageFetchStatus.LIVE
            if (pageCacheEnabled && identityId != null) pageCacheStore.write(identityId, currentAddress, source)
        } catch (e: CancellationException) {
            // Real bug found from an on-device report ("Couldn't load
            // this page: the coroutine scope left the composition" shown
            // as a genuine error, while some other in-flight call still
            // went on to mark the page LIVE) — a classic Kotlin
            // coroutines footgun: CancellationException is structurally
            // an Exception, so the broad `catch (e: Exception)` below
            // was swallowing it and reporting a superseded/aborted fetch
            // (this LaunchedEffect relaunching on a key change, or the
            // screen leaving composition entirely) as a real load
            // failure. A cancellation was never a failure to begin with
            // — it must propagate, not get caught and turned into UI
            // state, so the *actual* still-running attempt (or nothing,
            // if the screen is really gone) is what determines what the
            // user sees next.
            throw e
        } catch (e: Exception) {
            if (alreadyShowingContent) {
                fetchStatus = PageFetchStatus.FAILED
            } else {
                loadError = e.message ?: "Failed to load page"
                fetchStatus = null
            }
        }
    }

    LaunchedEffect(currentAddress, identified, activeIdentity?.id) {
        loadError = null
        result = null
        rawSource = null
        fetchStatus = null

        val identityId = activeIdentity?.id
        var showingCache = false
        if (pageCacheEnabled && identityId != null) {
            pageCacheStore.read(identityId, currentAddress)?.let { cached ->
                rawSource = cached
                result = runCatching {
                    converter.convert(cached, nodeHash = currentAddress.nodeHash, basePath = currentAddress.path)
                }.getOrNull()
                if (result != null) {
                    showingCache = true
                    fetchStatus = PageFetchStatus.REFRESHING
                }
            }
        }

        refreshLive(alreadyShowingContent = showingCache)
    }

    pendingWarning?.let { warning ->
        LinkWarningDialog(
            warning = warning,
            onConfirm = {
                when (warning) {
                    is PendingLinkWarning.ExternalWeb -> uriHandler.openUri(warning.url)
                    // TODO(core extraction, sequencing step 1): actually
                    // fetch the file over an RNS Link once that layer
                    // exists. No-op for now — there's nothing to fetch
                    // from yet.
                    is PendingLinkWarning.FileDownload -> Unit
                }
                pendingWarning = null
            },
            onDismiss = { pendingWarning = null },
        )
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        topBar = {
            Column {
                AdaptiveTopAppBar(
                    title = {
                        val nodeName = nodes.find { it.hash == currentAddress.nodeHash }?.displayName
                            ?: (currentAddress.nodeHash.take(12) + "…")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(nodeName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            // Stale-while-revalidate status — a small
                            // spinner while a cached page's background
                            // refresh is still in flight, then a green
                            // (reached the live page) or red (refresh
                            // failed, still showing the cached copy)
                            // StatusDot once it resolves. Nothing shown at
                            // all before the first status is known (see
                            // PageFetchStatus's own doc comment).
                            when (fetchStatus) {
                                PageFetchStatus.REFRESHING -> {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                                PageFetchStatus.LIVE -> {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    // NomadAccent2 — same green already used
                                    // for "reachable"/"enabled" status dots
                                    // elsewhere (NetworkScreen's own
                                    // interface rows), not a new one-off hue.
                                    StatusDot(color = NomadAccent2, size = 8.dp)
                                }
                                PageFetchStatus.FAILED -> {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    StatusDot(color = MaterialTheme.colorScheme.error, size = 8.dp)
                                }
                                null -> Unit
                            }
                        }
                    },
                    navigationIcon = {
                        CompactIconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to sites")
                        }
                    },
                    actions = {
                        PanicWipeLogo(
                            modifier = Modifier.padding(end = 8.dp),
                            onTripleTap = {
                                scope.launch {
                                    PanicWipe.perform(context)
                                    PanicWipe.restartApp(context)
                                }
                            },
                        )
                    },
                )
                // Back/forward/Go/favorite/raw all sized down to "just
                // bigger than the icon they represent" — Material3's
                // IconButton/Button both reserve a much larger touch
                // target (48dp/40dp) by default regardless of the icon's
                // own size, which read as oversized here; zeroing
                // LocalMinimumInteractiveComponentSize for this row lets
                // an explicit small Modifier.size actually take effect
                // instead of being padded back out by that reservation.
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Vertical padding only — trimmed further in
                            // landscape per "header rows need to be as
                            // small as possible" when rotated. Not
                            // touching the text field's own height here:
                            // that's independently tuned to avoid
                            // clipping (see its own comment below).
                            .padding(horizontal = 4.dp, vertical = if (isLandscape) 1.dp else 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        IconButton(
                            onClick = { if (historyIndex > 0) historyIndex-- },
                            enabled = historyIndex > 0,
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back in history",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        IconButton(
                            onClick = { if (historyIndex < history.lastIndex) historyIndex++ },
                            enabled = historyIndex < history.lastIndex,
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Forward in history",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        // weight(1f) claims whatever every fixed-size
                        // sibling in this row doesn't — no separate Go
                        // button anymore (removed per explicit request:
                        // the IME's own Go action is enough, and dropping
                        // the button reclaims that space for the field).
                        //
                        // A plain OutlinedTextField(...).height(44.dp)
                        // still clipped the bottom of the text even at
                        // 44dp — the convenience composable doesn't
                        // expose its own internal content padding at all,
                        // so no amount of outer height/padding tuning on
                        // it can actually remove that padding, only make
                        // the box taller/shorter around it. CompactAddressField
                        // below is the low-level BasicTextField +
                        // OutlinedTextFieldDefaults.DecorationBox
                        // construction instead, which *does* expose
                        // `contentPadding` directly — set to almost
                        // nothing per request, and the field sizes itself
                        // to the text's real line height instead of a
                        // guessed fixed dp value.
                        CompactAddressField(
                            value = addressBarText,
                            onValueChange = { addressBarText = it },
                            modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
                            onGo = { goToAddressBarUrl() },
                        )
                        IconButton(
                            onClick = {
                                scope.launch {
                                    repository.setFavorite(currentAddress.nodeHash, !currentNodeFavorited)
                                }
                            },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = if (currentNodeFavorited) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = if (currentNodeFavorited) "Unfavorite this site" else "Favorite this site",
                                tint = if (currentNodeFavorited) MaterialTheme.colorScheme.primary else NomadTextDim,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        // "Raw" per the reference app's address-bar
                        // checkbox — toggles the rendered MicronPage for
                        // the page's actual unrendered .mu source (see
                        // `rawSource`/`showRawView` above).
                        IconButton(
                            onClick = { showRawView = !showRawView },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Code,
                                contentDescription = if (showRawView) "Show rendered page" else "Show raw source",
                                tint = if (showRawView) MaterialTheme.colorScheme.primary else NomadTextDim,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        // "Identify to this node" — the rightmost icon in
                        // the reference app's own address bar. Per
                        // porting-notes.md §4, this is a *toggle*: on
                        // sends this device's own RNS identity along with
                        // page requests to this specific node (identify()
                        // over the fetch Link), off browses anonymously
                        // — not a hash-viewer popup (a previous build of
                        // this icon misread the spec that way).
                        IconButton(
                            onClick = {
                                identified = !identified
                                IdentifySession.setIdentified(currentAddress.nodeHash, identified)
                            },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Fingerprint,
                                contentDescription = if (identified) {
                                    "Identifying to this site — tap to browse anonymously"
                                } else {
                                    "Browsing anonymously — tap to identify to this site"
                                },
                                tint = if (identified) MaterialTheme.colorScheme.primary else NomadTextDim,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding).dismissKeyboardOnTap()) {
            when {
                loadError != null -> Text(
                    text = "Couldn't load this page: $loadError",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
                // Checked ahead of the rendered-page branch below —
                // `showRawView` wins over `result != null` whenever both
                // are true, since toggling Raw is meant to *replace* the
                // rendered view, not sit alongside it.
                showRawView && rawSource != null -> Text(
                    text = rawSource!!,
                    fontFamily = NomadMono,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                )
                // Micron content is fixed-character-grid by design (box-
                // drawing art, tables) — MicronBlock (micron2compose)
                // already renders with softWrap=false, but its Text still
                // uses fillMaxWidth(), so without giving it more width
                // than the screen here, anything wider than the viewport
                // was just silently clipped with no way to see it.
                //
                // The width given here matters, not just "wide enough":
                // a blind oversized fixed width (2000.dp, tried first)
                // broke `` `c ``/`` `r `` (center/right-align) content —
                // e.g. a page's ASCII-art hero rendered centered relative
                // to that whole 2000dp canvas, landing far outside the
                // initial viewport and reading as "not showing up" (a
                // real reported bug, confirmed against a real page's raw
                // .mu source). The original NomadPortal web app doesn't
                // have this problem because CSS `white-space: pre` +
                // `overflow: auto` naturally shrink the scrollable area
                // to the widest actual line (verified in its
                // static/css/style.css) — Compose's LazyColumn (which
                // MicronPage uses internally) can't do that (no
                // intrinsic-measurement support for lazy lists), so this
                // measures the real widest line via TextMeasurer instead
                // and sizes to that — same effect, computed explicitly
                // rather than free from the layout system. Recomputed
                // whenever `result` changes; NomadMono is fixed-width so
                // one measurement per block covers that block's whole
                // line correctly regardless of which characters it uses.
                result != null -> {
                    val density = LocalDensity.current
                    val pullRefreshThresholdPx = with(density) { PULL_REFRESH_THRESHOLD.toPx() }
                    val textMeasurer = rememberTextMeasurer()
                    val listState = rememberLazyListState()
                    val horizontalScrollState = rememberScrollState()
                    // ~15% smaller than bodyMedium itself -- per explicit
                    // on-device feedback that rendered Micron page text
                    // ran a bit large. Still derived from a real type-
                    // scale role (not a freehand value), and still goes
                    // through the same `* scale` accessibility multiplier
                    // bodyMedium itself carries.
                    val bodyFontSize = MaterialTheme.typography.bodyMedium.fontSize * 0.85f
                    val contentWidth = remember(result, bodyFontSize) {
                        var maxWidthPx = 0
                        for (block in result!!.blocks) {
                            val text = block.runs.joinToString("") { run ->
                                when (run) {
                                    is TextRun -> run.text
                                    is LinkRun -> run.label
                                    else -> ""
                                }
                            }
                            if (text.isEmpty()) continue
                            val fontSize = when (block.headingLevel) {
                                1 -> 24.sp
                                2 -> 20.sp
                                3 -> 18.sp
                                else -> bodyFontSize
                            }
                            val width = textMeasurer.measure(
                                text = text,
                                style = TextStyle(fontFamily = NomadMono, fontSize = fontSize),
                            ).size.width
                            if (width > maxWidthPx) maxWidthPx = width
                        }
                        with(density) { maxWidthPx.toDp() }.coerceAtLeast(MIN_MICRON_CONTENT_WIDTH)
                    }

                    // Backs the fling launched below — a real on-device
                    // report ("swiping quickly should carry on a little
                    // way, not stop dead") against the manual
                    // dispatchRawDelta drag this Box already drives:
                    // Compose's own scrollable() gives fling for free,
                    // but this screen deliberately doesn't use it (see
                    // the pointerInput's own doc comment for why), so
                    // fling has to be driven by hand too, the same way
                    // the drag itself is. remember()'d at this level (not
                    // inside the gesture detector) so a fling from one
                    // gesture can be found and cancelled by the *next*
                    // gesture's very first down event, below.
                    var flingJob by remember { mutableStateOf<Job?>(null) }
                    // Pull-to-refresh (per explicit direction: "continuing
                    // to pull a page down should trigger a refresh, if you
                    // let it snap") — real overscroll distance in px past
                    // the top of listState, tracked by hand for the same
                    // reason fling above is: this screen's own custom
                    // dispatchRawDelta drag replaces Compose's built-in
                    // scrollable(), which is what normally gives
                    // pull-to-refresh integration for free. 0f = not
                    // pulling; > 0f drives both the pull indicator's own
                    // position (below) and, past pullRefreshThresholdPx on
                    // release, a real refreshLive() call.
                    var pullDistance by remember { mutableStateOf(0f) }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // enabled = false: horizontalScrollState still
                            // backs this modifier's layout offset, but its
                            // own gesture detection is off — driven
                            // manually below instead, see that pointerInput's
                            // doc comment for why.
                            .horizontalScroll(horizontalScrollState, enabled = false)
                            .pointerInput(horizontalScrollState, listState) {
                                // Real Micron pages are routinely both
                                // wider AND taller than the viewport
                                // (box-drawing art, wide tables) — the two
                                // scroll axes here are two separate
                                // scrollable() detectors at different
                                // composable depths (this outer
                                // horizontalScrollState vs. MicronPage's
                                // own internal LazyColumn), and Compose's
                                // per-orientation touch-slop locking means
                                // whichever axis's detector wins the
                                // initial slop race consumes the whole
                                // drag gesture, leaving the other axis
                                // dead for that entire continuous drag —
                                // a real on-device report ("can't swipe
                                // diagonally... only vertically or
                                // horizontally at a time"). Intercepting
                                // at PointerEventPass.Initial (before
                                // MicronPage's LazyColumn gets a chance to
                                // see the event at all) and driving both
                                // ScrollStates directly via
                                // dispatchRawDelta — the non-suspend API
                                // meant for exactly this "a custom drag
                                // detector programmatically drives scroll
                                // state" case, no coroutine-launch-per-
                                // pointer-move-event race — replaces that
                                // per-axis lock with genuine simultaneous
                                // 2-axis panning.
                                //
                                // Consumption only starts once movement
                                // clears real touch slop (below, mirrors
                                // how Compose's own scrollable() gesture
                                // detector distinguishes a drag from a
                                // tap) — a plain link tap under this Box
                                // routinely carries a pixel or two of
                                // sensor jitter, and consuming from the
                                // very first sub-slop movement would eat
                                // that tap before MicronPage's own link
                                // tap-detector (Main pass) ever saw a
                                // complete down+up sequence.
                                awaitEachGesture {
                                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                                    // A new touch always wins over whatever
                                    // the previous gesture's fling was
                                    // still doing — without this, the
                                    // still-running fling coroutine would
                                    // keep calling dispatchRawDelta in
                                    // parallel with this fresh drag,
                                    // fighting over the same scroll states.
                                    flingJob?.cancel()
                                    val pointerId = down.id
                                    val slop = viewConfiguration.touchSlop
                                    var dragging = false
                                    var accumX = 0f
                                    var accumY = 0f
                                    val velocityTracker = VelocityTracker()
                                    velocityTracker.addPosition(down.uptimeMillis, down.position)
                                    // Pull-to-refresh — see pullDistance's
                                    // own doc comment. Once any pull is
                                    // outstanding, further vertical motion
                                    // this gesture feeds entirely into the
                                    // pull (grow on drag-down, shrink on
                                    // drag-up) rather than the list, so the
                                    // two never fight over the same frame's
                                    // delta; only once pullDistance is back
                                    // to exactly 0 does vertical motion
                                    // resume driving listState normally.
                                    // Real overscroll only exists once
                                    // dispatchRawDelta can't consume the
                                    // full requested amount *at the top* of
                                    // the list — never at the bottom, where
                                    // this function is simply a no-op.
                                    fun applyVerticalDelta(dy: Float) {
                                        if (pullDistance > 0f) {
                                            pullDistance = (pullDistance + dy * PULL_RESISTANCE).coerceAtLeast(0f)
                                            return
                                        }
                                        val requested = -dy
                                        val consumed = listState.dispatchRawDelta(requested)
                                        val overscroll = requested - consumed
                                        // LazyListState (unlike ScrollState)
                                        // has no single scalar `.value` —
                                        // "at the very top" is item 0 with
                                        // zero scroll offset into it.
                                        val atTop = listState.firstVisibleItemIndex == 0 &&
                                            listState.firstVisibleItemScrollOffset == 0
                                        if (overscroll < 0f && atTop) {
                                            pullDistance = -overscroll * PULL_RESISTANCE
                                        }
                                    }
                                    while (true) {
                                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                        if (!change.pressed) break
                                        if (!change.positionChanged()) continue
                                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                                        val delta = change.positionChange()
                                        if (dragging) {
                                            change.consume()
                                            horizontalScrollState.dispatchRawDelta(-delta.x)
                                            applyVerticalDelta(delta.y)
                                        } else {
                                            accumX += delta.x
                                            accumY += delta.y
                                            if (kotlin.math.abs(accumX) > slop || kotlin.math.abs(accumY) > slop) {
                                                dragging = true
                                                change.consume()
                                                horizontalScrollState.dispatchRawDelta(-accumX)
                                                applyVerticalDelta(accumY)
                                            }
                                        }
                                    }
                                    // Pull-to-refresh release — "let it
                                    // snap": past the threshold, a real
                                    // refresh fires; either way the pull
                                    // indicator eases back to 0 rather than
                                    // vanishing instantly. Takes priority
                                    // over fling below — a released pull
                                    // gesture was, by definition, at the
                                    // very top of the list with no fling-
                                    // worthy vertical motion left to carry.
                                    if (pullDistance > 0f) {
                                        val shouldRefresh = pullDistance > pullRefreshThresholdPx
                                        scope.launch {
                                            if (shouldRefresh) {
                                                fetchStatus = PageFetchStatus.REFRESHING
                                                refreshLive(alreadyShowingContent = true)
                                            }
                                        }
                                        scope.launch {
                                            val anim = Animatable(pullDistance)
                                            anim.animateTo(0f, animationSpec = tween(PULL_SNAP_BACK_MS)) {
                                                pullDistance = value
                                            }
                                        }
                                        return@awaitEachGesture
                                    }
                                    // Fling — only a real drag that
                                    // actually panned content earns one; a
                                    // tap/link-click's sub-slop jitter has
                                    // no meaningful velocity to carry
                                    // forward anyway.
                                    if (dragging) {
                                        val velocity = velocityTracker.calculateVelocity()
                                        flingJob = scope.launch {
                                            // Two independent 1D decays
                                            // (one per axis) running
                                            // concurrently — a real 2D
                                            // fling, not just "whichever
                                            // axis had more speed."
                                            // Animatable's own `block`
                                            // callback hands back
                                            // cumulative decayed distance
                                            // each frame; only the *delta*
                                            // since the last frame is what
                                            // dispatchRawDelta wants, same
                                            // sign convention as the live
                                            // drag above (-delta).
                                            launch {
                                                val anim = Animatable(0f)
                                                var last = 0f
                                                anim.animateDecay(velocity.x, splineBasedDecay(density)) {
                                                    horizontalScrollState.dispatchRawDelta(-(value - last))
                                                    last = value
                                                }
                                            }
                                            launch {
                                                val anim = Animatable(0f)
                                                var last = 0f
                                                anim.animateDecay(velocity.y, splineBasedDecay(density)) {
                                                    listState.dispatchRawDelta(-(value - last))
                                                    last = value
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                    ) {
                        // MicronBlock (micron2compose) never sets an
                        // explicit fontSize at all, headings included —
                        // real Micron has no font-size concept (a
                        // terminal markup), so every block just inherits
                        // this ambient LocalTextStyle uniformly, headings
                        // distinguished only by their own fg/bg color
                        // band. This is how body (and heading) text size
                        // is controlled here. bodyFontSize (bodyMedium's
                        // real size) still tracks the user's Settings →
                        // text size multiplier, same as every other role.
                        //
                        // lineHeight scaled down by the same 0.85 factor,
                        // not left at bodyMedium's own unscaled value —
                        // a real on-device report ("wider gap between
                        // rows" on multi-line content like this page's
                        // own logo art) traced to exactly this: fontSize
                        // shrank to 0.85x but the line box itself stayed
                        // at bodyMedium's full 20sp*scale, leaving
                        // visible extra leading above/below every line
                        // that had nothing to do with Micron's own
                        // per-block spacing.
                        CompositionLocalProvider(
                            LocalTextStyle provides MaterialTheme.typography.bodyMedium.copy(
                                fontSize = bodyFontSize,
                                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 0.85f,
                            ),
                        ) {
                            MicronPage(
                                result = result!!,
                                readOnly = false,
                                scrollToAnchor = scrollToAnchor,
                                listState = listState,
                                onLinkClick = ::handleLink,
                                fontFamily = NomadMono,
                                monospaceFontFamily = NomadMono,
                                modifier = Modifier.fillMaxHeight().width(contentWidth),
                            )
                        }
                    }
                    // Custom-drawn, not a built-in Compose scrollbar API —
                    // so the user can see how much content there is /
                    // how far they've scrolled in both directions, per
                    // request. MicronPage's LazyColumn has no built-in
                    // visual scrollbar of its own.
                    VerticalScrollIndicator(listState, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    HorizontalScrollIndicator(horizontalScrollState, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth())
                    // Pull-to-refresh indicator — see pullDistance's own
                    // doc comment. Determinate (not spinning) while
                    // pulling: the ring's own sweep shows how close to
                    // pullRefreshThresholdPx the pull already is, same
                    // "how far along" cue a native pull-to-refresh spinner
                    // gives via its own arc.
                    if (pullDistance > 0f) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = with(density) { (pullDistance * 0.6f).toDp() }),
                        ) {
                            CircularProgressIndicator(
                                progress = { (pullDistance / pullRefreshThresholdPx).coerceIn(0f, 1f) },
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
                else -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

/**
 * A single-line address field built from `BasicTextField` +
 * `OutlinedTextFieldDefaults.DecorationBox` instead of the convenience
 * `OutlinedTextField` composable — the convenience one doesn't expose its
 * own internal content padding at all, so no outer `.height()`/`.padding()`
 * tuning on it can ever remove that padding, only change the box size
 * around it (confirmed the hard way: 40dp, then 44dp, still clipped the
 * bottom of the text either way). This construction *does* expose
 * `contentPadding` directly, set to almost nothing per explicit request —
 * and with no outer height constraint at all, the field sizes itself to
 * the text's real line height instead of a guessed fixed dp value, so
 * there's nothing left to clip against.
 */
@Composable
private fun CompactAddressField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onGo: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val textStyle = MaterialTheme.typography.bodyMedium.copy(
        color = MaterialTheme.colorScheme.onSurface,
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = textStyle,
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                // Almost nothing left, per explicit request — a couple dp
                // just so the cursor/descenders never touch the outline.
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = true,
                        isError = false,
                        interactionSource = interactionSource,
                    )
                },
            )
        },
    )
}

