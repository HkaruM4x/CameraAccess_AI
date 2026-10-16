/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.aistream

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.SwitchButton
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import androidx.compose.foundation.Image

@Composable
fun AIStreamScreen(
    wearablesViewModel: WearablesViewModel,
    sessionContext: String,
    modifier: Modifier = Modifier,
    viewModel: AIStreamViewModel =
        viewModel(
            factory =
                AIStreamViewModel.Factory(
                    application = (LocalActivity.current as ComponentActivity).application,
                    wearablesViewModel = wearablesViewModel,
                    sessionContext = sessionContext,
                ),
        ),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  LaunchedEffect(Unit) { viewModel.startStream() }

  Box(modifier = modifier.fillMaxSize()) {

    // ── Background: live video feed ──────────────────────────────────────────
    uiState.videoFrame?.let { frame ->
      Image(
          bitmap = frame.asImageBitmap(),
          contentDescription = "AI live stream",
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
      )
    }

    // ── Loading spinner while stream is connecting ───────────────────────────
    if (uiState.streamSessionState == StreamSessionState.STARTING) {
      CircularProgressIndicator(
          modifier = Modifier.align(Alignment.Center),
          color = Color.White,
      )
    }

    // ── Top section: AI analysis card + thinking indicator ───────────────────
    Column(
        modifier =
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // "Thinking" indicator — shown while waiting for the OpenAI response.
      // Appears in the top-right corner so it doesn't block the analysis text.
      if (uiState.isAnalyzing) {
        Row(
            modifier = Modifier.align(Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          CircularProgressIndicator(
              modifier = Modifier.size(14.dp),
              color = Color.White,
              strokeWidth = 2.dp,
          )
          Text(
              text = "Analyzing…",
              color = Color.White,
              fontSize = 12.sp,
          )
        }
      }

      // AI analysis card — only shown once the first response arrives.
      uiState.aiAnalysisText?.let { text ->
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
          Text(
              text = text,
              color = Color.White,
              fontSize = 14.sp,
              lineHeight = 20.sp,
          )
        }
      }
    }

    // ── Bottom section: Stop button ──────────────────────────────────────────
    Box(
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
      SwitchButton(
          label = "Stop AI Stream",
          onClick = {
            viewModel.stopStream()
            wearablesViewModel.navigateToDeviceSelection()
          },
          isDestructive = true,
          modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}
