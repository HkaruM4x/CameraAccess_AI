/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ImportMediaScreen"

enum class MediaFilter { ALL, VIDEOS, PHOTOS }

data class MediaItem(
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
    val durationMs: Long = 0,
    val dateAdded: Long = 0,
)

@Composable
fun ImportMediaScreen(
    viewModel: WearablesViewModel,
    modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var mediaItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
  var filter by remember { mutableStateOf(MediaFilter.ALL) }

  LaunchedEffect(Unit) {
    mediaItems = loadMediaFromGlasses(context)
  }

  val filteredItems = remember(mediaItems, filter) {
    when (filter) {
      MediaFilter.ALL -> mediaItems
      MediaFilter.VIDEOS -> mediaItems.filter { it.isVideo }
      MediaFilter.PHOTOS -> mediaItems.filter { !it.isVideo }
    }
  }

  MaterialTheme(colorScheme = darkColorScheme()) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding(),
    ) {
      // Top bar
      Row(
          modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 8.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = { viewModel.navigateToDeviceSelection() }) {
          Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.import_media_back),
              tint = Color.White,
          )
        }
        Text(
            text = stringResource(R.string.import_media_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
      }

      // Filter chips
      Row(
          modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FilterChip(
            selected = filter == MediaFilter.ALL,
            onClick = { filter = MediaFilter.ALL },
            label = { Text(stringResource(R.string.import_filter_all)) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = AppColor.DeepBlue,
                selectedLabelColor = Color.White,
            ),
        )
        FilterChip(
            selected = filter == MediaFilter.VIDEOS,
            onClick = { filter = MediaFilter.VIDEOS },
            label = { Text(stringResource(R.string.import_filter_videos)) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = AppColor.DeepBlue,
                selectedLabelColor = Color.White,
            ),
        )
        FilterChip(
            selected = filter == MediaFilter.PHOTOS,
            onClick = { filter = MediaFilter.PHOTOS },
            label = { Text(stringResource(R.string.import_filter_photos)) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = AppColor.DeepBlue,
                selectedLabelColor = Color.White,
            ),
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      if (filteredItems.isEmpty()) {
        // Empty state
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.VideoLibrary,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(64.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.import_media_empty),
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 48.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.import_media_hint),
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 48.dp),
            )
          }
        }
      } else {
        // Media grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
          items(filteredItems, key = { it.uri.toString() }) { item ->
            MediaThumbnail(item = item)
          }
        }
      }
    }
  }
}

@Composable
private fun MediaThumbnail(item: MediaItem, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  Box(
      modifier = modifier
          .aspectRatio(1f)
          .clip(RoundedCornerShape(4.dp))
          .background(Color.DarkGray)
          .clickable {
            // Open with system viewer
            try {
              val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(
                    item.uri,
                    if (item.isVideo) "video/*" else "image/*",
                )
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
              }
              context.startActivity(intent)
            } catch (e: Exception) {
              Log.w(TAG, "Cannot open media", e)
            }
          },
  ) {
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(item.uri)
            .crossfade(true)
            .build(),
        contentDescription = item.name,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )

    // Video overlay icon
    if (item.isVideo) {
      Icon(
          imageVector = Icons.Default.PlayCircle,
          contentDescription = null,
          tint = Color.White.copy(alpha = 0.8f),
          modifier = Modifier
              .align(Alignment.Center)
              .size(32.dp),
      )

      // Duration label
      if (item.durationMs > 0) {
        Text(
            text = formatDuration(item.durationMs),
            color = Color.White,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
      }
    } else {
      // Photo icon
      Icon(
          imageVector = Icons.Default.Image,
          contentDescription = null,
          tint = Color.White.copy(alpha = 0.6f),
          modifier = Modifier
              .align(Alignment.BottomStart)
              .padding(4.dp)
              .size(16.dp),
      )
    }
  }
}

private fun formatDuration(ms: Long): String {
  val totalSec = ms / 1000
  val min = totalSec / 60
  val sec = totalSec % 60
  return "%d:%02d".format(min, sec)
}

/**
 * Load videos and photos from the CameraAccess folder and DCIM/Camera (where glasses media
 * gets synced via Meta View app).
 */
private suspend fun loadMediaFromGlasses(context: Context): List<MediaItem> =
    withContext(Dispatchers.IO) {
      val items = mutableListOf<MediaItem>()

      // Load videos
      try {
        val videoProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.RELATIVE_PATH,
        )
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            videoProjection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
          val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
          val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
          val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
          val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

          while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            items.add(
                MediaItem(
                    uri = uri,
                    name = cursor.getString(nameCol) ?: "video",
                    isVideo = true,
                    durationMs = cursor.getLong(durationCol),
                    dateAdded = cursor.getLong(dateCol),
                )
            )
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error loading videos", e)
      }

      // Load photos
      try {
        val photoProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            photoProjection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
          val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
          val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
          val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

          while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            items.add(
                MediaItem(
                    uri = uri,
                    name = cursor.getString(nameCol) ?: "photo",
                    isVideo = false,
                    dateAdded = cursor.getLong(dateCol),
                )
            )
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error loading photos", e)
      }

      // Sort all by date descending
      items.sortByDescending { it.dateAdded }
      items
    }
