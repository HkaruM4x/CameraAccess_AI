/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.aistream

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Samples live video frames and sends them to OpenAI GPT-4o for real-time scene analysis.
 *
 * Throttling: at most one API call every [intervalMs] milliseconds. If a call is already
 * in-flight when a new frame arrives, that frame is silently dropped. This keeps API costs
 * low and prevents requests from stacking up.
 *
 * How to use:
 *   val processor = AIAnalystProcessor(apiKey, viewModelScope) { result -> ... }
 *   processor.process(bitmap)   // call every frame — it handles throttling internally
 *   processor.close()           // call when stream stops
 */
internal class AIAnalystProcessor(
    private val apiKey: String,
    private val scope: CoroutineScope,
    private val sessionContext: String = "",
    private val intervalMs: Long = 3_000L,
    private val onAnalyzing: () -> Unit,
    private val onResult: (String) -> Unit,
) {

  companion object {
    private const val TAG = "AIAnalystProcessor"
    private const val API_URL = "https://api.openai.com/v1/chat/completions"
    private const val MODEL = "gpt-4o"

    // Caps the response length — keeps the overlay text short and readable.
    private const val MAX_TOKENS = 120

    // Lower quality = smaller payload = faster upload + cheaper per call.
    // 60 is enough for GPT-4o to understand the scene clearly.
    private const val JPEG_QUALITY = 60

    // "low" tells OpenAI to analyse at 512×512 resolution internally.
    // ~3× cheaper than "high" and perfectly sufficient for scene understanding.
    private const val IMAGE_DETAIL = "low"

    private const val SYSTEM_PROMPT =
        "You are an intelligent assistant with the vision of smart glasses. " +
            "Describe what you see and provide useful insights. " +
            "When food or drink is present, estimate its nutritional profile — " +
            "calories, macros, portion size — even if uncertain. " +
            "Make confident, educated guesses. 2–3 sentences max."
  }

  // One OkHttp client reused for every call — creating a new one per request is expensive.
  private val client =
      OkHttpClient.Builder()
          .connectTimeout(10, TimeUnit.SECONDS)
          .readTimeout(20, TimeUnit.SECONDS)
          .build()

  // Wall-clock time of the last analysis kick-off.
  private var lastAnalysisMs = 0L

  // Prevents launching a new call while one is still in-flight.
  @Volatile private var callInFlight = false

  // ── Public API ────────────────────────────────────────────────────────────

  /**
   * Pass every incoming video frame here. The processor silently drops frames
   * that arrive before the interval has elapsed or while a call is in-flight.
   * Safe to call from any thread.
   */
  fun process(bitmap: Bitmap) {
    val now = System.currentTimeMillis()
    if (now - lastAnalysisMs < intervalMs) return
    if (callInFlight) return

    lastAnalysisMs = now
    callInFlight = true

    // Compress the bitmap NOW, on the calling thread, before launching the
    // coroutine — the bitmap may be recycled by the next frame before the
    // coroutine gets scheduled.
    val jpegBytes = compressBitmap(bitmap)

    onAnalyzing()

    scope.launch(Dispatchers.IO) {
      try {
        val result = callOpenAI(jpegBytes)
        if (result != null) onResult(result)
      } catch (e: Exception) {
        Log.e(TAG, "AI analysis failed", e)
      } finally {
        callInFlight = false
      }
    }
  }

  /** Call when the stream stops to reset internal state. */
  fun close() {
    callInFlight = false
    lastAnalysisMs = 0L
  }

  // ── Private helpers ───────────────────────────────────────────────────────

  private fun compressBitmap(bitmap: Bitmap): ByteArray {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
    return out.toByteArray()
  }

  /**
   * Builds and sends the OpenAI request, then parses the response text.
   * Returns null if the request fails for any reason.
   *
   * The request body is built with Android's built-in JSONObject/JSONArray —
   * no extra JSON library needed for a single API call.
   */
  private fun callOpenAI(jpegBytes: ByteArray): String? {
    val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

    // The user message contains two content parts: the image and a text instruction.
    val imageContent =
        JSONObject().apply {
          put("type", "image_url")
          put(
              "image_url",
              JSONObject().apply {
                put("url", "data:image/jpeg;base64,$base64Image")
                put("detail", IMAGE_DETAIL)
              },
          )
        }

    val userText = buildString {
      append("What do you see? Analyze and share useful insights.")
      if (sessionContext.isNotBlank()) {
        append("\nSession context: $sessionContext")
      }
    }

    val textContent =
        JSONObject().apply {
          put("type", "text")
          put("text", userText)
        }

    val messages =
        JSONArray().apply {
          put(JSONObject().apply {
            put("role", "system")
            put("content", SYSTEM_PROMPT)
          })
          put(JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().apply {
              put(imageContent)
              put(textContent)
            })
          })
        }

    val requestBody =
        JSONObject()
            .apply {
              put("model", MODEL)
              put("max_tokens", MAX_TOKENS)
              put("messages", messages)
            }
            .toString()
            .toRequestBody("application/json".toMediaType())

    val request =
        Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        Log.e(TAG, "OpenAI error ${response.code}: ${response.body?.string()}")
        return null
      }
      val json = JSONObject(response.body?.string() ?: return null)
      return json
          .getJSONArray("choices")
          .getJSONObject(0)
          .getJSONObject("message")
          .getString("content")
          .trim()
    }
  }
}
