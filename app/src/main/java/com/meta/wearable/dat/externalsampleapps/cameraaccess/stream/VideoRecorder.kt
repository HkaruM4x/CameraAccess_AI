/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder.AudioSource
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records video frames (I420 YUV) + microphone audio to an MP4 file in Movies/CameraAccess
 * using MediaCodec (H.264 video, AAC audio) + MediaMuxer.
 *
 * Usage:
 *  1. Call [start] on the first frame to initialize with correct dimensions.
 *  2. Call [encodeFrame] for each subsequent frame.
 *  3. Call [stop] to flush, finalize, and publish to MediaStore.
 */
internal class VideoRecorder(private val context: Context) {

  companion object {
    private const val TAG = "VideoRecorder"

    // Video
    private const val VIDEO_MIME_TYPE = "video/avc"
    private const val BIT_RATE = 2_000_000
    private const val FRAME_RATE = 24
    private const val I_FRAME_INTERVAL = 1

    // Audio
    private const val AUDIO_MIME_TYPE = "audio/mp4a-latm"
    private const val AUDIO_SAMPLE_RATE = 44100
    private const val AUDIO_CHANNEL_COUNT = 1
    private const val AUDIO_BIT_RATE = 128_000
    private const val AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    // Muxer track sentinel
    private const val NO_TRACK = -1
  }

  // ── Video encoder ──────────────────────────────────────────────────────────
  private var videoEncoder: MediaCodec? = null
  private var videoTrackIndex = NO_TRACK

  // ── Audio encoder + recorder ───────────────────────────────────────────────
  private var audioEncoder: MediaCodec? = null
  private var audioRecord: AudioRecord? = null
  private var audioThread: Thread? = null
  @Volatile private var audioRunning = false
  private var audioTrackIndex = NO_TRACK

  // ── Muxer ──────────────────────────────────────────────────────────────────
  private var muxer: MediaMuxer? = null
  private var muxerStarted = false
  // Both tracks must be registered before muxer.start()
  private var videoFormatReady = false
  private var audioFormatReady = false
  private val muxerLock = Any()   // guards muxer writes and track registration

  // ── File handles ───────────────────────────────────────────────────────────
  private var pfd: ParcelFileDescriptor? = null
  private var pendingUri: Uri? = null

  // ── Frame geometry ─────────────────────────────────────────────────────────
  private var frameWidth = 0
  private var frameHeight = 0
  private var i420Buffer = ByteArray(0)
  private var nv12Buffer = ByteArray(0)

  // ── Video timestamp tracking ───────────────────────────────────────────────
  private var firstPtsUs = -1L
  private var lastPtsUs = -1L
  private var syntheticPtsUs = 0L

  // ── Audio timestamp tracking ───────────────────────────────────────────────
  // We sync audio timestamps to wall-clock so they align with video (which also
  // normalises to zero from the recording start).
  private var audioStartNs = -1L    // System.nanoTime() at first audio sample

  val isRecording: Boolean
    get() = videoEncoder != null

  // ══════════════════════════════════════════════════════════════════════════
  // Public API
  // ══════════════════════════════════════════════════════════════════════════

  fun start(width: Int, height: Int) {
    if (isRecording) return

    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val values = ContentValues().apply {
      put(MediaStore.Video.Media.DISPLAY_NAME, "VID_$timestamp.mp4")
      put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
      put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CameraAccess")
      put(MediaStore.Video.Media.IS_PENDING, 1)
    }

    val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ?: run { Log.e(TAG, "Failed to create MediaStore video entry"); return }
    pendingUri = uri

    val filePfd = context.contentResolver.openFileDescriptor(uri, "w")
        ?: run {
          Log.e(TAG, "Failed to open file descriptor for recording")
          context.contentResolver.delete(uri, null, null)
          pendingUri = null
          return
        }
    pfd = filePfd

    try {
      muxer = MediaMuxer(filePfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

      // ── Video encoder ──────────────────────────────────────────────────────
      val videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FormatYUV420Flexible)
        setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
      }
      videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE).also { codec ->
        codec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
      }

      frameWidth = width
      frameHeight = height
      i420Buffer = ByteArray(width * height * 3 / 2)
      nv12Buffer = ByteArray(width * height * 3 / 2)
      framesEncoded = 0
      firstPtsUs = -1L
      lastPtsUs = -1L
      syntheticPtsUs = 0L

      // ── Audio encoder + AudioRecord ────────────────────────────────────────
      startAudioCapture()

      Log.d(TAG, "Recording started: ${width}x${height}")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start recording", e)
      releaseOnFailure()
    }
  }

  /** Flushes remaining frames, stops the encoder, and publishes the file to the gallery. */
  fun stop() {
    val codec = videoEncoder ?: return
    try {
      val inputIndex = codec.dequeueInputBuffer(10_000)
      if (inputIndex >= 0) {
        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
      }
      drainVideoEncoder(endOfStream = true)
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping video encoder", e)
    }

    stopAudioCapture()
    releaseResources()
    finalizeRecording()
  }

  // ══════════════════════════════════════════════════════════════════════════
  // Video encoding
  // ══════════════════════════════════════════════════════════════════════════

  private var framesEncoded = 0

  /**
   * Encodes one I420 frame. Safe to call every frame — does nothing if not recording or
   * dimensions changed.
   */
  fun encodeFrame(yuvData: ByteBuffer, width: Int, height: Int, presentationTimeUs: Long) {
    val codec = videoEncoder ?: run { Log.w(TAG, "encodeFrame: encoder is null"); return }
    if (width != frameWidth || height != frameHeight) {
      Log.w(TAG, "encodeFrame: dimension mismatch got ${width}x${height}, expected ${frameWidth}x${frameHeight}")
      return
    }

    val frameSize = width * height
    val uvSize = frameSize / 4
    val expectedSize = frameSize + uvSize * 2
    if (yuvData.remaining() < expectedSize) {
      Log.w(TAG, "encodeFrame: buffer too small remaining=${yuvData.remaining()} expected=$expectedSize")
      return
    }

    val savedPos = yuvData.position()
    yuvData.get(i420Buffer, 0, expectedSize)
    yuvData.position(savedPos)

    // Normalise timestamps
    val effectivePtsUs: Long
    if (firstPtsUs < 0) {
      firstPtsUs = presentationTimeUs
      lastPtsUs = presentationTimeUs
      syntheticPtsUs = 0L
      effectivePtsUs = 0L
    } else {
      val delta = presentationTimeUs - lastPtsUs
      if (delta > 0) {
        syntheticPtsUs += delta
        lastPtsUs = presentationTimeUs
      } else {
        syntheticPtsUs += 1_000_000L / FRAME_RATE
      }
      effectivePtsUs = syntheticPtsUs
    }

    // I420 → NV12
    System.arraycopy(i420Buffer, 0, nv12Buffer, 0, frameSize)
    for (i in 0 until uvSize) {
      nv12Buffer[frameSize + i * 2]     = i420Buffer[frameSize + i]
      nv12Buffer[frameSize + i * 2 + 1] = i420Buffer[frameSize + uvSize + i]
    }

    val inputIndex = codec.dequeueInputBuffer(10_000)
    if (inputIndex < 0) {
      Log.w(TAG, "encodeFrame: no input buffer available after 10ms timeout")
      drainVideoEncoder(endOfStream = false)
      return
    }

    val inputImage = codec.getInputImage(inputIndex)
    if (inputImage != null) {
      val yPlane = inputImage.planes[0]
      val uPlane = inputImage.planes[1]
      val yRowStride = yPlane.rowStride
      val uvRowStride = uPlane.rowStride

      if (framesEncoded == 0) {
        Log.d(TAG, "Image API: yRowStride=$yRowStride uvRowStride=$uvRowStride uvPixelStride=${uPlane.pixelStride}")
      }

      val yBuffer = yPlane.buffer
      for (row in 0 until frameHeight) {
        yBuffer.position(row * yRowStride)
        yBuffer.put(nv12Buffer, row * frameWidth, frameWidth)
      }

      val uvBuffer = uPlane.buffer
      val uvBufLimit = uvBuffer.limit()
      for (row in 0 until frameHeight / 2) {
        val pos = row * uvRowStride
        if (pos >= uvBufLimit) break
        val bytesToWrite = minOf(frameWidth, uvBufLimit - pos)
        uvBuffer.position(pos)
        uvBuffer.put(nv12Buffer, frameSize + row * frameWidth, bytesToWrite)
      }

      val actualCap = codec.getInputBuffer(inputIndex)?.capacity()
          ?: (yRowStride * frameHeight * 3 / 2)
      codec.queueInputBuffer(inputIndex, 0, actualCap, effectivePtsUs, 0)
    } else {
      Log.w(TAG, "getInputImage returned null — falling back to ByteBuffer")
      val inputBuffer = codec.getInputBuffer(inputIndex)!!
      val cap = inputBuffer.capacity()
      val packedSize = frameWidth * frameHeight * 3 / 2
      val (stride, sliceH) = if (cap > packedSize) {
        val s = cap * 2 / (3 * frameHeight)
        s to (cap * 2 / (3 * s))
      } else {
        frameWidth to frameHeight
      }
      if (framesEncoded == 0) {
        Log.d(TAG, "Buffer fallback: stride=$stride sliceH=$sliceH cap=$cap")
      }
      inputBuffer.clear()
      if (stride == frameWidth) {
        inputBuffer.put(nv12Buffer)
      } else {
        for (row in 0 until frameHeight) {
          inputBuffer.position(row * stride)
          inputBuffer.put(nv12Buffer, row * frameWidth, frameWidth)
        }
        val uvOffset = stride * sliceH
        for (row in 0 until frameHeight / 2) {
          inputBuffer.position(uvOffset + row * stride)
          inputBuffer.put(nv12Buffer, frameSize + row * frameWidth, frameWidth)
        }
      }
      codec.queueInputBuffer(inputIndex, 0, stride * sliceH * 3 / 2, effectivePtsUs, 0)
    }

    framesEncoded++
    if (framesEncoded == 1 || framesEncoded % 30 == 0) {
      Log.d(TAG, "Queued frame #$framesEncoded sdkPts=$presentationTimeUs effectivePts=$effectivePtsUs")
    }

    drainVideoEncoder(endOfStream = false)
  }

  private fun drainVideoEncoder(endOfStream: Boolean) {
    val codec = videoEncoder ?: return
    val info = MediaCodec.BufferInfo()

    while (true) {
      val outputIndex = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
      when {
        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          synchronized(muxerLock) {
            if (videoTrackIndex == NO_TRACK) {
              videoTrackIndex = muxer!!.addTrack(codec.outputFormat)
              videoFormatReady = true
              maybeStartMuxer()
            }
          }
        }
        outputIndex >= 0 -> {
          val outputBuffer = codec.getOutputBuffer(outputIndex)!!
          if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
          if (info.size > 0) {
            synchronized(muxerLock) {
              if (muxerStarted) {
                outputBuffer.position(info.offset)
                outputBuffer.limit(info.offset + info.size)
                muxer!!.writeSampleData(videoTrackIndex, outputBuffer, info)
              }
            }
          }
          codec.releaseOutputBuffer(outputIndex, false)
          if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
      }
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  // Audio capture
  // ══════════════════════════════════════════════════════════════════════════

  private fun startAudioCapture() {
    val minBufSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_CONFIG, AUDIO_FORMAT)
    if (minBufSize == AudioRecord.ERROR_BAD_VALUE || minBufSize == AudioRecord.ERROR) {
      Log.e(TAG, "AudioRecord: bad buffer size — audio will be skipped")
      // Still allow video-only recording
      synchronized(muxerLock) {
        audioFormatReady = true
        maybeStartMuxer()
      }
      return
    }

    // Configure AAC audio encoder
    val audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME_TYPE, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT).apply {
      setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
      setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
      setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBufSize * 2)
    }

    val aEncoder = try {
      MediaCodec.createEncoderByType(AUDIO_MIME_TYPE).also { codec ->
        codec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create audio encoder — recording video only", e)
      synchronized(muxerLock) {
        audioFormatReady = true
        maybeStartMuxer()
      }
      return
    }
    audioEncoder = aEncoder

    val recorder = try {
      AudioRecord(AudioSource.MIC, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_CONFIG, AUDIO_FORMAT, minBufSize * 4).also {
        if (it.state != AudioRecord.STATE_INITIALIZED) {
          throw IllegalStateException("AudioRecord not initialized — RECORD_AUDIO permission granted?")
        }
        it.startRecording()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start AudioRecord — recording video only", e)
      aEncoder.stop(); aEncoder.release(); audioEncoder = null
      synchronized(muxerLock) {
        audioFormatReady = true
        maybeStartMuxer()
      }
      return
    }
    audioRecord = recorder

    audioRunning = true
    audioStartNs = -1L
    audioThread = Thread({
      val pcmBuf = ByteArray(minBufSize)
      while (audioRunning) {
        val read = recorder.read(pcmBuf, 0, pcmBuf.size)
        if (read <= 0) continue

        val nowNs = System.nanoTime()
        if (audioStartNs < 0) audioStartNs = nowNs
        val ptsUs = (nowNs - audioStartNs) / 1000L

        feedAudioToEncoder(aEncoder, pcmBuf, read, ptsUs)
        drainAudioEncoder(aEncoder, endOfStream = false)
      }
      // Flush remaining audio
      signalAudioEndOfStream(aEncoder)
      drainAudioEncoder(aEncoder, endOfStream = true)
    }, "AudioRecordThread").also { it.start() }
  }

  private fun feedAudioToEncoder(codec: MediaCodec, pcm: ByteArray, length: Int, ptsUs: Long) {
    val inputIndex = codec.dequeueInputBuffer(10_000)
    if (inputIndex < 0) return
    val buf = codec.getInputBuffer(inputIndex) ?: return
    buf.clear()
    buf.put(pcm, 0, length)
    codec.queueInputBuffer(inputIndex, 0, length, ptsUs, 0)
  }

  private fun signalAudioEndOfStream(codec: MediaCodec) {
    val inputIndex = codec.dequeueInputBuffer(10_000)
    if (inputIndex >= 0) {
      codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }
  }

  private fun drainAudioEncoder(codec: MediaCodec, endOfStream: Boolean) {
    val info = MediaCodec.BufferInfo()
    while (true) {
      val outputIndex = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
      when {
        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          synchronized(muxerLock) {
            if (audioTrackIndex == NO_TRACK) {
              audioTrackIndex = muxer!!.addTrack(codec.outputFormat)
              audioFormatReady = true
              maybeStartMuxer()
            }
          }
        }
        outputIndex >= 0 -> {
          val outputBuffer = codec.getOutputBuffer(outputIndex)!!
          if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
          if (info.size > 0) {
            synchronized(muxerLock) {
              if (muxerStarted) {
                outputBuffer.position(info.offset)
                outputBuffer.limit(info.offset + info.size)
                muxer!!.writeSampleData(audioTrackIndex, outputBuffer, info)
              }
            }
          }
          codec.releaseOutputBuffer(outputIndex, false)
          if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
      }
    }
  }

  /** Must be called with [muxerLock] held. */
  private fun maybeStartMuxer() {
    if (!muxerStarted && videoFormatReady && audioFormatReady) {
      muxer!!.start()
      muxerStarted = true
      Log.d(TAG, "Muxer started (video+audio tracks registered)")
    }
  }

  private fun stopAudioCapture() {
    audioRunning = false
    audioThread?.join(3_000)
    audioThread = null

    try { audioRecord?.stop() } catch (_: Exception) {}
    try { audioRecord?.release() } catch (_: Exception) {}
    audioRecord = null

    try { audioEncoder?.stop() } catch (_: Exception) {}
    try { audioEncoder?.release() } catch (_: Exception) {}
    audioEncoder = null
  }

  // ══════════════════════════════════════════════════════════════════════════
  // Teardown helpers
  // ══════════════════════════════════════════════════════════════════════════

  private fun releaseResources() {
    try { videoEncoder?.stop() } catch (_: Exception) {}
    try { videoEncoder?.release() } catch (_: Exception) {}
    videoEncoder = null

    synchronized(muxerLock) {
      try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
      try { muxer?.release() } catch (_: Exception) {}
      muxer = null
      muxerStarted = false
      videoTrackIndex = NO_TRACK
      audioTrackIndex = NO_TRACK
      videoFormatReady = false
      audioFormatReady = false
    }

    try { pfd?.close() } catch (_: Exception) {}
    pfd = null
  }

  private fun releaseOnFailure() {
    stopAudioCapture()
    releaseResources()
    pendingUri?.let { context.contentResolver.delete(it, null, null) }
    pendingUri = null
  }

  private fun finalizeRecording() {
    val uri = pendingUri ?: return
    pendingUri = null
    try {
      val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
      context.contentResolver.update(uri, values, null, null)
      Handler(Looper.getMainLooper()).post {
        Toast.makeText(context, "Video saved to Movies/CameraAccess", Toast.LENGTH_SHORT).show()
      }
      Log.d(TAG, "Recording saved: $uri")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to finalize recording", e)
      context.contentResolver.delete(uri, null, null)
    }
  }
}
