package eu.kanade.tachiyomi.util.ml

data class DetectedText(
    val text: String,
    val language: String,
    val confidence: Float,
)
