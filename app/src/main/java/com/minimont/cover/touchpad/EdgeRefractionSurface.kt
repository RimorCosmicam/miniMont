package com.minimont.cover.touchpad

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * miniMate's edge glass, carried over unchanged.
 *
 * The rail and the corner are not drawn on top of the background — they are lenses cut into it.
 * This wraps whatever is behind them and refracts it: a cylindrical normal along the rail and a
 * spherical one at the corner, so the bend rises toward grazing angles the way real glass does
 * rather than following an animation. Reflections are sampled from the live artwork mirrored
 * across each lens, so they inherit every movement in the field behind.
 */
private const val EDGE_REFRACTION_SHADER = """
    uniform shader content;
    uniform float2 uResolution;
    uniform float uRailSide;
    uniform float uRailWidth;
    uniform float uCornerRadius;
    uniform float uRailEnabled;
    uniform float uCornerEnabled;
    uniform float uRailStyle;
    uniform float uCornerStyle;

    half4 main(float2 p) {
        float railDistance = mix(p.x, uResolution.x - p.x, uRailSide);
        float railMask = (1.0 - smoothstep(max(0.0, uRailWidth - 12.0), uRailWidth, railDistance)) * uRailEnabled;
        float railUnit = clamp(railDistance / max(uRailWidth, 1.0), 0.0, 1.0);

        float cornerX = mix(uResolution.x, 0.0, uRailSide);
        float2 cornerVector = p - float2(cornerX, 0.0);
        float cornerDistance = length(cornerVector);
        float cornerMask = (1.0 - smoothstep(max(0.0, uCornerRadius - 16.0), uCornerRadius, cornerDistance)) * uCornerEnabled;
        float cornerUnit = clamp(cornerDistance / max(uCornerRadius, 1.0), 0.0, 1.0);

        // Cylindrical rail and spherical corner surface normals. Refraction rises
        // naturally toward grazing angles rather than following an animation wave.
        float railX = railUnit * 2.0 - 1.0;
        float railZ = sqrt(max(0.04, 1.0 - railX * railX));
        float cornerZ = sqrt(max(0.04, 1.0 - cornerUnit * cornerUnit));
        float direction = uRailSide < 0.5 ? 1.0 : -1.0;
        float2 radial = cornerVector / max(cornerDistance, 1.0);
        float railStrength = uRailStyle < 0.5 ? 0.78 : (uRailStyle < 1.5 ? 0.90 : (uRailStyle < 2.5 ? 1.05 : (uRailStyle < 3.5 ? 0.95 : 1.25)));
        float cornerStrength = uCornerStyle < 0.5 ? 0.72 : (uCornerStyle < 1.5 ? 0.86 : (uCornerStyle < 2.5 ? 1.00 : (uCornerStyle < 3.5 ? 0.92 : 1.20)));
        float railBend = clamp(railX / railZ, -2.2, 2.2) * uRailWidth * 0.18 * railStrength;
        float cornerBend = clamp(cornerUnit / cornerZ, 0.0, 2.2) * uCornerRadius * 0.12 * cornerStrength;
        float2 railNormal = float2(direction * railX, 0.0);
        float2 cornerNormal = radial;
        float2 opticalOffset = railNormal * railBend * railMask + cornerNormal * cornerBend * cornerMask;

        float mask = max(railMask, cornerMask);
        half4 original = content.eval(p);
        // Everything below samples the frame another ten times, and the last line then throws all
        // of it away wherever the mask is zero — which is most of the display. Leaving early turns
        // eleven full-screen texture reads per pixel per frame into one everywhere the glass is
        // not, at no cost to how the glass looks.
        if (mask <= 0.0) return original;

        float railDispersion = uRailStyle > 2.5 && uRailStyle < 3.5 ? 0.115 : (uRailStyle > 3.5 ? 0.055 : 0.028);
        float cornerDispersion = uCornerStyle > 2.5 && uCornerStyle < 3.5 ? 0.115 : (uCornerStyle > 3.5 ? 0.055 : 0.028);
        float dispersion = max(railMask * railDispersion, cornerMask * cornerDispersion);
        float2 sampleRed = clamp(p - opticalOffset * (1.0 - dispersion), float2(0.0), uResolution - float2(1.0));
        float2 sampleGreen = clamp(p - opticalOffset, float2(0.0), uResolution - float2(1.0));
        float2 sampleBlue = clamp(p - opticalOffset * (1.0 + dispersion), float2(0.0), uResolution - float2(1.0));
        half3 refracted = half3(content.eval(sampleRed).r, content.eval(sampleGreen).g, content.eval(sampleBlue).b);

        // Reflections are sampled from the live artwork mirrored across each lens,
        // so they inherit every movement and color change in the field behind.
        float railAxisX = mix(uRailWidth * 0.5, uResolution.x - uRailWidth * 0.5, uRailSide);
        float2 railReflectionPoint = float2(railAxisX * 2.0 - p.x, p.y);
        float2 cornerReflectionPoint = float2(cornerX, 0.0) + radial * uCornerRadius * (1.0 - cornerUnit);
        float2 reflectionPoint = mix(railReflectionPoint, cornerReflectionPoint, step(railMask, cornerMask));
        half3 reflected = content.eval(clamp(reflectionPoint, float2(0.0), uResolution - float2(1.0))).rgb;
        float railF0 = uRailStyle < 1.5 ? 0.055 : (uRailStyle < 2.5 ? 0.085 : (uRailStyle < 3.5 ? 0.075 : 0.105));
        float cornerF0 = uCornerStyle < 1.5 ? 0.055 : (uCornerStyle < 2.5 ? 0.085 : (uCornerStyle < 3.5 ? 0.075 : 0.105));
        float railFresnel = railF0 + (1.0 - railF0) * pow(1.0 - railZ, 5.0);
        float cornerFresnel = cornerF0 + (1.0 - cornerF0) * pow(1.0 - cornerZ, 5.0);
        float fresnel = max(railMask * railFresnel, cornerMask * cornerFresnel);
        half3 glass = mix(refracted, reflected, half(fresnel));

        float scatter = max(railMask * (uRailStyle > 3.5 ? 0.24 : (uRailStyle > 1.5 && uRailStyle < 2.5 ? 0.11 : 0.0)), cornerMask * (uCornerStyle > 3.5 ? 0.24 : (uCornerStyle > 1.5 && uCornerStyle < 2.5 ? 0.11 : 0.0)));
        half3 soft = (content.eval(clamp(sampleGreen + float2(4.0, 0.0), float2(0.0), uResolution - float2(1.0))).rgb + content.eval(clamp(sampleGreen - float2(4.0, 0.0), float2(0.0), uResolution - float2(1.0))).rgb + content.eval(clamp(sampleGreen + float2(0.0, 4.0), float2(0.0), uResolution - float2(1.0))).rgb + content.eval(clamp(sampleGreen - float2(0.0, 4.0), float2(0.0), uResolution - float2(1.0))).rgb) * half(0.25);
        glass = mix(glass, soft, half(scatter));
        glass = clamp((glass - half3(0.5)) * half(1.10) + half3(0.5), half3(0.0), half3(1.0));
        half luma = dot(refracted, half3(0.2126, 0.7152, 0.0722));
        float adaptive = max(railMask * (uRailStyle > 0.5 && uRailStyle < 1.5 ? 1.0 : 0.0), cornerMask * (uCornerStyle > 0.5 && uCornerStyle < 1.5 ? 1.0 : 0.0));
        glass = mix(glass, glass * half(0.91) + half3((0.5 - float(luma)) * 0.085 + 0.04), half(adaptive));

        // Caustic energy comes from local contrast in the sampled artwork, not a
        // synthetic light sweep.
        float2 surfaceNormal = mix(railNormal, cornerNormal, step(railMask, cornerMask));
        half3 contrastA = content.eval(clamp(sampleGreen + surfaceNormal * 3.0, float2(0.0), uResolution - float2(1.0))).rgb;
        half3 contrastB = content.eval(clamp(sampleGreen - surfaceNormal * 3.0, float2(0.0), uResolution - float2(1.0))).rgb;
        float localContrast = float(length(contrastA - contrastB));
        half caustic = half(mask * fresnel * localContrast * 0.24);
        float deep = max(railMask * (uRailStyle > 1.5 && uRailStyle < 2.5 ? 1.0 : 0.0), cornerMask * (uCornerStyle > 1.5 && uCornerStyle < 2.5 ? 1.0 : 0.0));
        half3 glassColor = glass * half(1.0 - deep * 0.09) + half3(caustic);
        half3 result = mix(original.rgb, glassColor, half(mask));
        return half4(result, original.a);
    }
"""

/** Building the effect needs API 33, which is also the only place the shader itself exists. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun edgeEffect(
    shader: RuntimeShader,
    width: Float,
    height: Float,
    railOnRight: Boolean,
    railWidth: Float,
    cornerRadius: Float,
    railEnabled: Boolean,
    cornerEnabled: Boolean
): androidx.compose.ui.graphics.RenderEffect {
    shader.setFloatUniform("uResolution", width.coerceAtLeast(1f), height.coerceAtLeast(1f))
    shader.setFloatUniform("uRailSide", if (railOnRight) 1f else 0f)
    shader.setFloatUniform("uRailWidth", railWidth)
    shader.setFloatUniform("uCornerRadius", cornerRadius)
    shader.setFloatUniform("uRailEnabled", if (railEnabled) 1f else 0f)
    shader.setFloatUniform("uCornerEnabled", if (cornerEnabled) 1f else 0f)
    // miniMate's first material. The other four are its own settings, which miniDex does not offer.
    shader.setFloatUniform("uRailStyle", 0f)
    shader.setFloatUniform("uCornerStyle", 0f)
    return RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
}

/**
 * Wraps the field in the edge glass. [content] is emitted from exactly one place whatever the rail
 * and corner are doing, so the background is composed once and stays: a subtree that moves is a
 * subtree Compose disposes, and rebuilding the halftone shader mid-transition is a visible flash.
 */
@Composable
fun EdgeRefractionSurface(
    railEnabled: Boolean,
    cornerEnabled: Boolean,
    railOnRight: Boolean,
    railScale: Float,
    cornerScale: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shader = remember {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) null
        else runCatching { RuntimeShader(EDGE_REFRACTION_SHADER) }
            .onFailure { Log.e("MiniDexGlass", "Edge glass shader compilation failed", it) }
            .getOrNull()
    }
    val density = LocalDensity.current
    val railWidth = with(density) { (28.dp * railScale.coerceIn(0.65f, 1.8f)).toPx() }
    val cornerRadius = with(density) { (94.dp * cornerScale.coerceIn(0.65f, 1.8f)).toPx() }
    val glass = if (railEnabled || cornerEnabled) shader else null

    Box(
        modifier.then(
            if (glass == null) Modifier else Modifier.graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                renderEffect = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        edgeEffect(
                            glass, size.width, size.height, railOnRight, railWidth, cornerRadius,
                            railEnabled, cornerEnabled
                        )
                    } else {
                        null
                    }
                }.getOrNull()
            }
        )
    ) { content() }
}
