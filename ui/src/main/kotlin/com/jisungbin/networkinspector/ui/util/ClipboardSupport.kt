package com.jisungbin.networkinspector.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.launch

/**
 * Compose 1.11 [androidx.compose.ui.platform.Clipboard]는 suspending API라서
 * 콜백·일반 람다에서 바로 부르기 불편하다. 이 헬퍼가 [rememberCoroutineScope]로
 * 한 번 감싸 `(text) -> Unit` 형태로 노출해 호출처를 단순하게 만든다.
 *
 * Desktop에서는 [ClipEntry]가 AWT [java.awt.datatransfer.Transferable]을 감싸므로
 * [StringSelection]을 그대로 넘겨 평문 텍스트를 클립보드에 올린다.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun rememberCopyToClipboard(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) {
        { text: String ->
            scope.launch { clipboard.setClipEntry(ClipEntry(StringSelection(text))) }
            Unit
        }
    }
}
