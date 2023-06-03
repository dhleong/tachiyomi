package eu.kanade.tachiyomi.util.ml

import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.View.OnAttachStateChangeListener
import androidx.core.view.drawToBitmap
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.Text.TextBlock
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import eu.kanade.tachiyomi.util.lang.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

class TextDetector(
    private val pageView: View,
    language: TextRecognizerOptionsInterface =
        JapaneseTextRecognizerOptions.Builder().build(),
) {
    private val recognizer by lazy {
        TextRecognition.getClient(language)
    }
    private val lastBlocks = AtomicReference<List<Text.TextBlock>>(null)

    private var scopeJob: Job? = null
    private var scope: CoroutineScope? = null

    init {
        val listener = object : OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                val newJob = SupervisorJob()
                scopeJob = newJob
                scope = CoroutineScope(newJob + Dispatchers.Main)
            }

            override fun onViewDetachedFromWindow(v: View) {
                scope?.cancel()
                scope = null
            }
        }
        pageView.addOnAttachStateChangeListener(listener)
        if (pageView.isAttachedToWindow) {
            listener.onViewAttachedToWindow(pageView)
        }
    }

    fun scanViewForText(view: View) {
        scanForText {
            InputImage.fromBitmap(view.drawToBitmap(), 0)
        }
    }

    fun scanForText(inputFactory: () -> InputImage) {
        val job = scopeJob ?: return
        job.cancelChildren()

        scope?.launch(job) {
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

    fun findTextBlockInView(event: MotionEvent): TextBlock? {
        val sourceCoord =
            (pageView as? SubsamplingScaleImageView)
                ?.viewToSourceCoord(event.x, event.y)
                ?: return null

        val x = sourceCoord.x.toInt()
        val y = sourceCoord.y.toInt()
        return findTextBlockAtPoint(x, y)
    }

    fun findTextBlockAtPoint(x: Int, y: Int): TextBlock? {
        val blocks = lastBlocks.get() ?: return null

        val matchingBlock = blocks.find {
            it.boundingBox?.contains(x, y) == true
        }

        Log.v("ml", "found: ${matchingBlock?.text}")
        for (line in matchingBlock?.lines ?: emptyList()) {
            Log.v("ml", "line@${line.confidence}: ${line.text}")
        }

        return matchingBlock
    }
}
