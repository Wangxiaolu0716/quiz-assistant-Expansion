package com.quizassist.questionbank

import android.content.Context
import java.text.Normalizer
import kotlin.math.max

class QuestionBankMatcher(context: Context) {
    private val store = QuestionBankStore(context)

    @Volatile
    private var cached: Index? = null

    val entryCount: Int get() = index().entries.size

    fun find(ocrText: String, minScore: Float = MIN_SCORE): Match? {
        val entries = index().entries
        if (entries.isEmpty()) return null
        val queries = queryVariants(ocrText)
        if (queries.isEmpty()) return null

        val matches = entries.mapNotNull { indexed ->
            queries.maxOfOrNull { query -> score(query, indexed) ?: 0f }
                ?.takeIf { it >= minScore }
                ?.let { Match(indexed.entry, it) }
        }
        if (matches.isEmpty()) return null
        return pickBestAnswer(matches)
    }

    /**
     * 同一题干在题库里常有多条记录：有的答案是「排除xxx」这类提示，有的才是真实答案。
     * 这里统计所有命中记录里每个答案出现的次数，剔除排除类答案后，取出现最多的那个作为最终答案。
     */
    private fun pickBestAnswer(matches: List<Match>): Match {
        val realAnswers = matches.filter { !isExclusionAnswer(it.entry.answer) }
        val pool = realAnswers.ifEmpty { matches }
        val bestGroup = pool.groupBy { normalize(it.entry.answer) }
            .values
            .maxWithOrNull(
                compareBy<List<Match>> { it.size }
                    .thenBy { group -> group.maxOf { it.score } },
            )
        return bestGroup?.maxByOrNull { it.score } ?: matches.first()
    }

    /** 「排除xxx」「不选xxx」这类答案只是提示不是真实答案，命中后应被筛掉。 */
    private fun isExclusionAnswer(answer: String): Boolean {
        val trimmed = answer.trim()
        return trimmed.contains("排除") || trimmed.contains("不选")
    }

    private fun index(): Index {
        val signature = store.signature()
        cached?.takeIf { it.signature == signature }?.let { return it }
        val entries = store.entries().map { entry ->
            val question = normalize(entry.question)
            IndexedEntry(
                entry = entry,
                normalizedQuestion = question,
                normalizedAnswer = normalize(entry.answer),
                questionGrams = grams(question),
            )
        }
        return Index(signature, entries).also { cached = it }
    }

    private fun score(query: String, indexed: IndexedEntry): Float? {
        val question = indexed.normalizedQuestion
        val answer = indexed.normalizedAnswer
        if (indexed.entry.answer.isBlank()) return null

        // 短题干（如「首日补丁」，少于词袋匹配所需长度）仍可通过「OCR 文本包含题干」精确命中。
        if (question.length >= MIN_QUESTION_LENGTH && (query.contains(question) || question.contains(query))) {
            return 0.99f
        }

        if (question.length < MIN_QUERY_LENGTH) return null

        val queryGrams = grams(query)
        val questionGrams = indexed.questionGrams
        if (queryGrams.isEmpty() || questionGrams.isEmpty()) return null

        val common = queryGrams.count { it in questionGrams }
        if (common < MIN_COMMON_GRAMS) return null
        val longest = longestCommonSubstring(query, question)
        val queryCoverage = common.toFloat() / queryGrams.size
        val bankCoverage = common.toFloat() / questionGrams.size
        val longestCoverage = longest.toFloat() / minOf(query.length, question.length).coerceAtLeast(1)
        val answerMentioned = answer.length >= 2 && answer in query
        val dice = (2f * common) / (queryGrams.size + questionGrams.size).coerceAtLeast(1)

        // A truncated OCR fragment can cover only part of the bank question,
        // so coverage of the shorter text matters more than full-string equality.
        val score = (
            queryCoverage * 0.40f +
                bankCoverage * 0.20f +
                longestCoverage * 0.25f +
                dice * 0.15f +
                if (answerMentioned) 0.08f else 0f
            ).coerceAtMost(1f)

        val strongEnough = longest >= MIN_LONGEST_MATCH && common >= MIN_COMMON_GRAMS &&
            (queryCoverage >= 0.42f || bankCoverage >= 0.16f)
        return score.takeIf { strongEnough }
    }

    private fun queryVariants(raw: String): List<String> = buildSet {
        add(normalize(raw))
        raw.lineSequence().map(::normalize).forEach(::add)

        // OCR may return the whole screen as one line. Short windows let the
        // matcher isolate a question from status text and answer choices.
        val whole = normalize(raw)
        WINDOW_SIZES.forEach { size ->
            if (whole.length > size) {
                val step = size / 2
                var start = 0
                while (start + MIN_QUERY_LENGTH <= whole.length) {
                    add(whole.substring(start, minOf(start + size, whole.length)))
                    start += step
                }
            }
        }
    }.filter { it.length >= MIN_QUERY_LENGTH }

    private fun grams(text: String): Set<String> = buildSet {
        val sizes = if (text.any { it.code in 0x4E00..0x9FFF }) listOf(2, 3, 4) else listOf(3, 4)
        sizes.forEach { size ->
            if (text.length >= size) {
                for (index in 0..text.length - size) add(text.substring(index, index + size))
            }
        }
    }

    private fun longestCommonSubstring(first: String, second: String): Int {
        if (first.isEmpty() || second.isEmpty()) return 0
        var previous = IntArray(second.length + 1)
        var longest = 0
        first.forEach { firstChar ->
            val current = IntArray(second.length + 1)
            second.forEachIndexed { index, secondChar ->
                if (firstChar == secondChar) {
                    current[index + 1] = previous[index] + 1
                    longest = max(longest, current[index + 1])
                }
            }
            previous = current
        }
        return longest
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .filter { it.isLetterOrDigit() || it.code in 0x4E00..0x9FFF }

    data class Match(
        val entry: QuestionBankEntry,
        val score: Float,
    )

    private data class Index(
        val signature: String,
        val entries: List<IndexedEntry>,
    )

    private data class IndexedEntry(
        val entry: QuestionBankEntry,
        val normalizedQuestion: String,
        val normalizedAnswer: String,
        val questionGrams: Set<String>,
    )

    private companion object {
        const val MIN_QUERY_LENGTH = 8
        const val MIN_QUESTION_LENGTH = 2
        const val MIN_LONGEST_MATCH = 5
        const val MIN_COMMON_GRAMS = 4
        const val MIN_SCORE = 0.50f
        val WINDOW_SIZES = intArrayOf(16, 24, 32, 48)
    }
}
