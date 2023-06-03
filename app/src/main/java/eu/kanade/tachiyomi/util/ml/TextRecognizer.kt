package eu.kanade.tachiyomi.util.ml

import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.core.view.drawToBitmap
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.lang.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.newCoroutineContext
import okhttp3.internal.closeQuietly
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class TextRecognizer(
    parentScope: CoroutineScope,
    language: ReaderPreferences.RecognizeTextLanguage,
    private val preferredTextGranularity: RecognizedText.Granularity =
        RecognizedText.Granularity.WORD,
    private val debounceScansDuration: Duration = 175.0.milliseconds,
) {
    private val recognizer by lazy {
        when (language) {
            ReaderPreferences.RecognizeTextLanguage.DISABLE -> null
            ReaderPreferences.RecognizeTextLanguage.LATIN ->
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            ReaderPreferences.RecognizeTextLanguage.DEVANAGARI ->
                TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            ReaderPreferences.RecognizeTextLanguage.CHINESE ->
                TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            ReaderPreferences.RecognizeTextLanguage.KOREAN ->
                TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            ReaderPreferences.RecognizeTextLanguage.JAPANESE ->
                TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        }
    }
    private val lastBlocks = AtomicReference<List<Text.TextBlock>>(null)

    private val scope = CoroutineScope(
        parentScope.newCoroutineContext(Dispatchers.IO),
    )
    private var lastScanJob: Job? = null

    init {
        scope.coroutineContext.job.invokeOnCompletion {
            recognizer?.closeQuietly()
        }
    }

    fun scanViewForText(view: View) {
        scanForText {
            InputImage.fromBitmap(view.drawToBitmap(), 0)
        }
    }

    fun scanForText(inputFactory: () -> InputImage) {
        val recognizer = recognizer ?: return

        // There should only be one active scan at a time
        lastScanJob?.cancel()
        lastScanJob = scope.launch {
            delay(debounceScansDuration)

            try {
                Log.v("ml", "Scanning for text")
                val text = recognizer.process(inputFactory()).await()
                lastBlocks.set(text.textBlocks)
                Log.v("ml", "processed: ${text.text}")
            } catch (e: CancellationException) {
                // "Ignore" and re-raise cancellations
                throw e
            } catch (e: Throwable) {
                Log.v("ml", "failed: $e")
            }
        }
    }

    /**
     * If you fed a full image in, this method may be useful to convert a motion
     * event to the source image coordinates
     */
    fun findTextBlockInZoomedView(view: SubsamplingScaleImageView, event: MotionEvent): RecognizedText? {
        val sourceCoord = view.viewToSourceCoord(event.x, event.y)
            ?: return null

        val x = sourceCoord.x.toInt()
        val y = sourceCoord.y.toInt()
        return findTextBlockAtPoint(x, y)
    }

    fun findTextBlockAtPoint(ev: MotionEvent) =
        findTextBlockAtPoint(ev.x.toInt(), ev.y.toInt())

    private fun findTextBlockAtPoint(x: Int, y: Int): RecognizedText? {
        val blocks = lastBlocks.get() ?: return null

        val matchingBlock = blocks.find {
            it.boundingBox?.contains(x, y) == true
        } ?: return null

        if (BuildConfig.DEBUG) {
            Log.v("ml", "found: ${matchingBlock.text}")
            for (line in matchingBlock.lines) {
                Log.v("ml", "line@${line.confidence}: ${line.text}")
            }
        }

        // Refine granularity if requested. This is a bit more
        // annoying than it could be if TextBase were not package private
        if (preferredTextGranularity != RecognizedText.Granularity.BLOCK) {
            matchingBlock.findLineAt(x, y)?.let { lineGranularity ->
                // NOTE: The Japanese recognizer tends to (incorrectly) return a single
                // "element" for every line. If there's a single "word" in the line, we should
                // fall through and return a "line" granularity result
                if (
                    preferredTextGranularity == RecognizedText.Granularity.WORD &&
                    lineGranularity.elements.size > 1
                ) {
                    lineGranularity.findElementAt(x, y)?.let { wordGranularity ->
                        return RecognizedText(
                            text = wordGranularity.text,
                            language = wordGranularity.recognizedLanguage,
                            confidence = wordGranularity.confidence,
                            granularity = RecognizedText.Granularity.WORD,
                        )
                    }

                    Log.v("ml", "Fallback to LINE from $preferredTextGranularity")
                }

                return RecognizedText(
                    text = lineGranularity.text,
                    language = lineGranularity.recognizedLanguage,
                    confidence = lineGranularity.elements.averageBy { it.confidence },
                    granularity = RecognizedText.Granularity.LINE,
                )
            }

            Log.v("ml", "Fallback to BLOCK from $preferredTextGranularity")
        }

        return RecognizedText(
            text = matchingBlock.text,
            language = matchingBlock.recognizedLanguage,
            confidence = matchingBlock.lines.averageBy { it.confidence },
            granularity = RecognizedText.Granularity.BLOCK,
        )
    }
}

private fun Text.TextBlock.findLineAt(x: Int, y: Int): Text.Line? {
    for (line in lines) {
        if (line.boundingBox?.contains(x, y) == true) {
            return line
        }
    }
    return null
}

private fun Text.Line.findElementAt(x: Int, y: Int): Text.Element? {
    for (element in elements) {
        if (element.boundingBox?.contains(x, y) == true) {
            return element
        }
    }
    return null
}

private fun <T> List<T>.averageBy(predicate: (T) -> Float): Float {
    val total = sumOf { predicate(it).toDouble() }
    return (total / size).toFloat()
}
