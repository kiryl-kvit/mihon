package eu.kanade.presentation.more.settings.screen.debug

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.CrashLogUtil
import eu.kanade.tachiyomi.util.logging.AppLogStore
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LogsScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { LogsScreenModel() }
        val state by screenModel.state.collectAsState()
        val title = stringResource(MR.strings.pref_view_logs)
        val clearTitle = stringResource(MR.strings.pref_view_logs_clear_title)
        val clearMessage = stringResource(MR.strings.pref_view_logs_clear_message)
        val copyToClipboard = stringResource(MR.strings.action_copy_to_clipboard)
        val share = stringResource(MR.strings.action_share)
        val refresh = stringResource(MR.strings.action_webview_refresh)
        val delete = stringResource(MR.strings.action_delete)
        val cancel = stringResource(MR.strings.action_cancel)
        var showClearDialog by remember { mutableStateOf(false) }

        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text(text = clearTitle) },
                text = { Text(text = clearMessage) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearDialog = false
                            screenModel.clearLogs()
                            context.toast(MR.strings.pref_view_logs_cleared)
                        },
                    ) {
                        Text(text = delete)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text(text = cancel)
                    }
                },
            )
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = title,
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(
                                    title = copyToClipboard,
                                    icon = Icons.Default.ContentCopy,
                                    onClick = {
                                        context.copyToClipboard(title, state.logs)
                                    },
                                ),
                                AppBar.Action(
                                    title = share,
                                    icon = Icons.Outlined.Share,
                                    onClick = {
                                        screenModel.shareLogs(CrashLogUtil(context).getDebugInfo())
                                    },
                                ),
                                AppBar.Action(
                                    title = refresh,
                                    icon = Icons.Outlined.Refresh,
                                    onClick = screenModel::refresh,
                                ),
                                AppBar.Action(
                                    title = clearTitle,
                                    icon = Icons.Outlined.Delete,
                                    onClick = { showClearDialog = true },
                                ),
                            ),
                        )
                    },
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            SelectionContainer {
                Text(
                    text = state.logs,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(contentPadding)
                        .padding(16.dp),
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }

    private class LogsScreenModel(
        private val appLogStore: AppLogStore = Injekt.get(),
    ) : StateScreenModel<LogsScreenModel.State>(State()) {

        init {
            refresh()
        }

        fun refresh() {
            screenModelScope.launchIO {
                mutableState.update { it.copy(logs = appLogStore.getLogText()) }
            }
        }

        fun clearLogs() {
            screenModelScope.launchIO {
                appLogStore.clear()
                mutableState.update { it.copy(logs = appLogStore.getLogText()) }
            }
        }

        fun shareLogs(header: String) {
            screenModelScope.launchIO {
                appLogStore.shareLogs(header)
            }
        }

        data class State(
            val logs: String = AppLogStore.NO_LOGS_MESSAGE,
        )
    }
}
