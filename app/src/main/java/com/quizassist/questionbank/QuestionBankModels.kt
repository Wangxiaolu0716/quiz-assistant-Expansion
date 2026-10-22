package com.quizassist.questionbank

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

@Serializable
data class QuestionBankEntry(
    val id: Int = 0,
    val question: String = "",
    val answer: String = "",
    @SerialName("question_confidence") val questionConfidence: String = "",
    @SerialName("answer_confidence") val answerConfidence: String = "",
)

/**
 * 题库解析：不要求用户按某个固定模板准备文件，尽量自己认。
 *
 * 支持的形态：
 *  - JSON：数组，或 `{questions:[...]}` / `{data:[...]}`；键名认 question/题目/q/content/题干 与 answer/答案/a
 *  - 表格：逗号 / 制表符（Excel 复制出来就是它）/ 分号 / 竖线，带不带表头都行
 *  - 纯文本：`题目：xxx` + `答案：yyy` 成块，或两行一组
 *
 * 列不听话时用打分法兜底：越短的越像答案（A、正确、√），越长的越像题干。
 * 三列里没标「选项」的那列如果内容像 A.xxx B.xxx，会被拼进题干，方便匹配。
 */
object QuestionBankParser {
    private val json = Json { ignoreUnknownKeys = true }

    enum class Format(val label: String) {
        Json("JSON"),
        Csv("CSV（逗号）"),
        Tsv("制表符表格（Excel 粘贴）"),
        Pipe("竖线表格"),
        Semicolon("分号表格"),
        Text("纯文本"),
    }

    data class Result(
        val entries: List<QuestionBankEntry>,
        val format: Format,
        /** 识别出的列角色，例如「题目 / 选项 / 答案」，给 UI 回显用。 */
        val columns: String = "",
    )

    private val QUESTION_KEYS = listOf("question", "题目", "题干", "问题", "试题", "content", "title", "q")
    private val ANSWER_KEYS = listOf("answer", "答案", "正确选项", "结果", "a")
    private val OPTION_KEYS = listOf("选项", "options", "option", "choices", "choice", "选择项")

    /** 兼容旧调用：只关心拿到的条目。 */
    fun parse(content: String, csv: Boolean = false): List<QuestionBankEntry> = parseAuto(content).entries

    // ---------------------------------------------------------------- 入口

    fun parseAuto(raw: String): Result {
        val content = raw.removePrefix("\uFEFF").trim()
        if (content.isBlank()) return Result(emptyList(), Format.Text)

        // 1) JSON
        if (content.startsWith("[") || content.startsWith("{")) {
            val parsed = runCatching { parseJson(content) }.getOrNull()
            if (!parsed.isNullOrEmpty()) {
                return Result(parsed, Format.Json, "question / answer")
            }
        }

        // 2) 分隔表格
        val delimiter = detectDelimiter(content)
        if (delimiter != null) {
            val rows = readRows(content, delimiter.char)
            if (rows.size >= 1) {
                val table = parseTable(rows)
                if (table.entries.isNotEmpty()) {
                    return Result(table.entries, delimiter.format, table.columns)
                }
            }
        }

        // 3) 纯文本
        val text = parseText(content)
        return Result(text, Format.Text, "题目 / 答案")
    }

    // ---------------------------------------------------------------- JSON

    private fun parseJson(content: String): List<QuestionBankEntry> {
        val element = json.parseToJsonElement(content)
        val objectElement = runCatching { element.jsonObject }.getOrNull()
        val array: JsonArray = when {
            element is JsonArray -> element
            objectElement?.get("questions") is JsonArray -> objectElement["questions"]!!.jsonArray
            objectElement?.get("data") is JsonArray -> objectElement["data"]!!.jsonArray
            objectElement?.get("list") is JsonArray -> objectElement["list"]!!.jsonArray
            else -> return emptyList()
        }
        return array.mapIndexedNotNull { index, item ->
            // 先试标准结构，失败再按别名键名硬取，兼容别人整理的表
            runCatching { json.decodeFromJsonElement(QuestionBankEntry.serializer(), item) }
                .getOrNull()
                ?.takeIf { it.question.isNotBlank() && it.answer.isNotBlank() }
                ?: runCatching {
                    val obj = item.jsonObject
                    val question = pick(obj, QUESTION_KEYS)
                    val answer = pick(obj, ANSWER_KEYS)
                    if (question.isBlank() || answer.isBlank()) {
                        null
                    } else {
                        QuestionBankEntry(index + 1, question, answer)
                    }
                }.getOrNull()
        }
    }

    private fun pick(obj: JsonObject, keys: List<String>): String {
        val normalized = obj.keys.associateBy { it.trim().lowercase() }
        keys.forEach { key ->
            normalized[key]?.let { real ->
                val value = obj[real]
                if (value is JsonPrimitive) return value.content.trim()
            }
        }
        return ""
    }

    // ---------------------------------------------------------------- 分隔符 / 行解析

    private data class Delimiter(val char: Char, val format: Format)

    /**
     * 猜分隔符：取前 12 行，看哪种分隔符能把每行切成一致的 ≥2 列。
     * 制表符优先（Excel 粘贴最可靠），其次逗号。
     */
    private fun detectDelimiter(content: String): Delimiter? {
        val sample = content.lineSequence().take(12).filter { it.isNotBlank() }.toList()
        if (sample.isEmpty()) return null
        val candidates = listOf(
            Delimiter('\t', Format.Tsv),
            Delimiter(',', Format.Csv),
            Delimiter('|', Format.Pipe),
            Delimiter(';', Format.Semicolon),
        )
        var best: Delimiter? = null
        var bestScore = 0
        candidates.forEach { candidate ->
            val counts = sample.map { countOutsideQuotes(it, candidate.char) }
            val multi = counts.count { it >= 1 }
            if (multi < sample.size * 0.6) return@forEach
            val frequent = counts.groupingBy { it }.eachCount().maxByOrNull { it.value }?.value ?: 0
            val score = frequent * 10 + multi
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    private fun countOutsideQuotes(line: String, delimiter: Char): Int {
        var quoted = false
        var count = 0
        line.forEach { char ->
            when {
                char == '"' -> quoted = !quoted
                char == delimiter && !quoted -> count++
            }
        }
        return count
    }

    /** 通用分隔符版 CSV 读取（支持双引号包裹与 "" 转义）。 */
    private fun readRows(content: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < content.length) {
            val char = content[index]
            when {
                char == '"' && quoted && index + 1 < content.length && content[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == delimiter && !quoted -> {
                    row.add(field.toString())
                    field.clear()
                }
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && index + 1 < content.length && content[index + 1] == '\n') index++
                    row.add(field.toString())
                    field.clear()
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = mutableListOf()
                }
                else -> field.append(char)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            if (row.any { it.isNotBlank() }) rows.add(row)
        }
        return rows
    }

    // ---------------------------------------------------------------- 表格列识别

    private data class TableResult(val entries: List<QuestionBankEntry>, val columns: String)

    private fun parseTable(rows: List<List<String>>): TableResult {
        if (rows.isEmpty()) return TableResult(emptyList(), "")

        val headerRow = rows.first()
        val allKeys = QUESTION_KEYS + ANSWER_KEYS + OPTION_KEYS
        val headerHasKeyword = headerRow.any { headerMatches(it, allKeys) }
        val data = if (headerHasKeyword && rows.size > 1) rows.drop(1) else rows
        if (data.isEmpty()) return TableResult(emptyList(), "")

        val columnCount = data.maxOf { it.size }
        if (columnCount < 2) return TableResult(emptyList(), "")

        // 有表头就按关键词定位，没有就靠打分猜
        var questionCol = if (headerHasKeyword) indexOfHeader(headerRow, QUESTION_KEYS) else -1
        var answerCol = if (headerHasKeyword) indexOfHeader(headerRow, ANSWER_KEYS) else -1
        var optionCol = if (headerHasKeyword) indexOfHeader(headerRow, OPTION_KEYS) else -1

        val sample = data.take(30)
        if (questionCol < 0 || answerCol < 0 || questionCol == answerCol) {
            val guessed = guessColumns(sample, columnCount)
            if (questionCol < 0) questionCol = guessed.first
            if (answerCol < 0) answerCol = guessed.second
        }
        if (questionCol < 0 || answerCol < 0 || questionCol == answerCol) {
            // 两列兜底：左题右答
            questionCol = 0
            answerCol = 1
        }

        val entries = data.mapIndexedNotNull { index, row ->
            val question = row.getOrNull(questionCol).orEmpty().trim()
            var answer = row.getOrNull(answerCol).orEmpty().trim()
            if (answer.isBlank()) return@mapIndexedNotNull null

            // 选项列：拼进题干，匹配和模型都能用上
            val options = optionCol.takeIf { it >= 0 && it != questionCol && it != answerCol }
                ?.let { row.getOrNull(it).orEmpty().trim() }
                .orEmpty()

            // 没标选项但有三列以上：其它列如果像 “A.xxx B.xxx” 也拼进去
            val extraOptions = if (options.isBlank()) {
                row.filterIndexed { i, cell ->
                    i != questionCol && i != answerCol && looksLikeOptions(cell)
                }.joinToString(" ").trim()
            } else {
                ""
            }

            val fullQuestion = listOf(question, options.ifBlank { extraOptions })
                .filter { it.isNotBlank() }
                .joinToString("\n")
            if (fullQuestion.isBlank()) return@mapIndexedNotNull null

            QuestionBankEntry(index + 1, fullQuestion, answer)
        }

        val columns = buildList {
            add(if (questionCol >= 0) "题目" else "?")
            if (optionCol > questionCol && optionCol < answerCol) add("选项")
            if (answerCol >= 0) add("答案")
        }.joinToString(" / ")

        return TableResult(entries, columns)
    }

    private fun indexOfHeader(row: List<String>, keys: List<String>): Int =
        row.indexOfFirst { headerMatches(it, keys) }

    /**
     * 判断一个单元格是不是表头。
     *
     * 两个约束缺一不可：
     *  - 表头都很短，超过 12 个字基本是数据行；
     *  - 长度 ≤2 的关键词（`a`、`q`）必须完全相等才算，否则
     *    「A.派蒙 B.钟离 …」这种选项串会因为含字母 a 被误判成表头，
     *    进而把整张表按表头规则解析，答案列直接取错。
     */
    private fun headerMatches(cell: String, keys: List<String>): Boolean {
        val n = normalizeHeader(cell)
        if (n.isEmpty() || n.length > 12) return false
        return keys.any { key -> if (key.length <= 2) n == key else n.contains(key) }
    }

    /**
     * 无表头时猜列：答案列取「像答案」得分最高的，题目列取「像题目」得分最高的。
     * 像答案 = 短（A / 正确 / √ / 三五个字）；像题目 = 长、带问号或括号。
     */
    private fun guessColumns(sample: List<List<String>>, columnCount: Int): Pair<Int, Int> {
        val answerScores = (0 until columnCount).map { col -> averageScore(sample, col, ::answerLikeness) }
        val questionScores = (0 until columnCount).map { col -> averageScore(sample, col, ::questionLikeness) }

        val answerCol = answerScores.indices.maxByOrNull { answerScores[it] } ?: 1
        val questionCol = questionScores.indices
            .filter { it != answerCol }
            .maxByOrNull { questionScores[it] }
            ?: 0
        return questionCol to answerCol
    }

    private fun averageScore(
        sample: List<List<String>>,
        column: Int,
        scorer: (String) -> Double,
    ): Double {
        var total = 0.0
        var count = 0
        sample.forEach { row ->
            val cell = row.getOrNull(column).orEmpty().trim()
            if (cell.isEmpty()) return@forEach
            total += scorer(cell)
            count++
        }
        return if (count == 0) -1.0 else total / count
    }

    private fun answerLikeness(text: String): Double {
        val t = text.trim()
        if (t.isEmpty()) return 0.0
        return when {
            t.matches(Regex("^[A-Da-d](\\s*[,，、/]\\s*[A-Da-d])*$")) -> 4.0
            t.matches(Regex("^[A-Da-d]$")) -> 4.0
            t in ANSWER_WORDS -> 3.5
            t.matches(Regex("^[A-Da-d][\\.、．:：].{0,6}$")) -> 2.0
            t.length <= 6 -> 1.6
            t.length <= 14 -> 0.6
            t.length <= 24 -> -0.4
            else -> -1.2
        }
    }

    private fun questionLikeness(text: String): Double {
        val t = text.trim()
        if (t.isEmpty()) return 0.0
        var score = t.length / 60.0
        if (t.contains('？') || t.contains('?')) score += 0.6
        if (t.contains('（') || t.contains('(')) score += 0.2
        if (looksLikeOptions(t)) score += 0.4
        return score
    }

    private fun looksLikeOptions(text: String): Boolean {
        val t = text.trim()
        if (t.length < 4) return false
        val hits = Regex("[A-Da-d][\\.、．:：)）]").findAll(t).count()
        return hits >= 2
    }

    private fun normalizeHeader(value: String): String {
        val trimmed = value.removePrefix("\uFEFF").trim()
        val cut = trimmed.indexOfFirst { it == '(' || it == '（' || it == '[' || it == '【' }
        val base = if (cut >= 0) trimmed.substring(0, cut) else trimmed
        return base.replace(Regex("\\s+"), "").lowercase()
    }

    private val ANSWER_WORDS = setOf(
        "正确", "错误", "对", "错", "是", "否", "√", "×", "✓", "✗",
        "true", "false", "t", "f", "yes", "no",
    )

    // ---------------------------------------------------------------- 纯文本

    /**
     * 纯文本两种排法：
     *   1) 带标记：`题目：xxx` 后面跟 `答案：yyy`（也认 Q/A、问题、答）
     *   2) 无标记：一行题目一行答案交替
     */
    private fun parseText(content: String): List<QuestionBankEntry> {
        val lines = content.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        val hasMarkers = lines.any { QUESTION_MARKER.containsMatchIn(it) || ANSWER_MARKER.containsMatchIn(it) }
        if (hasMarkers) {
            val entries = mutableListOf<QuestionBankEntry>()
            var question: String? = null
            lines.forEach { line ->
                val q = QUESTION_MARKER.find(line)?.let { line.substring(it.range.last + 1).trim() }
                if (q != null && q.isNotBlank()) {
                    question = q
                    return@forEach
                }
                val a = ANSWER_MARKER.find(line)?.let { line.substring(it.range.last + 1).trim() }
                if (a != null && a.isNotBlank() && question != null) {
                    entries.add(QuestionBankEntry(entries.size + 1, question!!, a))
                    question = null
                }
            }
            if (entries.isNotEmpty()) return entries
        }

        // 交替行
        val entries = mutableListOf<QuestionBankEntry>()
        var index = 0
        while (index + 1 < lines.size) {
            val question = lines[index]
            val answer = lines[index + 1]
            if (answerLikeness(answer) > 0.4 && question.length >= 2) {
                entries.add(QuestionBankEntry(entries.size + 1, question, answer))
            }
            index += 2
        }
        return entries
    }

    private val QUESTION_MARKER = Regex("^(?:题目|题干|问题|试题|Q|q)\\s*[:：]")
    private val ANSWER_MARKER = Regex("^(?:答案|正确答案|答|A|a)\\s*[:：]")
}
