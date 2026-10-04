// SPDX-License-Identifier: GPL-3.0-or-later
package dev.victorialauncher.ui.applist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.victorialauncher.R
import dev.victorialauncher.data.AppInfo
import dev.victorialauncher.data.EdgeSide
import dev.victorialauncher.data.HomeAlignment
import dev.victorialauncher.data.IconSide
import dev.victorialauncher.data.ShortcutSwipe
import dev.victorialauncher.ui.common.EditAppDialog

/** How far the screen rises from as it comes in, at nothing open. */
private val SEARCH_ENTER_RISE = 96.dp

/** A downward pull past the end of the results that puts the screen away. */
private val SEARCH_DISMISS_PULL = 72.dp

/**
 * The search on its own: a field with the keyboard already up and whatever the query turns up,
 * with no A-Z list underneath and no strip.
 *
 * Deliberately not a mode of [AppListScreen]. The list's keyboard setting also decides what a
 * touch on the strip opens into, and the point of this screen is that typing straight away
 * never costs the strip anything — so it shares the rows and the field, and none of the state.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    searchModel: AppListModel,
    nameOverrides: Map<String, String>,
    /** Placed and drawing; true from the first pixel of the swipe that brings it in. */
    visible: Boolean,
    /** Committed to being open, which is when the cursor goes into the field. */
    open: Boolean,
    /** 0 nowhere, 1 fully in. Read in the draw phase only. */
    progress: () -> Float,
    query: String,
    onQueryChange: (String) -> Unit,
    iconSizeDp: Int,
    labelSizeSp: Int,
    contentColor: Color,
    alignment: HomeAlignment,
    iconSide: IconSide,
    shortcutSwipe: ShortcutSwipe,
    favoritesEditable: Boolean,
    favoriteKeys: Set<String>,
    hiddenApps: Set<String>,
    statusBarHidden: Boolean,
    fieldAtBottom: Boolean,
    webSearchFallback: Boolean,
    onWebSearch: (String) -> Unit,
    onLaunch: (AppInfo) -> Unit,
    onSetFavorite: (AppInfo, Boolean) -> Unit,
    onSetName: (AppInfo, String?) -> Unit,
    onChangeIcon: (AppInfo) -> Unit,
    onAppInfo: (AppInfo) -> Unit,
    onUnpinShortcut: (AppInfo) -> Unit,
    onHideApp: (AppInfo, Boolean) -> Unit,
    onMoveToFolder: (AppInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    fun displayName(app: AppInfo) = nameOverrides[app.key] ?: app.label

    val currentDismiss by rememberUpdatedState(onDismiss)
    val density = LocalDensity.current
    val risePx = with(density) { SEARCH_ENTER_RISE.toPx() }
    val dismissPullPx = with(density) { SEARCH_DISMISS_PULL.toPx() }

    // Nothing is listed until something is typed: an empty query is the moment before a
    // search, not a request for every app.
    val results = remember(searchModel, query, nameOverrides) {
        if (query.isBlank()) {
            emptyList()
        } else {
            searchModel.searchFor(query, ::displayName).rows.filterIsInstance<AppListRow.Entry>()
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(query) { listState.scrollToItem(0) }

    val focusManager = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    // Asked for the moment the swipe commits, not when the screen settles: by then the finger
    // is already off the glass and on its way to the keys.
    LaunchedEffect(open) {
        if (open) runCatching { searchFocus.requestFocus() } else focusManager.clearFocus()
    }

    var menuForKey by remember { mutableStateOf<String?>(null) }
    var menuOffset by remember { mutableStateOf(DpOffset.Zero) }
    var editDialogFor by remember { mutableStateOf<AppInfo?>(null) }
    val touchPosition = remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(visible) {
        if (!visible) {
            menuForKey = null
            editDialogFor = null
        }
    }

    // A drag down that the results have no use for — nothing to scroll back through, or
    // nothing listed at all — is the screen being pushed back where it came from.
    val dismissPull = remember {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(available: Offset, consumed: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    pulled += available.y
                    if (pulled > dismissPullPx) {
                        pulled = 0f
                        currentDismiss()
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                pulled = 0f
                return Velocity.Zero
            }
        }
    }

    fun launchFirst() {
        results.firstOrNull()?.let { onLaunch(it.app) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = progress().coerceIn(0f, 1f)
                alpha = p
                translationY = (1f - p) * risePx
            }
            // Anywhere that is not a row or the field: a tap there is a tap on nothing.
            .pointerInput(Unit) { detectTapGestures { currentDismiss() } }
            .nestedScroll(dismissPull),
    ) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .widthIn(max = MAX_LIST_WIDTH)
                .align(Alignment.TopCenter)
                .fillMaxSize()
                .then(
                    if (statusBarHidden) {
                        Modifier
                    } else {
                        Modifier.windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                    }
                )
                .imePadding(),
        ) {
            val field: @Composable () -> Unit = {
                SearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    contentColor = contentColor,
                    edgeSide = EdgeSide.BOTH,
                    showAlphabet = false,
                    onGo = { launchFirst() },
                    atBottom = fieldAtBottom,
                    modifier = Modifier.focusRequester(searchFocus),
                )
            }
            if (!fieldAtBottom) field()
            CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                LazyColumn(
                    state = listState,
                    // With the field down by the keyboard the best match sits right above it,
                    // under the thumb, and the rest climb away from it.
                    reverseLayout = fieldAtBottom,
                    contentPadding = PaddingValues(vertical = SEARCH_EDGE_PADDING),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    if (webSearchFallback && query.isNotBlank() && results.isEmpty()) {
                        item(key = "websearch") {
                            val term = query.trim()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onWebSearch(term) }
                                    .padding(horizontal = 24.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Search, contentDescription = null, tint = contentColor)
                                Spacer(Modifier.width(16.dp))
                                Text(
                                    stringResource(R.string.applist_search_web, term),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = contentColor,
                                )
                            }
                        }
                    }
                    items(results, key = { it.app.key }) { row ->
                        AppRow(
                            shortcutSwipe = shortcutSwipe,
                            favoritesEditable = favoritesEditable,
                            contentColor = contentColor,
                            alignment = alignment,
                            iconSide = iconSide,
                            touchPosition = touchPosition,
                            app = row.app,
                            label = displayName(row.app),
                            iconSizeDp = iconSizeDp,
                            labelSizeSp = labelSizeSp,
                            isFavorite = row.app.key in favoriteKeys,
                            menuExpanded = menuForKey == row.app.key,
                            menuOffset = menuOffset,
                            onLaunch = { onLaunch(row.app) },
                            onLongPress = { offset -> menuOffset = offset; menuForKey = row.app.key },
                            onDismissMenu = { menuForKey = null },
                            onSetFavorite = { onSetFavorite(row.app, it) },
                            onEdit = { editDialogFor = row.app },
                            onAppInfo = { onAppInfo(row.app) },
                            onUnpin = { onUnpinShortcut(row.app) },
                            onHide = { onHideApp(row.app, row.app.key !in hiddenApps) },
                            isHidden = row.app.key in hiddenApps,
                            onMoveToFolder = { onMoveToFolder(row.app) },
                        )
                    }
                }
            }
            if (fieldAtBottom) field()
        }

        editDialogFor?.let { target ->
            EditAppDialog(
                currentName = displayName(target),
                onConfirmName = { name -> onSetName(target, name); editDialogFor = null },
                onChangeIcon = { name ->
                    if (name.trim() != displayName(target)) onSetName(target, name.trim())
                    onChangeIcon(target)
                    editDialogFor = null
                },
                onDismiss = { editDialogFor = null },
            )
        }
    }
}
