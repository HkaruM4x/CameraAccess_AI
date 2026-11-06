/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.aistream

import android.app.Application
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.FaceBlurProcessor
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.YuvToBitmapConverter
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

class AIStreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
    // The optional session context typed by the user before starting AI mode.
    // Empty string means no extra context — the default system prompt is used as-is.
    private val sessionContext: String,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "AIStreamViewModel"
    private val INITIAL_STATE = AIStreamUiState()
  }

  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<AIStreamUiState> = _uiState.asStateFlow()

  private var streamSession: StreamSession? = null
  private var videoJob: Job? = null
  private var stateJob: Job? = null

  private var aiProcessor: AIAnalystProcessor? = null
  private var tts: TextToSpeech? = null

  // Blurs faces in every frame before it is uploaded. Always on in AI mode — no toggle,
  // because these frames leave the device.
  private var uploadBlur: FaceBlurProcessor? = null

  fun startStream() {
    val blur = FaceBlurProcessor().also { uploadBlur = it }

    // Speak a one-time intro so the user knows AI mode is live.
    tts = TextToSpeech(getApplication()) { status ->
      if (status == TextToSpeech.SUCCESS) {
        tts?.language = Locale.US
        tts?.speak(
            "AI Analyst is now active. I'll analyze what you see every few seconds.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            "intro",
        )
      }
    }

    // Set up the AI processor. It will receive every frame and decide internally
    // whether to fire an API call based on the 3-second throttle.
    aiProcessor =
        AIAnalystProcessor(
            apiKey = BuildConfig.OPENAI_API_KEY,
            scope = viewModelScope,
            sessionContext = sessionContext,
            sanitize = { bitmap -> blur.blurForUpload(bitmap) },
            onAnalyzing = {
              // Called the moment a frame is chosen for analysis — before the response arrives.
              // We flip isAnalyzing = true so the UI can show a "thinking" indicator immediately.
              _uiState.update { it.copy(isAnalyzing = true) }
            },
            onResult = { text ->
              // Called when GPT-4o responds. Replace the old analysis text and hide the indicator.
              _uiState.update { it.copy(aiAnalysisText = text, isAnalyzing = false) }
            },
        )

    val streamSession =
        Wearables.startStreamSession(
                getApplication(),
                wearablesViewModel.deviceSelector,
                StreamConfiguration(videoQuality = VideoQuality.MEDIUM, 24),
            )
            .also { streamSession = it }

    // Collect video frames on a background thread — frame processing is CPU-heavy.
    videoJob =
        viewModelScope.launch(Dispatchers.Default) {
          streamSession.videoStream.collect { handleVideoFrame(it) }
        }

    // Collect stream state changes on the main thread.
    stateJob =
        viewModelScope.launch {
          streamSession.state.collect { currentState ->
            val prevState = _uiState.value.streamSessionState
            _uiState.update { it.copy(streamSessionState = currentState) }

            // If the glasses disconnect or the session ends, navigate back automatically.
            if (currentState != prevState && currentState == StreamSessionState.STOPPED) {
              stopStream()
              wearablesViewModel.navigateToDeviceSelection()
            }
          }
        }
  }

  fun stopStream() {
    tts?.stop()
    tts?.shutdown()
    tts = null
    videoJob?.cancel()
    videoJob = null
    stateJob?.cancel()
    stateJob = null
    streamSession?.close()
    streamSession = null
    aiProcessor?.close()
    aiProcessor = null
    uploadBlur?.close()
    uploadBlur = null
    _uiState.update { INITIAL_STATE }
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    val bitmap =
        YuvToBitmapConverter.convert(
            videoFrame.buffer,
            videoFrame.width,
            videoFrame.height,
        )

    if (bitmap == null) {
      Log.e(TAG, "Failed to convert YUV to bitmap")
      return
    }

    // Update the displayed frame every time — the UI always shows the latest frame.
    _uiState.update { it.copy(videoFrame = bitmap) }

    // Pass to the processor. It silently drops the frame if the interval hasn't
    // elapsed or a call is already in-flight — no extra logic needed here.
    aiProcessor?.process(bitmap)
  }

  override fun onCleared() {
    super.onCleared()
    stopStream()
  }

  class Factory(
      private val application: Application,
      private val wearablesViewModel: WearablesViewModel,
      private val sessionContext: String,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(AIStreamViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST", "KotlinGenericsCast")
        return AIStreamViewModel(application, wearablesViewModel, sessionContext) as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}
