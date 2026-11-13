package ai.ninerouter.util

import kotlinx.serialization.json.Json

object Json {
    val pretty = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    val compact = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val lenient = Json { isLenient = true; ignoreUnknownKeys = true; encodeDefaults = true }
}
