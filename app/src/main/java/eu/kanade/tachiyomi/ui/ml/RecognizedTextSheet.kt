package eu.kanade.tachiyomi.ui.ml

import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import eu.kanade.tachiyomi.databinding.RecognizedTextSheetBinding
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.ml.RecognizedText
import eu.kanade.tachiyomi.widget.sheet.BaseBottomSheetDialog
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import tachiyomi.core.util.lang.launchIO
import tachiyomi.core.util.lang.withUIContext

class RecognizedTextSheet(
    private val activity: ReaderActivity,
    private val recognizedText: RecognizedText,
) : BaseBottomSheetDialog(activity) {
    private val scope = MainScope()

    private lateinit var binding: RecognizedTextSheetBinding

    override fun createView(inflater: LayoutInflater): View {
        binding = RecognizedTextSheetBinding.inflate(activity.layoutInflater, null, false)

        binding.recognizedText.text = recognizedText.text
        binding.share.setOnClickListener { shareText() }

        scope.launchIO {
            val translatorIntent = resolveTranslateIntent()
            if (translatorIntent != null) {
                withUIContext {
                    binding.translate.visibility = View.VISIBLE
                    binding.translate.setOnClickListener { translateText(translatorIntent) }
                }
            }
        }

        return binding.root
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }

    private fun shareText() {
        activity.shareText(recognizedText.text)
    }

    private fun translateText(intent: Intent) {
        activity.startActivity(intent)
    }

    @Suppress("DEPRECATION")
    private fun resolveTranslateIntent(): Intent? {
        val packageManager = activity.packageManager
        for (intent in generateIntents()) {
            if (packageManager.resolveActivity(intent, 0) != null) {
                return intent
            }
        }

        return null
    }

    private fun generateIntents() = sequence {
        yield(
            Intent(Intent.ACTION_PROCESS_TEXT).apply {
                `package` = GOOGLE_TRANSLATE_PACKAGE
                type = "text/plain"
                putExtra(Intent.EXTRA_PROCESS_TEXT, recognizedText.text)
            },
        )

        yield(
            Intent(Intent.ACTION_SEND).apply {
                `package` = GOOGLE_TRANSLATE_PACKAGE
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, recognizedText.text)
                putExtra("key_text_input", recognizedText.text)
                putExtra("key_language_from", recognizedText.language)
                putExtra("key_from_floating_window", true)
            },
        )
    }

    companion object {
        private const val GOOGLE_TRANSLATE_PACKAGE = "com.google.android.apps.translate"
    }
}
