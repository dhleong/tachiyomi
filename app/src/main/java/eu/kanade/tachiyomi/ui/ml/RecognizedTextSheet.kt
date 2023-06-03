package eu.kanade.tachiyomi.ui.ml

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import androidx.compose.ui.text.intl.Locale
import androidx.core.view.isVisible
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import eu.kanade.tachiyomi.databinding.RecognizedTextSheetBinding
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.lang.await
import eu.kanade.tachiyomi.util.ml.RecognizedText
import eu.kanade.tachiyomi.widget.sheet.BaseBottomSheetDialog
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import okhttp3.internal.closeQuietly
import tachiyomi.core.util.lang.launchIO
import tachiyomi.core.util.lang.withUIContext

class RecognizedTextSheet(
    private val activity: ReaderActivity,
    private val recognizedText: RecognizedText,
) : BaseBottomSheetDialog(activity) {
    private val scope = MainScope()

    private lateinit var binding: RecognizedTextSheetBinding

    private val translator by lazy {
        val sourceLanguage = TranslateLanguage.fromLanguageTag(recognizedText.language)
            ?: return@lazy null

        val localeLanguage = Locale.current.toLanguageTag()
        val targetLanguage = TranslateLanguage.fromLanguageTag(localeLanguage)
            ?: TranslateLanguage.fromLanguageTag(stripLocale(localeLanguage))
            ?: return@lazy null

        val options =
            TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(targetLanguage)
                .build()
        Translation.getClient(options)
    }

    override fun createView(inflater: LayoutInflater): View {
        binding = RecognizedTextSheetBinding.inflate(activity.layoutInflater, null, false)

        binding.recognizedText.text = recognizedText.text
        binding.translatedText.isVisible = translator != null
        binding.share.setOnClickListener { shareText() }

        scope.launchIO {
            val translatorIntent = resolveTranslateIntent(activity, recognizedText)
            if (translatorIntent != null) {
                withUIContext {
                    binding.translate.visibility = View.VISIBLE
                    binding.translate.setOnClickListener { translateText(translatorIntent) }
                }
            }

            translator?.let { translator ->
                scope.coroutineContext.job.invokeOnCompletion {
                    translator.closeQuietly()
                }

                val downloadConditions =
                    DownloadConditions.Builder()
                        .requireWifi()
                        .build()
                try {
                    translator.downloadModelIfNeeded(downloadConditions).await()
                    val translation = translator.translate(recognizedText.text).await()
                    withUIContext {
                        binding.translatedText.text = translation
                    }
                } catch (e: Throwable) {
                    @SuppressLint("SetTextI18n")
                    binding.translatedText.text = "(${e.message})"
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

    companion object {
        private const val GOOGLE_TRANSLATE_PACKAGE = "com.google.android.apps.translate"

        @Suppress("DEPRECATION")
        fun resolveTranslateIntent(context: Context, recognizedText: RecognizedText): Intent? {
            val packageManager = context.packageManager
            for (intent in generateIntents(recognizedText)) {
                if (packageManager.resolveActivity(intent, 0) != null) {
                    return intent
                }
            }

            return null
        }

        private fun generateIntents(recognizedText: RecognizedText) = sequence {
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

        private fun stripLocale(languageTag: String): String {
            val dashIndex = languageTag.indexOf('-')
            if (dashIndex == -1) {
                return languageTag
            }

            return languageTag.substring(0 until dashIndex)
        }
    }
}
