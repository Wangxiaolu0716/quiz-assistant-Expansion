package com.quizassist.engine

import com.quizassist.prompt.AnswerSanitizer
import android.graphics.Bitmap
import com.quizassist.cache.AnswerCache
import com.quizassist.image.BitmapTools
import com.quizassist.model.AppSettings
import com.quizassist.model.QuizInput
import com.quizassist.model.SolveEvent
import com.quizassist.model.SolveMode
import com.quizassist.model.StructuredAnswer
import com.quizassist.network.LLMRepository
import com.quizassist.ocr.OcrProcessor
import com.quizassist.questionbank.QuestionBankMatcher
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull

class QuizSolverEngine(
    private val ocrProcessor: OcrProcessor,
    private val llmRepository: LLMRepository,
    private val answerCache: AnswerCache,
    private val questionBankMatcher: QuestionBankMatcher,
) {
    fun solve(bitmap: Bitmap, settings: AppSettings): Flow<SolveEvent> = channelFlow {
        send(SolveEvent.Capturing)
        val input = runCatching { prepareInput(bitmap, settings) }
            .getOrElse {
                send(SolveEvent.Failure("Failed to prepare screenshot: ${it.message}", it))
                close()
                return@channelFlow
            }
        send(
            SolveEvent.OcrFinished(
                text = input.ocr?.text.orEmpty(),
                confidence = input.ocr?.confidence ?: 0f,
                mode = input.mode,
            ),
        )

        val bankStartedAt = System.nanoTime()
        val hit = if (settings.questionBankMode) {
            questionBankMatcher.find(input.ocr?.text.orEmpty(), settings.bankMatchThreshold)
        } else {
            null
        }
        send(
            SolveEvent.QuestionBankStatus(
                enabled = settings.questionBankMode,
                entryCount = questionBankMatcher.entryCount,
                matched = hit != null,
            ),
        )
        if (settings.questionBankMode) {
            if (hit != null) {
                val answer = StructuredAnswer(
                    answer = hit.entry.answer.trim(),
                    confidence = "${(hit.score * 100).toInt()}%",
                    reasoning = "本地题库关键词命中",
                )
                val elapsed = (System.nanoTime() - bankStartedAt) / 1_000_000L
                send(SolveEvent.QuestionBankHit(answer, elapsed, hit.score))
                answerCache.save(input.cacheKey, input.ocr?.text.orEmpty(), answer)
                return@channelFlow
            }
        }

        val cached = answerCache.find(input.cacheKey)
        if (cached != null) {
            send(SolveEvent.CacheHit(cached))
            close()
            return@channelFlow
        }

        val questionText = input.ocr?.text.orEmpty()

        // 未配置 API Key 时提前拦截，给出可操作的提示，避免走到网络层抛出英文报错。
        if (settings.flashProvider.apiKey.isBlank()) {
            val bankCount = questionBankMatcher.entryCount
            val hint = when {
                !settings.questionBankMode -> "未开启本地题库，且未配置 API Key。请到设置中填写 API Key，或导入题库并开启「优先使用本地题库」。"
                bankCount <= 0 -> "未导入本地题库，且未配置 API Key。请到设置中填写 API Key，或先导入题库。"
                else -> "本地题库（${bankCount} 题）未命中本题，且未配置 API Key。请到设置中填写 API Key，或确认题干与题库一致。"
            }
            send(SolveEvent.FlashFailure(hint))
            return@channelFlow
        }

        // 同一次提问；双采样时多跑一遍
        suspend fun askOnce(): String {
            val buffer = StringBuilder()
            withTimeoutOrNull(settings.maxWaitTimeoutSeconds * 1000L) {
                llmRepository.streamAnswer(settings.flashProvider, input).collect { token ->
                    buffer.append(token)
                }
            } ?: error("快速模型超时，请稍后重试")
            return buffer.toString()
        }

        val raw = StringBuilder()
        try {
            val startedAt = System.currentTimeMillis()
            val first = askOnce()
            raw.append(first)

            val answer = if (settings.doubleCheck) {
                // 第二遍：只比最终答案，不一致就明说，不替用户选
                val second = runCatching { askOnce() }.getOrNull()
                val a = AnswerSanitizer.extract(first)
                val b = second?.let { AnswerSanitizer.extract(it) }.orEmpty()
                when {
                    second == null -> llmRepository.parseStructuredAnswer(first)
                        .copy(warning = "复核那一遍失败了，只用第一遍结果")
                    a.isBlank() || b.isBlank() -> llmRepository.parseStructuredAnswer(first)
                        .copy(warning = "有一遍没抓到答案，结果仅供参考")
                    AnswerSanitizer.same(a, b) -> llmRepository.parseStructuredAnswer(first)
                    else -> llmRepository.parseStructuredAnswer(first).copy(
                        answer = "",
                        warning = "两次结果不一致（" + a + " / " + b + "），建议重拍",
                    )
                }
            } else {
                llmRepository.parseStructuredAnswer(first)
            }

            // 选项白名单：答案字母得真的出现在 OCR 里
            val optionWarning = AnswerSanitizer.checkOption(answer.answer, questionText)
            val confidence = AnswerSanitizer.parseConfidence(answer.confidence)
            val lowConfidence = if (confidence in 0..69) "置信度偏低 " + confidence + "%" else ""

            val finalAnswer = answer.copy(
                warning = listOf(answer.warning, optionWarning, lowConfidence)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString("；"),
            )

            val elapsed = System.currentTimeMillis() - startedAt
            send(SolveEvent.FlashAnswer(finalAnswer, elapsed))
            if (finalAnswer.warning.isBlank()) {
                answerCache.save(input.cacheKey, questionText, finalAnswer)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            send(SolveEvent.FlashFailure(t.message.orEmpty()))
        }
    }.flowOn(Dispatchers.Default)

    suspend fun prepareOnly(bitmap: Bitmap, settings: AppSettings): QuizInput =
        prepareInput(bitmap, settings)

    private suspend fun prepareInput(bitmap: Bitmap, settings: AppSettings): QuizInput {
        val cropped = BitmapTools.cropByRoi(bitmap, settings.roi)
        val enhanced = BitmapTools.prepareForOcr(cropped)
        val enhancedOcr = runCatching { ocrProcessor.recognize(enhanced) }.getOrNull()
        val originalOcr = if (enhancedOcr?.usable == true) {
            null
        } else if (enhancedOcr != null && enhancedOcr.text.count { it.code in 0x4E00..0x9FFF } >= 6) {
            null
        } else {
            runCatching { ocrProcessor.recognize(cropped) }.getOrNull()
        }
        if (enhanced !== cropped) enhanced.recycle()
        val ocr = listOfNotNull(enhancedOcr, originalOcr)
            .maxWithOrNull(compareBy<com.quizassist.model.OcrResult> { it.confidence }.thenBy { it.text.length })
        val mode = if (settings.useVisionWhenOcrWeak && ocr?.usable != true) {
            SolveMode.Vision
        } else {
            SolveMode.TextOnly
        }
        val base64 = if (mode == SolveMode.Vision) BitmapTools.toBase64Jpeg(cropped) else null
        val key = BitmapTools.perceptualEnoughHash(cropped, ocr?.text)
        return QuizInput(
            bitmap = bitmap,
            croppedBitmap = cropped,
            ocr = ocr,
            mode = mode,
            imageBase64Jpeg = base64,
            cacheKey = key,
        )
    }
}
