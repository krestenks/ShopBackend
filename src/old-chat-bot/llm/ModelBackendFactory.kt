package llm

object ModelBackendFactory {
    fun fromEnv(): ModelBackend {
        val baseUrl = System.getenv("LM_BASE_URL") ?: "http://172.30.128.1:1234/v1"
        val model = System.getenv("LM_MODEL") ?: "essentialai/rnj-1"
        val apiKey = System.getenv("LM_API_KEY")

        val backend = System.getenv("MODEL_BACKEND") ?: "lmstudio"

        return when (backend.lowercase()) {
            "lmstudio" -> LmStudioBackend(baseUrl, model, apiKey)
            else -> throw IllegalArgumentException("Unknown MODEL_BACKEND=$backend")
        }
    }
}
