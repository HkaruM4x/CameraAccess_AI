/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * Detects faces in video frames and applies a pixelation blur to the detected regions.
 *
 * Detection is throttled to every [DETECTION_INTERVAL_FRAMES] frames to reduce CPU load (~8fps
 * detection at a 24fps stream). Between detections the last known face bounding boxes are reused,
 * so the blur is applied every frame with minimal latency.
 *
 * A copy of the source bitmap is made before passing to ML Kit so that YuvToBitmapConverter's
 * cached bitmap can safely be overwritten by the next frame while detection is in flight.
 *
 * Usage:
 * ```
 * val processor = FaceBlurProcessor()
 *
 * // Call once per frame (on a background thread):
 * val blurred: Bitmap? = processor.process(bitmap)
 * // blurred is a new Bitmap; recycle it after use.
 * // If null is returned, no faces were found — use the original bitmap as-is.
 *
 * // When the stream stops:
 * processor.close()
 * ```
 */
internal class FaceBlurProcessor {

  companion object {
    private const val TAG = "FaceBlurProcessor"

    // Run ML Kit detection every 3 frames (~8 fps at a 24 fps stream).
    private const val DETECTION_INTERVAL_FRAMES = 3

    // Pixelation: face region is scaled down by this factor then back up (no bilinear filter).
    // Higher = more heavily pixelated / more privacy.
    private const val PIXELATION_FACTOR = 12
  }

  private val detector =
      FaceDetection.getClient(
          FaceDetectorOptions.Builder()
              .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
              .setMinFaceSize(0.1f)
              .build(),
      )

  // Last bounding boxes returned by ML Kit, applied to every frame.
  @Volatile private var lastFaceRects: List<Rect> = emptyList()

  private var frameCount = 0

  // Prevents queuing multiple detections simultaneously.
  @Volatile private var detectionInFlight = false

  /**
   * Process one video frame.
   *
   * - If face blur rects are available, returns a new mutable [Bitmap] with those regions
   *   pixelated. The caller is responsible for recycling this bitmap after use.
   * - If no rects are available yet (first frames before detection runs), returns null — the
   *   caller should fall back to the original bitmap.
   *
   * This method is safe to call from any background thread. [source] must not be recycled while
   * this call is executing.
   */
  fun process(source: Bitmap): Bitmap? {
    frameCount++

    // Kick off async detection every N frames (only one in-flight at a time).
    if (frameCount % DETECTION_INTERVAL_FRAMES == 0 && !detectionInFlight) {
      runDetection(source)
    }

    val rects = lastFaceRects
    if (rects.isEmpty()) return null

    return pixelate(source, rects)
  }

  /** Release the underlying ML Kit detector. Call when streaming stops. */
  fun close() {
    detector.close()
    lastFaceRects = emptyList()
    frameCount = 0
    detectionInFlight = false
  }

  // ── Private helpers ──────────────────────────────────────────────────────────

  /**
   * Copies [source] and submits it to ML Kit asynchronously. Using a copy prevents a data race
   * with YuvToBitmapConverter which may overwrite [source] before the Task completes.
   */
  private fun runDetection(source: Bitmap) {
    detectionInFlight = true
    val snapshot = source.copy(Bitmap.Config.ARGB_8888, false)
    val image = InputImage.fromBitmap(snapshot, 0)

    detector
        .process(image)
        .addOnSuccessListener { faces: List<Face> ->
          lastFaceRects = faces.map { it.boundingBox }
          snapshot.recycle()
          detectionInFlight = false
          Log.d(TAG, "Detected ${faces.size} face(s)")
        }
        .addOnFailureListener { e ->
          Log.w(TAG, "Face detection failed", e)
          snapshot.recycle()
          detectionInFlight = false
        }
  }

  /**
   * Returns a mutable copy of [source] with each rect in [rects] replaced by a pixelated version
   * of that region.
   *
   * Pixelation is achieved by scaling the cropped region down to 1/[PIXELATION_FACTOR] of its
   * original size (without bilinear filtering) and then scaling it back up. This is fast, requires
   * no extra libraries, and is visually unambiguous as a privacy indicator.
   */
  private fun pixelate(source: Bitmap, rects: List<Rect>): Bitmap {
    val output = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(output)
    val paint = Paint().apply { isFilterBitmap = false }

    for (rect in rects) {
      // Clamp to bitmap bounds to guard against out-of-range detections.
      val left = rect.left.coerceIn(0, source.width)
      val top = rect.top.coerceIn(0, source.height)
      val right = rect.right.coerceIn(0, source.width)
      val bottom = rect.bottom.coerceIn(0, source.height)
      val w = right - left
      val h = bottom - top
      if (w <= 0 || h <= 0) continue

      val face = Bitmap.createBitmap(source, left, top, w, h)
      val smallW = maxOf(1, w / PIXELATION_FACTOR)
      val smallH = maxOf(1, h / PIXELATION_FACTOR)
      val small = Bitmap.createScaledBitmap(face, smallW, smallH, false)
      val pixelated = Bitmap.createScaledBitmap(small, w, h, false)

      canvas.drawBitmap(pixelated, left.toFloat(), top.toFloat(), paint)

      face.recycle()
      small.recycle()
      pixelated.recycle()
    }

    return output
  }
}
