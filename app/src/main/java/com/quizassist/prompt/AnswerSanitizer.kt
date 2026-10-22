package com.quizassist.prompt

/**
 * 答案清洗与校验。
 *
 * 从模型输出里捞出真正的答案，并做两层体检：
 *  - 候选一致性：模型左右互搏时别乱猜
 *  - 选项目录：答案字母必须真的出现在 OCR 出来的选项里
 *
 * 这些逻辑原来散在 OverlayController 里，双采样也要用，抽出来统一。
 */
object AnswerSanitizer {

    /** 写了推理就必须单独一行给的锚点。 */
    private val ANCHOR = Regex("""(?i)\bANSWER\s*[:：]\s*([^\n]{1,30})""")

    /** 「答案：B」「答案是 B」「正确答案为 B」 */
    private val LABELLED = Regex(
        """(?:最终答案|正确答案|答案)\s*(?:是|为|[:：])?\s*([A-D](?:\s*[,，、/]\s*[A-D])*|正确|错误|对|错|√|×|[^\n。；;，,]{1,30})""",
    )

    /** 出现这些词，说明前面那个被推翻了。 */
    private val CONTRADICTION = Regex("""(?:但|不过|可是|其实|应该是|改为|修正|不对|推翻|重新看)""")

    /** 明显的自言自语开头。 */
    private val THINKING_HINT = Regex(
        """^(?:我们需要|我们|首先|接下来|然后|分析|根据题目|题干|用户|需要|先|让我|好的|嗯|这个题目|考虑到|综上|因此|所以要求|按照要求之)""",
    )

    /** 单个字母、字母组合、判断词，或者很短的选项式文本。 */
    private val ANSWER_SHAPE = Regex(
        """^(?:[A-D](?:\s*[,，、/]\s*[A-D])*|[A-D][\.、．:：]\s*\S{0,20}|正确|错误|对|错|√|×|T|F|true|false|[^\p{Punct}\s]{1,12})$""",
    )

    const val UNCERTAIN = "AI 拿不准，重拍一次"
    const val NO_ANSWER = "没抓到答案，重拍一次"

    /**
     * 从模型输出里捞出答案。
     *
     * 优先级：锚点 → JSON → 标记候选（一致性校验）→ 答案形态行 → 去噪后的末行。
     * 拿不准就返回空串，调用方负责给用户一个明确提示，**绝不把思考过程当答案**。
     */
    fun extract(raw: String): String {
        val text = raw.replace(Regex("""(?i)\bassistant\b[:：]?\s*"""), "").trim()
        if (text.isEmpty()) return ""

        // 1) 锚点
        ANCHOR.findAll(text).lastOrNull()
            ?.groupValues?.getOrNull(1)?.trim()?.stripLabel()
            ?.takeIf { it.isNotBlank() }?.let { return it }

        // 2) JSON（联网模式），截断也能捞
        Regex("\"answer\"\\s*:\\s*\"([^\"]{1,40})\"")
            .findAll(text).lastOrNull()?.groupValues?.getOrNull(1)?.trim()
            ?.stripLabel()?.takeIf { it.isNotBlank() }?.let { return it }

        // 3) 标记候选：全部收集后判一致性
        val labelled = LABELLED.findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim()?.stripLabel() }
            .filter { it.isNotBlank() }
            .toList()
        if (labelled.isNotEmpty()) {
            val distinct = labelled.map { normalize(it) }.distinct()
            if (distinct.size == 1) return labelled.last()
            return if (CONTRADICTION.containsMatchIn(text)) labelled.last() else ""
        }

        val lines = text.lines().map { it.trim().stripLabel() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""

        // 4) 像答案的独立行
        lines.lastOrNull { ANSWER_SHAPE.matches(it) }?.let { return it }

        // 5) 去掉自言自语后的末行
        val meaningful = lines.filterNot { THINKING_HINT.containsMatchIn(it) }
        val last = meaningful.lastOrNull() ?: return ""
        return if (last.length > 30) "" else last
    }

    /** 两个答案是不是同一个（A 与 A.xxx 视为相同）。 */
    fun same(a: String, b: String): Boolean = normalize(a) == normalize(b)

    private fun normalize(value: String): String {
        val letters = Regex("[A-Da-d]").findAll(value).map { it.value.uppercase() }.toList()
        if (letters.size == 1 && value.trim().length <= 4) return letters.first()
        if (letters.size >= 2 && value.trim().length <= 8) return letters.sorted().joinToString(",")
        return value.trim().lowercase()
    }

    private fun String.stripLabel(): String =
        replace(Regex("""^(答案|答|选|选择|正确选项|解析|理由|依据|分析)\s*[:：]\s*"""), "").trim()

    /**
     * 选项白名单校验。
     *
     * 答案是个字母，但 OCR 里根本没出现过这个选项 —— 要么 OCR 漏了，要么模型编了。
     * 题库命中、判断题、填空题都不适用，只校验「纯字母答案」。
     *
     * @return 有问题时返回提示文案，没问题返回空
     */
    fun checkOption(answer: String, ocrText: String): String {
        val value = answer.trim()
        val letters = Regex("^[A-Da-d](?:\\s*[,，、/]\\s*[A-Da-d])*$")
            .takeIf { it.matches(value) }
            ?.let { Regex("[A-Da-d]").findAll(value).map { g -> g.value.uppercase() }.toSet() }
            ?: return ""

        val detected = PromptFactory.detectOptionLetters(ocrText).toSet()
        if (detected.isEmpty()) return ""   // 压根没识别到选项，不怪模型
        val missing = letters - detected
        if (missing.isEmpty()) return ""

        return "答案 " + missing.sorted().joinToString(",") + " 不在识别到的选项里（" +
            detected.sorted().joinToString(",") + "），可能 OCR 没拍全"
    }

    /** 「85%」→ 85；解析不出来返回 -1。 */
    fun parseConfidence(text: String): Int =
        Regex("""(\d{1,3})\s*%?""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: -1
}
