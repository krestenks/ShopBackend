package tools

import llm.ModelToolSpec

interface Tool {
    val name: String
    val description: String
    val jsonSchema: String

    fun spec(): ModelToolSpec = ModelToolSpec(
        name = name,
        description = description,
        jsonSchema = jsonSchema
    )

    suspend fun invoke(argumentsJson: String): String
}
