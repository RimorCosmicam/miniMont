package com.minimont.cover.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minimont.cover.model.KeyboardPage
import com.minimont.cover.theme.LocalMiniDexColors
import com.minimont.cover.theme.Mont

/**
 * The page row. Selected is simply the bright one — no fill, no border, no underline.
 *
 * The connection mark leads the row and carries the only colour on it: green when the driver is
 * live, red when it is not. That is a state, which is the one thing an accent is allowed to be.
 */
@Composable
fun PageBar(
    currentPage: KeyboardPage,
    isAdbConnected: Boolean = false,
    onPageSelected: (KeyboardPage) -> Unit,
    onAdbBadgeClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = LocalMiniDexColors.current
    val pages = KeyboardPage.entries.filterNot { it == KeyboardPage.SETTINGS }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        pages.forEach { page ->
            val isSelected = page == currentPage

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(26.dp)
                    .background(colors.keyBackground)
                    .clickable { onPageSelected(page) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = page.title,
                    color = if (isSelected) androidx.compose.ui.graphics.Color.White else colors.textSecondary,
                    fontFamily = Mont,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}
