package com.example.smartclipper

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.sqrt

data class VideoScene(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val label: String
)

class SmartSceneAnalyzer(private val context: Context) {

    fun getVideoDurationMs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

    suspend fun detectScenes(
        uri: Uri,
        clipDurationSec: Int,
        smartHighlight: Boolean
    ): List<VideoScene> = withContext(Dispatchers.IO) {
        val totalMs = getVideoDurationMs(uri)
        if (totalMs <= 0L) return@withContext emptyList()

        val clipMs = clipDurationSec * 1000L
        if (totalMs <= clipMs) {
            return@withContext listOf(
                VideoScene(1, 0L, totalMs, "Full Clip")
            )
        }

        if (!smartHighlight) {
            return@withContext buildSequentialScenes(totalMs, clipMs)
        }

        val audioScores = scanAudioEnergyPerSecond(uri, totalMs)
        if (audioScores.isEmpty()) {
            return@withContext buildSequentialScenes(totalMs, clipMs)
        }

        val totalSeconds = audioScores.size
        val windowSec = clipDurationSec
        val candidateWindows = mutableListOf<Pair<Int, Double>>()

        for (startSec in 0..(totalSeconds - windowSec)) {
            var sum = 0.0
            for (offset in 0 until windowSec) {
                sum += audioScores[startSec + offset]
            }
            candidateWindows.add(Pair(startSec, sum / windowSec))
        }

        if (candidateWindows.isEmpty()) {
            return@withContext buildSequentialScenes(totalMs, clipMs)
        }

        val maxScore = candidateWindows.maxOf { it.second }.coerceAtLeast(1.0)
        val sortedCandidates = candidateWindows.sortedByDescending { it.second }
        val selectedStarts = mutableListOf<Pair<Int, Int>>()
        val maxClips = min(10, (totalSeconds / windowSec).coerceAtLeast(1))

        for ((startSec, score) in sortedCandidates) {
            if (selectedStarts.size >= maxClips) break
            val overlaps = selectedStarts.any { (existingStart, _) ->
                kotlin.math.abs(existingStart - startSec) < windowSec
            }
            if (!overlaps) {
                val percent = ((score / maxScore) * 100).toInt().coerceIn(72, 99)
                selectedStarts.add(Pair(startSec, percent))
            }
        }

        selectedStarts.sortBy { it.first }
        selectedStarts.mapIndexed { idx, (startSec, percent) ->
            val startMs = startSec * 1000L
            val endMs = min(totalMs, startMs + clipMs)
            VideoScene(
                index = idx + 1,
                startMs = startMs,
                endMs = endMs,
                label = "Highlight $percent%"
            )
        }
    }

    private fun buildSequentialScenes(totalMs: Long, clipMs: Long): List<VideoScene> {
        val scenes = mutableListOf<VideoScene>()
        var currentStart = 0L
        var part = 1
        while (currentStart < totalMs && part <= 20) {
            val end = min(totalMs, currentStart + clipMs)
            if (end - currentStart >= 3000L) {
                scenes.add(VideoScene(part, currentStart, end, "Part $part"))
            }
            currentStart += clipMs
            part++
        }
        return scenes
    }

    private fun scanAudioEnergyPerSecond(uri: Uri, totalMs: Long): List<Double> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }
            if (audioTrackIndex == -1 || audioFormat == null) return emptyList()

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val totalSeconds = (totalMs / 1000L).toInt().coerceAtLeast(1)
            val energies = MutableList(totalSeconds) { 0.0 }
            val bufferInfo = MediaCodec.BufferInfo()

            for (sec in 0 until totalSeconds) {
                val targetUs = sec * 1_000_000L
                extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                codec.flush()

                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inputBuf = codec.getInputBuffer(inIdx)
                    if (inputBuf != null) {
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize > 0) {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 15_000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val shortBuf = outBuf.asShortBuffer()
                        var sumSquares = 0.0
                        val count = shortBuf.remaining()
                        while (shortBuf.hasRemaining()) {
                            val s = shortBuf.get().toDouble()
                            sumSquares += s * s
                        }
                        if (count > 0) {
                            energies[sec] = sqrt(sumSquares / count)
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
            energies
        } catch (e: Exception) {
            emptyList()
        } finally {
            try {
                codec?.stop()
                codec?.release()
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    @OptIn(UnstableApi::class)
    suspend fun exportSceneToGallery(
        sourceUri: Uri,
        scene: VideoScene,
        fullCropVertical: Boolean,
        subtitles: List<SubtitleSegment> = emptyList()
    ): String {
        val tempFile = File(context.cacheDir, "clip_${System.currentTimeMillis()}.mp4")
        if (tempFile.exists()) tempFile.delete()

        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val clippingConfig = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(scene.startMs)
                    .setEndPositionMs(scene.endMs)
                    .build()

                val mediaItem = MediaItem.Builder()
                    .setUri(sourceUri)
                    .setClippingConfiguration(clippingConfig)
                    .build()

                val layoutMode = if (fullCropVertical) {
                    Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                } else {
                    Presentation.LAYOUT_SCALE_TO_FIT
                }

                // Kunci resolusi Full HD 1080x1920 agar tetap tajam
                val presentation = Presentation.createForWidthAndHeight(1080, 1920, layoutMode)
                val videoEffects = mutableListOf<Effect>(presentation)

                if (subtitles.isNotEmpty()) {
                    val textOverlay = object : TextOverlay() {
                        override fun getText(presentationTimeUs: Long): SpannableString {
                            val activeText = subtitles.firstOrNull {
                                presentationTimeUs in it.startUs..it.endUs
                            }?.text ?: ""

                            if (activeText.isEmpty()) return SpannableString("")

                            val spannable = SpannableString(" $activeText ")
                            spannable.setSpan(
                                ForegroundColorSpan(Color.WHITE),
                                0,
                                spannable.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            spannable.setSpan(
                                BackgroundColorSpan(Color.argb(200, 12, 16, 24)),
                                0,
                                spannable.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            spannable.setSpan(
                                StyleSpan(Typeface.BOLD),
                                0,
                                spannable.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            spannable.setSpan(
                                RelativeSizeSpan(1.45f),
                                0,
                                spannable.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            return spannable
                        }

                        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
                            return StaticOverlaySettings.Builder()
                                .setBackgroundFrameAnchor(0f, -0.60f)
                                .setOverlayFrameAnchor(0f, -0.60f)
                                .build()
                        }
                    }
                    videoEffects.add(OverlayEffect(ImmutableList.of(textOverlay)))
                }

                val effects = Effects(emptyList(), videoEffects)
                val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                    .setEffects(effects)
                    .build()

                // Bitrate tinggi 10 Mbps mencegah gambar pecah/buram
                val encoderSettings = VideoEncoderSettings.Builder()
                    .setBitrate(10_000_000)
                    .build()
                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(encoderSettings)
                    .build()

                val transformer = Transformer.Builder(context)
                    .setEncoderFactory(encoderFactory)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            if (continuation.isActive) continuation.resumeWithException(exportException)
                        }
                    })
                    .build()

                transformer.start(editedMediaItem, tempFile.absolutePath)

                continuation.invokeOnCancellation {
                    transformer.cancel()
                }
            }
        }

        return withContext(Dispatchers.IO) {
            val fileName = "SmartClip_HD_Scene${scene.index}_${System.currentTimeMillis()}.mp4"
            saveFileToGallery(tempFile, fileName)
            tempFile.delete()
            fileName
        }
    }

    private fun saveFileToGallery(sourceFile: File, displayName: String) {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SmartClipper")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val itemUri = resolver.insert(collection, contentValues)
            ?: throw IllegalStateException("Gagal menyimpan ke Galeri")

        resolver.openOutputStream(itemUri)?.use { outputStream ->
            FileInputStream(sourceFile).use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(itemUri, contentValues, null, null)
        }
    }
}
