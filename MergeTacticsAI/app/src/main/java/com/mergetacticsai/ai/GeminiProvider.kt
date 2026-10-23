package com.mergetacticsai.ai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Превышен лимит запросов (HTTP 429). */
class RateLimitException(val retryAfterMs: Long) : IOException("Лимит запросов Google (429)")

/** Бесплатный ключ берётся в Google AI Studio. Ключ передаётся в заголовке, не в URL. */
class GeminiProvider(private val apiKey: String, private val model: String = "gemini-2.5-flash") : AIProvider {
    /** Запрос проходит через безопасный режим тестирования: когда он выключен, ничего не меняется. */
    override suspend fun requestPlan(frameJpeg: ByteArray, prompt: String): String =
        com.mergetacticsai.util.FaultSim.gate { realRequest(frameJpeg, prompt) }

    private suspend fun realRequest(frameJpeg: ByteArray, prompt: String): String = withContext(Dispatchers.IO) {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val image = JSONObject().put("inline_data", JSONObject()
            .put("mime_type", "image/jpeg")
            .put("data", Base64.encodeToString(frameJpeg, Base64.NO_WRAP)))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(image).put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", 0).put("responseMimeType", "application/json"))

        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 20000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", apiKey)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 429) {
                val ra = c.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000) ?: 60_000L
                throw RateLimitException(ra)
            }
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault("").take(150)
                throw IOException("Gemini HTTP $code $msg")
            }
            JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        } finally {
            c.disconnect()
        }
    }
}
