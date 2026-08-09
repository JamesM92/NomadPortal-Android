package com.jamesm92.nomadportal.ui.browser

import android.content.res.Configuration
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
import com.jamesm92.nomadportal.data.browsing.BrowserRepository
import com.jamesm92.nomadportal.data.browsing.PageAddress
import com.jamesm92.nomadportal.panicwipe.PanicWipe
import com.jamesm92.nomadportal.ui.components.AdaptiveTopAppBar
import com.jamesm92.nomadportal.ui.components.HorizontalScrollIndicator
import com.jamesm92.nomadportal.ui.components.PanicWipeLogo
import com.jamesm92.nomadportal.ui.components.VerticalScrollIndicator
import com.jamesm92.nomadportal.ui.components.dismissKeyboardOnTap
import com.jamesm92.nomadportal.ui.theme.NomadMono
import com.jamesm92.nomadportal.ui.theme.NomadTextDim
import kotlinx.coroutines.launch

/** A short, blank/near-empty page still needs *some* width — a phone-width
 * floor, not zero. */
private val MIN_MICRON_CONTENT_WIDTH = 320.dp

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    repository: BrowserRepository,
    startAddress: PageAddress,
    onBack: () -> Unit,
) {
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
    LaunchedEffect(currentAddress, identified) {
        loadError = null
        result = null
        rawSource = null
        try {
            val source = repository.fetchPage(currentAddress, identify = identified)
            rawSource = source
            result = converter.convert(source, nodeHash = currentAddress.nodeHash, basePath = currentAddress.path)
        } catch (e: Exception) {
            loadError = e.message ?: "Failed to load page"
        }
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
                        Text(nodeName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to nodes")
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
                                contentDescription = if (currentNodeFavorited) "Unfavorite this node" else "Favorite this node",
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
                                    "Identifying to this node — tap to browse anonymously"
                                } else {
                                    "Browsing anonymously — tap to identify to this node"
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
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.8f,
                    ),
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
                    val textMeasurer = rememberTextMeasurer()
                    val listState = rememberLazyListState()
                    val horizontalScrollState = rememberScrollState()
                    val bodyFontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.8f
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
                                    val pointerId = down.id
                                    val slop = viewConfiguration.touchSlop
                                    var dragging = false
                                    var accumX = 0f
                                    var accumY = 0f
                                    while (true) {
                                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                        if (!change.pressed) break
                                        if (!change.positionChanged()) continue
                                        val delta = change.positionChange()
                                        if (dragging) {
                                            change.consume()
                                            horizontalScrollState.dispatchRawDelta(-delta.x)
                                            listState.dispatchRawDelta(-delta.y)
                                        } else {
                                            accumX += delta.x
                                            accumY += delta.y
                                            if (kotlin.math.abs(accumX) > slop || kotlin.math.abs(accumY) > slop) {
                                                dragging = true
                                                change.consume()
                                                horizontalScrollState.dispatchRawDelta(-accumX)
                                                listState.dispatchRawDelta(-accumY)
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
                        // is controlled here. Scaled off the theme's own
                        // bodyLarge size so it still tracks the user's
                        // Settings → text size multiplier.
                        CompositionLocalProvider(
                            LocalTextStyle provides MaterialTheme.typography.bodyLarge.copy(fontSize = bodyFontSize),
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
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.8f,
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

