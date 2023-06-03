package eu.kanade.tachiyomi.util.ml

data class DetectedText(
    val text: String,
    val language: String,
    val confidence: Float,
    val granularity: Granularity,
) {
    enum class Granularity {
        BLOCK,
        LINE,
        WORD,
    }
}
