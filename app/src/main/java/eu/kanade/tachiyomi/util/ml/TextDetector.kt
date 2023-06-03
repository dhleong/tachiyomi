package eu.kanade.tachiyomi.util.ml

import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.core.view.drawToBitmap
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text.TextBlock
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.lang.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.newCoroutineContext
import okhttp3.internal.closeQuietly
import java.util.concurrent.atomic.AtomicReference

class TextDetector(
    parentScope: CoroutineScope,
    language: TextRecognizerOptionsInterface =
        JapaneseTextRecognizerOptions.Builder().build(),
) {
    private val recognizer by lazy {
        TextRecognition.getClient(language)
    }
    private val lastBlocks = AtomicReference<List<TextBlock>>(null)

    private val scope = CoroutineScope(
        parentScope.newCoroutineContext(Dispatchers.IO),
    )
    private var lastScanJob: Job? = null

    init {
        scope.coroutineContext.job.invokeOnCompletion {
            recognizer.closeQuietly()
        }
    }

    fun scanViewForText(view: View) {
        scanForText {
            InputImage.fromBitmap(view.drawToBitmap(), 0)
        }
    }

    fun scanForText(inputFactory: () -> InputImage) {
        // There should only be one active scan at a time
        lastScanJob?.cancel()
        lastScanJob = scope.launch {
            try {
                Log.v("ml", "Scanning for text")
                val text = recognizer.process(inputFactory()).await()
                lastBlocks.set(text.textBlocks)
                Log.v("ml", "processed: ${text.text}")
            } catch (e: Throwable) {
                Log.v("ml", "failed: $e")
            }
        }
    }

    /**
     * If you fed a full image in, this method may be useful to convert a motion
     * event to the source image coordinates
     */
    fun findTextBlockInZoomedView(view: SubsamplingScaleImageView, event: MotionEvent): DetectedText? {
        val sourceCoord = view.viewToSourceCoord(event.x, event.y)
            ?: return null

        val x = sourceCoord.x.toInt()
        val y = sourceCoord.y.toInt()
        return findTextBlockAtPoint(x, y)
    }

    fun findTextBlockAtPoint(ev: MotionEvent) =
        findTextBlockAtPoint(ev.x.toInt(), ev.y.toInt())

    private fun findTextBlockAtPoint(x: Int, y: Int): DetectedText? {
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

        val totalConfidence = matchingBlock.lines.sumOf { it.confidence.toDouble() }
        val averageConfidence = totalConfidence / matchingBlock.lines.size

        return DetectedText(
            text = matchingBlock.text,
            language = matchingBlock.recognizedLanguage,
            confidence = averageConfidence.toFloat(),
        )
    }
}
