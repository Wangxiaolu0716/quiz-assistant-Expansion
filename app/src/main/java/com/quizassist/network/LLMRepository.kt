package com.quizassist.network

import com.quizassist.model.ProviderConfig
import com.quizassist.model.QuizInput
import com.quizassist.model.SolveMode
import com.quizassist.model.StructuredAnswer
import com.quizassist.prompt.PromptFactory
import com.quizassist.prompt.RemoteRuntime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class LLMRepository(
    private val client: OkHttpClient = defaultClient(),
) {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    fun streamAnswer(
        provider: ProviderConfig,
        input: QuizInput,
    ): Flow<String> = flow {
        require(provider.apiKey.isNotBlank()) { "API Key 为空，请先在设置中填写 API Key。" }
        val useResponsesSearch = provider.enableSearchHint
        val requests = if (useResponsesSearch) {
            listOf(buildResponsesRequest(provider, input))
        } else if (provider.modelName.isDeepSeekModel()) {
            listOf(
                buildChatRequest(provider, input, disableThinking = true),
                buildChatRequest(provider, input, disableThinking = false),
            )
        } else {
            listOf(buildChatRequest(provider, input))
        }
        var requestIndex = 0
        while (requestIndex < requests.size) {
            val request = requests[requestIndex]
            try {
                if (useResponsesSearch) {
                    executeStreamingRequest(request, responsesMode = true) { token -> emit(token) }
                } else {
                    val full = executeBlockingRequest(request, timeoutSeconds = FLASH_TIMEOUT_SECONDS)
                    if (full.isNotBlank()) emit(full)
                }
                return@flow
            } catch (t: Throwable) {
                if (!useResponsesSearch && requestIndex == 0 && requests.size > 1 && t.isUnsupportedThinkingParameter()) {
                    requestIndex++
                    continue
                }
                throw t
            }
        }
    }.flowOn(Dispatchers.IO)

    fun parseStructuredAnswer(raw: String): StructuredAnswer {
        val trimmed = raw.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return StructuredAnswer(raw = raw.trim())
        }
        val jsonText = trimmed.substring(start, end + 1)
        return runCatching {
            val element = json.parseToJsonElement(jsonText)
            val obj = element.jsonObject
            StructuredAnswer(
                answer = obj["answer"]?.toText().orEmpty(),
                confidence = obj["confidence"]?.toText().orEmpty(),
                reasoning = obj["reasoning"]?.toText().orEmpty(),
                sources = obj["sources"]?.jsonArray?.map { it.toText() }.orEmpty(),
                raw = raw,
                localCorrect = obj["local_correct"]?.toBooleanValue()
                    ?: obj["is_correct"]?.toBooleanValue(),
            )
        }.getOrElse {
            StructuredAnswer(raw = raw.trim())
        }
    }

    private inline fun executeStreamingRequest(
        request: Request,
        responsesMode: Boolean,
        onToken: (String) -> Unit,
    ) {
        client.newCall(request).execute().use { response ->
            val body = response.body ?: error("LLM response body is empty.")
            if (!response.isSuccessful) {
                val raw = body.string()
                val message = runCatching {
                    json.decodeFromString(ErrorEnvelope.serializer(), raw).error?.message
                }.getOrNull()
                throw LlmHttpException(response.code, message ?: "LLM request failed: HTTP ${response.code}")
            }
            body.source().use { source ->
                var emittedResponsesTextLength = 0
                val plainBody = StringBuilder()
                var sawSseData = false
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: continue
                    if (!line.startsWith("data:")) {
                        if (line.isNotBlank()) plainBody.appendLine(line)
                        continue
                    }
                    sawSseData = true
                    val data = line.removePrefix("data:").trim()
                    if (data.isBlank()) continue
                    if (data == "[DONE]") break
                    if (responsesMode) {
                        val token = parseResponsesToken(data, emittedResponsesTextLength)
                        if (token.isNotEmpty()) {
                            emittedResponsesTextLength += token.length
                            onToken(token)
                        }
                    } else {
                        val token = parseChatToken(data)
                        if (token.isNotEmpty()) onToken(token)
                    }
                }
                if (!sawSseData) {
                    val token = if (responsesMode) {
                        parseResponsesToken(plainBody.toString(), emittedResponsesTextLength)
                    } else {
                        parseChatToken(plainBody.toString())
                    }
                    if (token.isNotEmpty()) onToken(token)
                }
            }
        }
    }

    private fun executeBlockingRequest(request: Request, timeoutSeconds: Long): String {
        val call = client.newCall(request)
        call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
        call.execute().use { response ->
            val body = response.body ?: error("LLM response body is empty.")
            val raw = body.string()
            if (!response.isSuccessful) {
                val message = runCatching {
                    json.decodeFromString(ErrorEnvelope.serializer(), raw).error?.message
                }.getOrNull()
                throw LlmHttpException(response.code, message ?: "LLM request failed: HTTP ${response.code}")
            }
            return parseChatContent(raw)
                .ifBlank { parseChatToken(raw) }
                .ifBlank { raw.trim() }
        }
    }

    private fun buildChatRequest(
        provider: ProviderConfig,
        input: QuizInput,
        disableThinking: Boolean = false,
    ): Request {
        val endpoint = provider.baseUrl.trimEnd('/') + "/chat/completions"
        val system = PromptFactory.flashSystem()
        val messages = listOf(
            ChatMessage("system", JsonPrimitive(system)),
            ChatMessage(
                "user",
                if (provider.modelName.prefersTextOnly()) {
                    textOnlyUserContent(input)
                } else {
                    userContent(input)
                },
            ),
        )
        val payload = ChatCompletionRequest(
            model = provider.modelName,
            messages = messages,
            temperature = RemoteRuntime.temperature(provider.temperature),
            stream = false,
            reasoningEffort = null,
            maxTokens = RemoteRuntime.maxTokens(DEFAULT_MAX_TOKENS),
            thinking = if (disableThinking) {
                ThinkingConfig(type = "disabled")
            } else {
                null
            },
        )
        return jsonPost(endpoint, provider.apiKey, json.encodeToString(payload))
    }

    private fun buildResponsesRequest(provider: ProviderConfig, input: QuizInput): Request {
        val endpoint = provider.baseUrl.trimEnd('/') + "/responses"
        val reasoningEffort = provider.reasoningEffort.trim().takeIf { it.isNotBlank() }
        val payload = ResponsesRequest(
            model = provider.modelName,
            input = listOf(
                ResponseInputMessage(
                    role = "system",
                    content = listOf(ResponseInputContent(type = "input_text", text = PromptFactory.searchSystem())),
                ),
                ResponseInputMessage(
                    role = "user",
                    content = listOf(ResponseInputContent(type = "input_text", text = searchUserText(input))),
                ),
            ),
            tools = listOf(ResponseTool(type = "web_search")),
            reasoning = reasoningEffort?.let { ResponseReasoning(effort = it) },
            stream = true,
            maxOutputTokens = RemoteRuntime.maxTokens(RESPONSES_MAX_OUTPUT_TOKENS),
        )
        return jsonPost(endpoint, provider.apiKey, json.encodeToString(payload))
    }

    private fun jsonPost(endpoint: String, apiKey: String, payload: String): Request =
        Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

    private fun userContent(input: QuizInput) =
        if (input.mode == SolveMode.Vision && input.imageBase64Jpeg != null) {
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", PromptFactory.userPrompt(input))
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") {
                            put("url", "data:image/jpeg;base64,${input.imageBase64Jpeg}")
                        }
                    },
                )
            }
        } else {
            JsonPrimitive(PromptFactory.userPrompt(input))
        }

    private fun textOnlyUserContent(input: QuizInput): JsonPrimitive =
        JsonPrimitive(PromptFactory.userPrompt(input))

    private fun searchUserText(input: QuizInput): String =
        buildString {
            appendLine(PromptFactory.userPrompt(input))
            appendLine()
            appendLine("已启用 Responses API 联网搜索。请优先用题干 OCR 文本检索官方资料、百科、攻略站、社区资料或活动说明。")
            appendLine("注意：本次联网请求只发送文本，不附带截图；如果 OCR 不完整，请基于可识别文字进行保守判断，并降低置信度。")
        }.trim()

    private fun parseChatToken(data: String): String =
        runCatching {
            val element = json.parseToJsonElement(data)
            extractChatText(element)
        }.getOrDefault("")

    private fun parseChatContent(data: String): String =
        runCatching {
            val element = json.parseToJsonElement(data)
            extractChatText(element, allowReasoningFallback = false)
        }.getOrDefault("")

    private fun extractChatText(
        element: JsonElement,
        allowReasoningFallback: Boolean = true,
    ): String {
        val obj = element.jsonObjectOrNull()
        if (obj != null) {
            val choices = obj["choices"]?.jsonArrayOrNull()
            if (!choices.isNullOrEmpty()) {
                choices.forEach { choice ->
                    val choiceObj = choice.jsonObjectOrNull() ?: return@forEach
                    val delta = choiceObj["delta"]?.extractAssistantText(allowReasoningFallback)
                    if (!delta.isNullOrBlank()) return delta
                    val message = choiceObj["message"]?.extractAssistantText(allowReasoningFallback)
                    if (!message.isNullOrBlank()) return message
                    val text = choiceObj["text"]?.jsonPrimitiveOrNull()
                    if (!text.isNullOrBlank()) return text
                }
            }
            val direct = element.extractTopLevelText()
            if (!direct.isNullOrBlank()) return direct
        }
        return ""
    }

    private fun parseResponsesToken(data: String, emittedLength: Int): String =
        runCatching {
            val event = json.decodeFromString(ResponseStreamEvent.serializer(), data)
            when (event.type) {
                "response.output_text.delta", "response.refusal.delta" ->
                    event.delta.orEmpty()
                "response.output_text.done", "response.refusal.done" ->
                    event.text.orEmpty().missingSuffixAfter(emittedLength)
                "response.content_part.done" ->
                    event.part?.text.orEmpty().missingSuffixAfter(emittedLength)
                "response.output_item.done" ->
                    event.item?.outputText().orEmpty().missingSuffixAfter(emittedLength)
                "response.completed" ->
                    event.response?.outputText().orEmpty().missingSuffixAfter(emittedLength)
                else -> event.delta.orEmpty()
                    .ifBlank { event.text.orEmpty() }
                    .ifBlank { event.response?.outputText().orEmpty() }
            }
        }.getOrDefault("")

    private fun ResponseOutputItem.outputText(): String =
        content.mapNotNull { it.text }.joinToString("")

    private fun ResponseCompleted.outputText(): String =
        outputText ?: output.joinToString("") { it.outputText() }

    private fun String.missingSuffixAfter(emittedLength: Int): String =
        if (length > emittedLength) substring(emittedLength) else ""

    private fun JsonElement.toText(): String =
        jsonPrimitive.contentOrNull ?: toString()

    private fun JsonElement.toBooleanValue(): Boolean? =
        jsonPrimitive.booleanOrNull ?: toText().toBooleanStrictOrNull()

    private fun JsonElement.extractAssistantText(allowReasoningFallback: Boolean): String? {
        val obj = jsonObjectOrNull() ?: return jsonPrimitiveOrNull()
        val keys = buildList {
            addAll(listOf(
            "content",
            "text",
            "output_text",
            "answer",
            "delta",
            ))
            if (allowReasoningFallback) add("reasoning_content")
        }
        keys.forEach { key ->
            val value = obj[key]
            val text = value?.jsonPrimitiveOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun JsonElement.extractTopLevelText(): String? {
        val obj = jsonObjectOrNull() ?: return null
        listOf("content", "text", "output_text", "answer").forEach { key ->
            val text = obj[key]?.jsonPrimitiveOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun JsonElement.jsonObjectOrNull() =
        runCatching { jsonObject }.getOrNull()

    private fun JsonElement.jsonArrayOrNull() =
        runCatching { jsonArray }.getOrNull()

    private fun JsonElement.jsonPrimitiveOrNull() =
        runCatching { jsonPrimitive.contentOrNull }.getOrNull()

    private fun String.prefersTextOnly(): Boolean {
        val normalized = lowercase()
        return "deepseek" in normalized ||
            "v4-flash" in normalized ||
            "reasoner" in normalized
    }

    private fun String.isDeepSeekModel(): Boolean = lowercase().contains("deepseek")

    private fun Throwable.isRetryableUpstreamError(): Boolean =
        (this is LlmHttpException && code in setOf(429, 500, 502, 503, 504)) ||
            message?.contains("temporarily unavailable", ignoreCase = true) == true ||
            message?.contains("upstream", ignoreCase = true) == true

    private fun Throwable.isUnsupportedThinkingParameter(): Boolean =
        this is LlmHttpException && code == 400 && (
            message.contains("thinking", ignoreCase = true) ||
                message.contains("unknown parameter", ignoreCase = true) ||
                message.contains("unsupported", ignoreCase = true)
            )

    private class LlmHttpException(
        val code: Int,
        override val message: String,
    ) : RuntimeException(message)

    companion object {
        private const val FLASH_TIMEOUT_SECONDS = 15L

        /**
         * 非联网模式输出上限。
         *
         * 原来是 96 —— 这是「假设模型乖乖只输出两行」的算法。现实是很多模型会先把
         * 「我们需要回答用户：…」这类推理写进正文，96 个 token 全被思考吃光，
         * 答案还没出来就被截断了。给到 640：思考写完还有富余。
         */
        private const val DEFAULT_MAX_TOKENS = 640

        /** 联网模式输出上限：要装下 JSON + 简短依据。 */
        private const val RESPONSES_MAX_OUTPUT_TOKENS = 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(210, TimeUnit.SECONDS)
            .build()
    }
}
