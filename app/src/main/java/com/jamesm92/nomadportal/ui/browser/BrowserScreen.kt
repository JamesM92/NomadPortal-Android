package com.jamesm92.nomadportal.ui.browser

import android.content.res.Configuration
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
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
import com.jamesm92.nomadportal.ui.components.PanicWipeLogo
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
    val clipboard = LocalClipboard.current
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
    var showFingerprintDialog by remember { mutableStateOf(false) }
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

    LaunchedEffect(currentAddress) {
        loadError = null
        result = null
        rawSource = null
        try {
            val source = repository.fetchPage(currentAddress)
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

    if (showFingerprintDialog) {
        AlertDialog(
            onDismissRequest = { showFingerprintDialog = false },
            title = { Text("Node fingerprint") },
            text = {
                Text(
                    text = currentAddress.nodeHash,
                    fontFamily = NomadMono,
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("Node fingerprint", currentAddress.nodeHash)),
                        )
                    }
                    showFingerprintDialog = false
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFingerprintDialog = false }) { Text("Close") }
            },
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
                        // height(44.dp) matches SearchField's own compact
                        // height, which needed that exact value (44dp, not
                        // 40dp) to stop clipping descenders on this same
                        // textStyle/fontSize combination — no leading/
                        // trailing icon fighting it for space here, but
                        // the field still wants a touch more vertical
                        // room than 40dp gives it for a single line.
                        OutlinedTextField(
                            value = addressBarText,
                            onValueChange = { addressBarText = it },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 2.dp)
                                .height(44.dp),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = MaterialTheme.typography.bodyLarge.fontSize * 0.8f,
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { goToAddressBarUrl() }),
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
                        // Fingerprint — the rightmost icon in the
                        // reference app's own address bar. Shows the
                        // currently-viewed node's full destination hash
                        // for manual verification (the actual security-
                        // relevant use of "fingerprint" here — a short
                        // display name can collide/be spoofed, the full
                        // hash can't), with a one-tap copy.
                        IconButton(
                            onClick = { showFingerprintDialog = true },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Fingerprint,
                                contentDescription = "Show node fingerprint",
                                tint = NomadTextDim,
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
                        modifier = Modifier.fillMaxSize().horizontalScroll(horizontalScrollState),
                    ) {
                        // MicronBlock (micron2compose) only sets an
                        // explicit fontSize for HEADING blocks
                        // (headingFontSize(level)) — body-level blocks
                        // pass TextUnit.Unspecified and fall back to the
                        // ambient LocalTextStyle, so this is how body
                        // text size is controlled here (headings aren't
                        // affected, they keep their own explicit size
                        // regardless — see the micron2compose heading
                        // font-size issue already reported upstream).
                        // Scaled off the theme's own bodyLarge size so it
                        // still tracks the user's Settings → text size
                        // multiplier.
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

/** Thin thumb along the right edge, sized/positioned from
 * [LazyListState.layoutInfo] — invisible (no track drawn at all) once
 * everything fits on-screen, since there's nothing to indicate then. */
@Composable
private fun VerticalScrollIndicator(listState: LazyListState, modifier: Modifier = Modifier) {
    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleCount = layoutInfo.visibleItemsInfo.size
    if (totalItems == 0 || visibleCount == 0) return
    val fractionVisible = (visibleCount.toFloat() / totalItems).coerceIn(0.04f, 1f)
    if (fractionVisible >= 0.999f) return

    BoxWithConstraints(modifier = modifier.padding(vertical = 2.dp)) {
        val trackHeight = maxHeight
        val thumbHeight = trackHeight * fractionVisible
        val maxFirstIndex = (totalItems - visibleCount).coerceAtLeast(1)
        val scrollFraction = (listState.firstVisibleItemIndex.toFloat() / maxFirstIndex).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = (trackHeight - thumbHeight) * scrollFraction)
                .width(4.dp)
                .height(thumbHeight)
                .background(NomadTextDim.copy(alpha = 0.6f), RoundedCornerShape(2.dp)),
        )
    }
}

/** Same idea as [VerticalScrollIndicator], along the bottom edge — the
 * one that actually matters for the "did I just break box-drawing art"
 * class of bug this session found, since that's exactly what horizontal
 * scroll is for on this screen. */
@Composable
private fun HorizontalScrollIndicator(scrollState: ScrollState, modifier: Modifier = Modifier) {
    val viewportSize = scrollState.viewportSize
    val totalSize = viewportSize + scrollState.maxValue
    if (totalSize <= 0) return
    val fractionVisible = (viewportSize.toFloat() / totalSize).coerceIn(0.04f, 1f)
    if (fractionVisible >= 0.999f) return

    BoxWithConstraints(modifier = modifier.padding(horizontal = 2.dp)) {
        val trackWidth = maxWidth
        val thumbWidth = trackWidth * fractionVisible
        val scrollFraction = if (scrollState.maxValue > 0) scrollState.value.toFloat() / scrollState.maxValue else 0f
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (trackWidth - thumbWidth) * scrollFraction)
                .height(4.dp)
                .width(thumbWidth)
                .background(NomadTextDim.copy(alpha = 0.6f), RoundedCornerShape(2.dp)),
        )
    }
}
