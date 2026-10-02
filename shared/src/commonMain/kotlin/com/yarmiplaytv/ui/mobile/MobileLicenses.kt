package com.yarmiplaytv.ui.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yarmiplaytv.ui.nav.Navigator
import com.yarmiplaytv.ui.shared.LICENSE_SUMMARY
import com.yarmiplaytv.ui.shared.openSourceComponents
import com.yarmiplaytv.ui.theme.AppColors

@Composable
fun MobileLicensesScreen(nav: Navigator) {
    Column(Modifier.fillMaxSize()) {
        MobileTopBar("Open-source licenses", nav)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp).testTag("licenses")) {
            Text(
                LICENSE_SUMMARY,
                color = AppColors.TextDim,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            openSourceComponents.forEach { c ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(c.name, style = MaterialTheme.typography.bodyLarge)
                    Text(c.license, color = AppColors.Accent, style = MaterialTheme.typography.bodyMedium)
                    Text(c.url, color = AppColors.TextDim, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
