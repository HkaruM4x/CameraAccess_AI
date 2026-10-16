/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.aistream

import android.graphics.Bitmap
import com.meta.wearable.dat.camera.types.StreamSessionState

data class AIStreamUiState(
    // The latest video frame from the glasses — displayed as the background.
    val videoFrame: Bitmap? = null,

    // Tracks the stream connection state (STARTING, STREAMING, STOPPED).
    // Used to show a loading spinner before the stream is live.
    val streamSessionState: StreamSessionState = StreamSessionState.STOPPED,

    // The most recent analysis text returned by GPT-4o.
    // Null means no analysis has come back yet.
    val aiAnalysisText: String? = null,

    // True while an OpenAI request is in-flight.
    // Used to show a subtle "thinking" indicator on screen.
    val isAnalyzing: Boolean = false,
)
