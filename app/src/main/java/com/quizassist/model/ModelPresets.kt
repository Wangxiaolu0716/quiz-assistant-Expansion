package com.quizassist.model

data class ModelPreset(
    val label: String,
    val provider: ProviderConfig,
)

object ModelPresets {
    val flash: List<ModelPreset> = listOf(
        ModelPreset(
            label = "dsv4flash",
            provider = ProviderConfig(
                baseUrl = "https://api.deepseek.com/v1/",
                modelName = "dsv4flash",
                temperature = 0.0,
            ),
        ),
        ModelPreset(
            label = "自定义",
            provider = ProviderConfig(baseUrl = "", modelName = ""),
        ),
    )
}
