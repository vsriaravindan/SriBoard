// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.ai

import helium314.keyboard.ai.AiPrefs.Provider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI API client that supports:
 * - Google AI (Gemini) — native Gemini API
 * - OpenAI-compatible (Grok, DeepSeek, custom) — chat completions endpoint
 *
 * API key is ONLY sent to the configured endpoint. Never logged, never transmitted elsewhere.
 */
object AiApiClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val TIMEOUT_MS = 90_000

    data class AiResult(
        val success: Boolean,
        val text: String = "",
        val errorMessage: String = ""
    )

    /**
     * Call the configured AI provider with [prompt] and [text].
     * The API key is read from SharedPreferences and sent ONLY to the provider's endpoint.
     */
    fun generate(provider: Provider, apiKey: String, model: String, endpoint: String, prompt: String, text: String): AiResult {
        if (apiKey.isBlank()) {
            return AiResult(false, errorMessage = "API key not configured")
        }

        return when (provider) {
            Provider.GOOGLE_AI -> callGemini(apiKey, model, prompt, text)
            else -> callOpenAICompatible(provider, apiKey, model, endpoint, prompt, text)
        }
    }

    /**
     * Google AI Gemini API — key goes in URL query param
     */
    private fun callGemini(apiKey: String, model: String, prompt: String, text: String): AiResult {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true

            val requestBody = buildGeminiRequest(prompt, text)
            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(requestBody)
            writer.flush()
            writer.close()

            val responseCode = conn.responseCode
            val responseBody = if (responseCode in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream)).readText()
            } else {
                val err = BufferedReader(InputStreamReader(conn.errorStream)).readText()
                return AiResult(false, errorMessage = parseGeminiError(err, responseCode))
            }
            conn.disconnect()

            val result = parseGeminiResponse(responseBody)
            if (result != null) AiResult(true, result)
            else AiResult(false, errorMessage = "Empty response from Gemini")
        } catch (e: java.net.UnknownHostException) {
            AiResult(false, errorMessage = "No network connection")
        } catch (e: java.net.SocketTimeoutException) {
            AiResult(false, errorMessage = "Request timed out")
        } catch (e: Exception) {
            AiResult(false, errorMessage = e.message ?: "Unknown error")
        }
    }

    private fun buildGeminiRequest(prompt: String, text: String): String {
        return """{"contents":[{"parts":[{"text":"${escapeJson(prompt)}\n\n${escapeJson(text)}"}]}]}"""
    }

    private fun parseGeminiResponse(body: String): String? {
        try {
            // Simple JSON parsing to extract candidates[0].content.parts[0].text
            val candidates = extractJsonArray(body, "\"candidates\"")
            if (candidates == "[]" || candidates == null) return null
            val content = extractJsonObject(candidates, "\"content\"") ?: return null
            val parts = extractJsonArray(content, "\"parts\"") ?: return null
            val firstPart = extractFirstArrayElement(parts) ?: return null
            return extractJsonStringValue(firstPart, "\"text\"")
        } catch (_: Exception) {
            return null
        }
    }

    private fun parseGeminiError(body: String, code: Int): String {
        try {
            val msg = extractJsonStringValue(body, "\"message\"")
            return if (msg != null) msg else "HTTP $code"
        } catch (_: Exception) {
            return "HTTP $code"
        }
    }

    /**
     * OpenAI-compatible API — key goes in Authorization header.
     * Used by: Grok, DeepSeek Flash, DeepSeek Pro, Custom OpenAI-compatible
     */
    private fun callOpenAICompatible(
        provider: Provider,
        apiKey: String,
        model: String,
        endpoint: String,
        prompt: String,
        text: String
    ): AiResult {
        val baseUrl = if (endpoint.isBlank()) provider.defaultEndpoint() else endpoint
        if (baseUrl.isBlank()) {
            return AiResult(false, errorMessage = "No endpoint configured")
        }

        return try {
            val url = URL("$baseUrl/chat/completions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true

            val requestBody = buildOpenAiRequest(model, prompt, text)
            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(requestBody)
            writer.flush()
            writer.close()

            val responseCode = conn.responseCode
            val responseBody = if (responseCode in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream)).readText()
            } else {
                val err = BufferedReader(InputStreamReader(conn.errorStream)).readText()
                return AiResult(false, errorMessage = parseOpenAiError(err, responseCode))
            }
            conn.disconnect()

            val result = parseOpenAiResponse(responseBody)
            if (result != null) AiResult(true, result.trim())
            else AiResult(false, errorMessage = "Empty response from API")
        } catch (e: java.net.UnknownHostException) {
            AiResult(false, errorMessage = "No network connection")
        } catch (e: java.net.SocketTimeoutException) {
            AiResult(false, errorMessage = "Request timed out")
        } catch (e: Exception) {
            AiResult(false, errorMessage = e.message ?: "Unknown error")
        }
    }

    private fun buildOpenAiRequest(model: String, prompt: String, text: String): String {
        return """{"model":"${escapeJson(model)}","messages":[{"role":"system","content":"${escapeJson(prompt)}"},{"role":"user","content":"${escapeJson(text)}"}],"temperature":0.3}"""
    }

    private fun parseOpenAiResponse(body: String): String? {
        try {
            val choices = extractJsonArray(body, "\"choices\"") ?: return null
            val firstChoice = extractFirstArrayElement(choices) ?: return null
            val message = extractJsonObject(firstChoice, "\"message\"") ?: return null
            return extractJsonStringValue(message, "\"content\"")
        } catch (_: Exception) {
            return null
        }
    }

    private fun parseOpenAiError(body: String, code: Int): String {
        try {
            val msg = extractJsonStringValue(body, "\"message\"")
            return if (msg != null) msg else "HTTP $code"
        } catch (_: Exception) {
            return "HTTP $code"
        }
    }

    // --- Minimal JSON helpers (no dependency needed) ---

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun extractJsonArray(json: String, key: String): String? {
        val idx = json.indexOf(key)
        if (idx == -1) return null
        val start = json.indexOf('[', idx)
        if (start == -1) return null
        var depth = 0
        var inStr = false
        var escaped = false
        for (i in start until json.length) {
            val c = json[i]
            if (escaped) { escaped = false; continue }
            if (c == '\\' && inStr) { escaped = true; continue }
            if (c == '"') { inStr = !inStr; continue }
            if (inStr) continue
            when (c) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return json.substring(start, i + 1) }
            }
        }
        return null
    }

    private fun extractJsonObject(json: String, key: String): String? {
        val idx = json.indexOf(key)
        if (idx == -1) return null
        val start = json.indexOf('{', idx)
        if (start == -1) return null
        var depth = 0
        var inStr = false
        var escaped = false
        for (i in start until json.length) {
            val c = json[i]
            if (escaped) { escaped = false; continue }
            if (c == '\\' && inStr) { escaped = true; continue }
            if (c == '"') { inStr = !inStr; continue }
            if (inStr) continue
            when (c) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return json.substring(start, i + 1) }
            }
        }
        return null
    }

    private fun extractJsonStringValue(json: String, key: String): String? {
        val idx = json.indexOf(key)
        if (idx == -1) return null
        val colon = json.indexOf(':', idx + key.length)
        if (colon == -1) return null
        val start = json.indexOf('"', colon)
        if (start == -1) return null
        val sb = StringBuilder()
        var escaped = false
        for (i in (start + 1) until json.length) {
            val c = json[i]
            if (escaped) { sb.append(c); escaped = false; continue }
            if (c == '\\') { escaped = true; continue }
            if (c == '"') break
            sb.append(c)
        }
        return sb.toString()
    }

    private fun extractFirstArrayElement(array: String): String? {
        val trimmed = array.trim()
        if (!trimmed.startsWith('[')) return null
        val start = trimmed.indexOfAny(charArrayOf('{', '"'))
        if (start == -1) return trimmed.substring(1, trimmed.length - 1).trim().trim('"')
        val endChar = if (trimmed[start] == '{') '}' else '"'
        var depth = 0
        var inStr = false
        var escaped = false
        for (i in start until trimmed.length) {
            val c = trimmed[i]
            if (escaped) { escaped = false; continue }
            if (c == '\\' && inStr) { escaped = true; continue }
            if (c == '"') { inStr = !inStr; continue }
            if (inStr) continue
            when (c) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0 && endChar == '}') return trimmed.substring(start, i + 1) }
            }
        }
        return null
    }
}
