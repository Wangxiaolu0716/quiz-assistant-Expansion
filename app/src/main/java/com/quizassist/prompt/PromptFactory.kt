package com.quizassist.prompt

import com.quizassist.FeatureFlags
import com.quizassist.PromptOverride
import com.quizassist.RemoteConfig
import com.quizassist.model.AnswerMode
import com.quizassist.model.QuizInput
import com.quizassist.model.SolveMode

/**
 * 服务端下发的运行时参数。
 *
 * 有了自己的服务器之后，提示词和采样参数不用发版就能调：
 * 在 config.json 里写 `prompt` / `features` 字段即可。
 * 这里把远端值缓存成线程安全的静态状态，取不到就回落到内置实现。
 */
object RemoteRuntime {
    @Volatile
    private var prompt: PromptOverride = PromptOverride()

    @Volatile
    private var features: FeatureFlags = FeatureFlags()

    fun apply(config: RemoteConfig) {
        prompt = config.prompt
        features = config.features
    }

    fun flashSystem(): String = prompt.flashSystem.trim()

    fun searchSystem(): String = prompt.searchSystem.trim()

    fun maxTokens(default: Int): Int = prompt.maxTokens.takeIf { it in 16..4096 } ?: default

    fun temperature(default: Double): Double = prompt.temperature?.takeIf { it in 0.0..2.0 } ?: default

    fun disableQuestionBank(): Boolean = features.disableQuestionBank

    fun enableReport(): Boolean = features.enableReport
}

object PromptFactory {

    /**
     * 当前输出风格。悬浮窗空间很小，默认 [AnswerMode.ANSWER]（只给答案）。
     *
     * 由 MainActivity 在读取设置后写入；不依赖 Context，方便 LLMRepository 直接取。
     */
    @Volatile
    private var answerMode: AnswerMode = AnswerMode.ANSWER

    fun setAnswerMode(mode: AnswerMode) {
        answerMode = mode
    }

    fun answerMode(): AnswerMode = answerMode

    /** 推理指引：告诉模型怎么想，但不许把过程写出来。 */
    private const val FLASH_REASONING = """你是答题助手。答案会直接显示在手机悬浮窗上，屏幕空间很小，用户只看答案。

【内部执行，不要写出任何过程】
1. 还原题干：OCR 常把题干、选项、按钮文字混在一起，可能缺标点、有错别字、选项粘连成一行。先按语义还原真实题目。
2. 判定题型：单选 / 多选 / 判断 / 填空 / 无选项简答。
3. 对照选项逐项判断，选出最可能的答案。

【硬性要求】
- 字母只能取自 OCR 文本里真实出现过的选项，绝对不要输出未出现的字母（不能编 E/F）。
- 不要复述题目，不要罗列选项，不要写推理步骤，不要输出「答案：」这类前缀。
- 概率低也要给出最可能的答案；只有完全无法判断时才输出单个问号 ?。"""

    /** 输出契约：只给答案。 */
    private const val CONTRACT_ANSWER = """【输出格式】只输出答案本身，一行，最多 12 个汉字。
- 单选：一个字母，如 A
- 多选：字母用英文逗号连接，如 A,C
- 判断：只写「正确」或「错误」
- 填空：只写要填的内容，多个空用「；」分隔
- 题目没有 A/B/C/D 选项：直接写答案内容
- 不换行、不解释、不加引号、不写标点以外的任何说明"""

    /** 输出契约：答案 + 一行简析。 */
    private const val CONTRACT_BRIEF = """【输出格式】最多两行，全文不超过 40 字。
第一行：答案（写法同上，单选只写字母）
第二行：以「· 」开头，一句话说明依据，不超过 20 字
- 不加空行、不用 markdown、不写「答案：」「解析：」这类标签
- 除这两行外不要有任何多余内容"""

    /** 联网模式：先查证再作答，返回 JSON。 */
    private const val SEARCH_REASONING = """你是 ACG、手游、动漫与综合知识答题专家，当前已开启联网搜索。

【作答流程】
1. 从题干提取可检索的关键实体：作品名、角色名、活动名、版本号、专业术语。
2. 优先检索官方公告、维基百科、攻略站与社区资料，确认真实含义与机制后再作答。
3. 搜索结果与固有印象冲突时，以最新的官方资料为准。
4. OCR 可能有错别字，检索前先纠正明显形近字；纠正不确定时按原文检索。

【答案写法】
- 选择题只写字母（必须来自题目给出的选项），多选如 A,C
- 判断题只写「正确」或「错误」
- 填空题只写要填的内容
- 没有给出选项时直接写答案内容，不要编造字母"""

    private const val SEARCH_JSON_ANSWER =
        """【输出】只输出一行 JSON，不要代码块，不要任何多余文字：
{"answer":"答案","confidence":"0-100%"}"""

    private const val SEARCH_JSON_BRIEF =
        """【输出】只输出一行 JSON，不要代码块，不要任何多余文字：
{"answer":"答案","confidence":"0-100%","reasoning":"不超过20字的依据"}"""

    /**
     * 快速模式系统提示词：推理指引 + 当前模式的输出契约。
     *
     * 远程下发的提示词只有足够完整（>= 60 字）时才整体替换本地版本；
     * 否则只当作补充要求追加。之前后台配了一句「仅显示答案即可」，
     * 把「先还原题干、字母白名单、不许写过程」整套约束全冲掉了，
     * 模型于是开始自言自语，再被 maxTokens 截断 —— 用户看到的就是
     * 「我们需要回答用户：需要只输出一行 json…」然后没了。
     */
    fun flashSystem(): String {
        val remote = RemoteRuntime.flashSystem()
        val base = if (remote.length >= 60) remote else FLASH_REASONING
        val supplement = if (remote.isNotBlank() && remote.length < 60) "\n\n补充要求：$remote" else ""
        return base + supplement + "\n\n" + outputContract(answerMode)
    }

    /** 联网模式系统提示词：同样按模式切换 JSON 契约。 */
    fun searchSystem(): String {
        val base = RemoteRuntime.searchSystem().ifBlank { SEARCH_REASONING }
        val contract = if (answerMode == AnswerMode.BRIEF) SEARCH_JSON_BRIEF else SEARCH_JSON_ANSWER
        return base + "\n\n" + contract
    }

    /** 暴露给单元测试 / 调试用。 */
    fun outputContract(mode: AnswerMode): String =
        if (mode == AnswerMode.BRIEF) CONTRACT_BRIEF else CONTRACT_ANSWER

    /**
     * 用户消息：OCR 文本 + 供模型判断的补充信号。
     * 显式告诉模型「有没有选项字母」，能明显降低瞎编字母的概率。
     */
    fun userPrompt(input: QuizInput): String {
        val ocrText = input.ocr?.text?.trim().orEmpty()
        val letters = detectOptionLetters(ocrText)
        return buildString {
            appendLine("OCR 文本如下：")
            if (ocrText.isNotBlank()) {
                appendLine(ocrText)
            } else {
                appendLine("OCR 不可靠，请根据截图内容判断。")
            }
            appendLine()
            appendLine("补充信息（供判断，不要输出）：")
            if (letters.isNotEmpty()) {
                appendLine("- 检测到选项字母：${letters.joinToString(",")}")
            } else {
                appendLine("- 未检测到 A/B/C/D 选项，请直接给出答案内容")
            }
            if (input.mode == SolveMode.Vision) {
                appendLine("- 已附带题目截图，OCR 有疑问时以截图为准")
            } else {
                appendLine("- 仅文本输入，OCR 可能有形近字与断行噪声")
            }
            appendLine("- 可能混入了界面按钮文字（如「下一题」「提交」「返回」），属于噪声，直接忽略")
            appendLine("- 输出风格：${answerMode.label}（${answerMode.key}）")
        }.trim()
    }

    /** 粗判 OCR 里出现的选项字母，仅用于给模型的补充提示，不作为强校验。 */
    fun detectOptionLetters(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val found = LinkedHashSet<String>()
        Regex("""(?:^|[\s　（(【\[])([A-D])[\s　]*[\.、．:：)）】\]]""", RegexOption.MULTILINE)
            .findAll(text)
            .forEach { found.add(it.groupValues[1].uppercase()) }
        if (found.isEmpty()) {
            Regex("""([A-D])[\s]*[\.、．:：)）]""").findAll(text).forEach { found.add(it.groupValues[1]) }
        }
        return found.sorted()
    }
}
