package com.featherize.app.domain

/**
 * Presets tune both image and video output. Video bitrate is derived per-pixel
 * (bits per pixel per frame) so it scales sanely across resolutions.
 */
enum class CompressionPreset(
    val label: String,
    val imageMaxDimension: Int,
    val imageJpegQuality: Int,
    val videoMaxShortSide: Int,
    val videoBitsPerPixel: Double,
) {
    LIGHT(
        label = "Léger",
        imageMaxDimension = 2560,
        imageJpegQuality = 85,
        videoMaxShortSide = 1080,
        videoBitsPerPixel = 0.20,
    ),
    MEDIUM(
        label = "Moyen",
        imageMaxDimension = 1920,
        imageJpegQuality = 70,
        videoMaxShortSide = 720,
        videoBitsPerPixel = 0.12,
    ),
    STRONG(
        label = "Fort",
        imageMaxDimension = 1280,
        imageJpegQuality = 50,
        videoMaxShortSide = 480,
        videoBitsPerPixel = 0.07,
    ),
}
