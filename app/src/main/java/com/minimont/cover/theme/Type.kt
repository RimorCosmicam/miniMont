package com.minimont.cover.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.minimont.R

/**
 * Mont, in five weights.
 *
 * Compose snaps an unlisted weight to the nearest supplied one, so SemiBold has to ship even
 * though it is rarely named: without it Medium collapses onto Regular and headings stop reading
 * as headings.
 *
 * Black is not an emphasis weight here, it is the default. The interface is built almost entirely
 * from it, which is what lets a plain word act as a button without any box around it.
 */
val Mont = FontFamily(
    Font(R.font.mont_thin, FontWeight.Thin),
    Font(R.font.mont_light, FontWeight.Light),
    Font(R.font.mont_regular, FontWeight.Normal),
    Font(R.font.mont_semibold, FontWeight.SemiBold),
    Font(R.font.mont_black, FontWeight.Black)
)

/**
 * The Mont scale. Small, because the screen is small; every size here is in use.
 * Letter-spacing runs slightly negative on titles and slightly positive on the smallest labels.
 */
val Typography = Typography(
    titleLarge = TextStyle(
        fontFamily = Mont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.4).sp
    ),
    titleMedium = TextStyle(
        fontFamily = Mont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.2).sp
    ),
    titleSmall = TextStyle(
        fontFamily = Mont,
        fontWeight = FontWeight.Black,
        fontSize = 15.sp,
        lineHeight = 18.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Mont,
        fontWeight = FontWeight.Black,
        fontSize = 14.sp,
        lineHeight = 17.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Mont,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 15.sp
    ),
    labelLarge = TextStyle(
        fontFamily = Mont,
        fontWeight = FontWeight.Black,
        fontSize = 13.sp,
        lineHeight = 15.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Mont,
        fontWeight = FontWeight.Black,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = Mont,
        fontWeight = FontWeight.Black,
        fontSize = 10.sp,
        lineHeight = 12.sp
    )
)
