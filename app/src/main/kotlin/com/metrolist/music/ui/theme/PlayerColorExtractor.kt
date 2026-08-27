/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Player color extraction system for generating gradients from album artwork
 * 
 * Analyzes album artwork and extracts primary and secondary colors using Palette API
 * to create visually stunning dynamic gradients and compute luminance contrast.
 */
object PlayerColorExtractor {

    /**
     * Extracts primary and secondary colors from a palette and creates a dynamic gradient
     * 
     * @param palette The color palette extracted from album artwork
     * @param fallbackColor Fallback color to use if extraction fails
     * @return List of colors for gradient (primary, secondary, darker variant, deep dark base)
     */
    suspend fun extractGradientColors(
        palette: Palette,
        fallbackColor: Int
    ): List<Color> = withContext(Dispatchers.Default) {
        
        val swatches = listOfNotNull(
            palette.vibrantSwatch,
            palette.darkVibrantSwatch,
            palette.lightVibrantSwatch,
            palette.dominantSwatch,
            palette.mutedSwatch,
            palette.darkMutedSwatch,
            palette.lightMutedSwatch
        )

        val fallbackDominant = palette.dominantSwatch?.rgb?.let { Color(it) }
            ?: Color(palette.getDominantColor(fallbackColor))

        val sortedSwatches = swatches.sortedByDescending { calculateColorWeight(it) }
        val bestSwatch = sortedSwatches.firstOrNull()
        val secondSwatch = sortedSwatches.getOrNull(1)

        val primaryColor = if (bestSwatch != null) {
            val bestColor = Color(bestSwatch.rgb)
            if (isColorVibrant(bestColor)) {
                enhanceColorVividness(bestColor, 1.35f)
            } else {
                enhanceColorVividness(fallbackDominant, 1.15f)
            }
        } else {
            enhanceColorVividness(fallbackDominant, 1.15f)
        }

        val secondaryColor = if (secondSwatch != null) {
            val sColor = Color(secondSwatch.rgb)
            enhanceColorVividness(sColor, 1.2f)
        } else {
            primaryColor.copy(
                red = (primaryColor.red * 0.75f).coerceIn(0f, 1f),
                green = (primaryColor.green * 0.75f).coerceIn(0f, 1f),
                blue = (primaryColor.blue * 0.85f).coerceIn(0f, 1f)
            )
        }

        val darkVariant = primaryColor.copy(
            red = (primaryColor.red * 0.35f).coerceIn(0f, 1f),
            green = (primaryColor.green * 0.35f).coerceIn(0f, 1f),
            blue = (primaryColor.blue * 0.40f).coerceIn(0f, 1f)
        )

        // 4-point dynamic gradient: Primary -> Secondary -> Dark Variant -> Black
        listOf(
            primaryColor,
            secondaryColor,
            darkVariant,
            Color(0xFF08080C)
        )
    }

    /**
     * Determines whether the given color is light or dark based on standard relative luminance
     */
    fun isLightColor(color: Color): Boolean {
        val luminance = 0.2126 * color.red + 0.7152 * color.green + 0.0722 * color.blue
        return luminance > 0.5
    }

    /**
     * Determines if a color is vibrant enough for use in player UI
     */
    private fun isColorVibrant(color: Color): Boolean {
        val argb = color.toArgb()
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(argb, hsv)
        val saturation = hsv[1]
        val brightness = hsv[2]
        return saturation > 0.20f && brightness > 0.15f && brightness < 0.95f
    }
    
    /**
     * Enhances color vividness by adjusting saturation and brightness
     */
    private fun enhanceColorVividness(color: Color, saturationFactor: Float = 1.3f): Color {
        val argb = color.toArgb()
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(argb, hsv)
        
        hsv[1] = (hsv[1] * saturationFactor).coerceAtMost(1.0f)
        hsv[2] = (hsv[2] * 0.92f).coerceIn(0.35f, 0.85f)
        
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    private fun calculateColorWeight(swatch: Palette.Swatch?): Float {
        if (swatch == null) return 0f
        val population = swatch.population.toFloat()
        val argb = swatch.rgb
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(argb, hsv)
        val saturation = hsv[1]
        val brightness = hsv[2]
        
        val populationWeight = population * 2f
        val vibrancyBonus = if (saturation > 0.3f && brightness > 0.3f) 1.5f else 1f
        
        return populationWeight * vibrancyBonus * (saturation + brightness) / 2f
    }
}
