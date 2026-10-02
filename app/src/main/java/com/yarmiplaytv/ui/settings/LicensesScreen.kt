package com.yarmiplaytv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.yarmiplaytv.ui.components.ValueRow
import com.yarmiplaytv.ui.shared.LICENSE_SUMMARY
import com.yarmiplaytv.ui.shared.openSourceComponents
import com.yarmiplaytv.ui.theme.AppColors

/** Rows are focusable (and do nothing) so the D-pad can scroll through the list. */
@Composable
fun LicensesScreen() {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 96.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Open-source licenses", style = MaterialTheme.typography.headlineMedium)
        Text(LICENSE_SUMMARY, color = AppColors.TextDim, style = MaterialTheme.typography.bodyLarge)
        openSourceComponents.forEachIndexed { i, c ->
            ValueRow(
                c.name,
                c.license,
                onClick = {},
                modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                subtitle = c.url,
            )
        }
    }
}
