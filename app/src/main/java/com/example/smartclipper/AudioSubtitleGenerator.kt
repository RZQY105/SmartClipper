package com.example.smartclipper

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

data class SubtitleSegment(
    val startUs: Long,
    val endUs: Long,
    val text: String
)

class AudioSubtitleGenerator(private val context: Context) {

    companion object {
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-id-0.22.zip"
        private const val MODEL_FOLDER_NAME = "vosk-model-id"
    }

    private fun getModelDir(): File = File(context.filesDir, MODEL_FOLDER_NAME)

    fun isModelReady(): Boolean {
        val dir = getModelDir()
        return dir.exists() && File(dir, "conf/model.conf").exists()
    }

    suspend fun ensureModelDownloaded(onProgress: (String) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            if (isModelReady()) return@withContext true

            val targetDir = getModelDir()
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()

            val zipFile = File(context.cacheDir, "vosk_id_model.zip")
            if (zipFile.exists()) zipFile.delete()

            onProgress("Mengunduh paket bahasa AI Indonesia (42 MB, 1x saja)...")
            val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            connection.connect()

            val totalBytes = connection.contentLength.coerceAtLeast(1)
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(zipFile).use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        val pct = ((downloaded * 100) / totalBytes).toInt().coerceIn(1, 99)
                        onProgress("Mengunduh paket bahasa AI ($pct%)...")
                    }
                }
            }

            onProgress("Menyiapkan kamus suara AI...")
            ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val cleanName = entry.name.substringAfter("/", "")
                    if (cleanName.isNotEmpty()) {
                        val outFile = File(targetDir, cleanName)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            zipFile.delete()
            isModelReady()
        }

    suspend fun generateSubtitlesForClip(
        sourceUri: Uri,
        startMs: Long,
        endMs: Long,
        onStatus: (String) -> Unit
    ): List<SubtitleSegment> = withContext(Dispatchers.IO) {
        if (!ensureModelDownloaded(onStatus)) return@withContext emptyList()

        onStatus("Mengekstrak suara untuk subtitle...")
        val pcm16kMono = extractPcm16kMono(sourceUri, startMs, endMs)
        if (pcm16kMono.isEmpty()) return@withContext emptyList()

        onStatus("Mengubah suara menjadi teks subtitle...")
        val model = Model(getModelDir().absolutePath)
        val recognizer = Recognizer(model, 16000.0f).apply {
            setWords(true)
        }

        val wordList = mutableListOf<Triple<Long, Long, String>>()
        try {
            var offset = 0
            val chunkSize = 4096
            while (offset < pcm16kMono.size) {
                val len = minOf(chunkSize, pcm16kMono.size - offset)
                val chunk = pcm16kMono.copyOfRange(offset, offset + len)
                if (recognizer.acceptWaveForm(chunk, len)) {
                    parseWordsFromJson(recognizer.result, wordList)
                }
                offset += len
            }
            parseWordsFromJson(recognizer.finalResult, wordList)
        } finally {
            recognizer.close()
            model.close()
        }

        buildCaptionChunks(wordList)
    }

    private fun parseWordsFromJson(
        jsonString: String,
        outWords: MutableList<Triple<Long, Long, String>>
    ) {
        try {
            val root = JSONObject(jsonString)
            val arr = root.optJSONArray("result") ?: return
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val word = obj.optString("word", "").trim()
                val startSec = obj.optDouble("start", -1.0)
                val endSec = obj.optDouble("end", -1.0)
                if (word.isNotEmpty() && startSec >= 0.0 && endSec >= startSec) {
                    outWords.add(
                        Triple(
                            (startSec * 1_000_000.0).toLong(),
                            (endSec * 1_000_000.0).toLong(),
                            word.uppercase()
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun buildCaptionChunks(
        words: List<Triple<Long, Long, String>>
    ): List<SubtitleSegment> {
        if (words.isEmpty()) return emptyList()
        val segments = mutableListOf<SubtitleSegment>()
        var idx = 0
        while (idx < words.size) {
            val endIdx = minOf(idx + 4, words.size)
            val slice = words.subList(idx, endIdx)
            val startUs = slice.first().first
            val endUs = (slice.last().second + 250_000L)
            val phrase = slice.joinToString(" ") { it.third }
            segments.add(SubtitleSegment(startUs, endUs, phrase))
            idx = endIdx
        }
        return segments
    }

    private fun extractPcm16kMono(uri: Uri, startMs: Long, endMs: Long): ByteArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val pcmOutput = ByteArrayOutputStream()

        try {
            extractor.setDataSource(context, uri, null)
            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    format = f
                    break
                }
            }
            if (audioTrack == -1 || format == null) return ByteArray(0)

            extractor.selectTrack(audioTrack)
            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val inputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        val sampleTime = extractor.sampleTime
                        if (sampleSize < 0 || sampleTime > endUs) {
                            codec.queueInputBuffer(
                                inIdx,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0 && bufferInfo.presentationTimeUs >= startUs) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val chunk = ByteArray(bufferInfo.size)
                        outBuf.get(chunk)
                        val resampled = downsampleTo16kMono(chunk, inputSampleRate, channelCount)
                        pcmOutput.write(resampled)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                codec?.stop()
                codec?.release()
                extractor.release()
            } catch (_: Exception) {
            }
        }
        return pcmOutput.toByteArray()
    }

    private fun downsampleTo16kMono(
        pcmBytes: ByteArray,
        sampleRate: Int,
        channels: Int
    ): ByteArray {
        val shortCount = pcmBytes.size / 2
        if (shortCount == 0) return ByteArray(0)

        val shorts = ShortArray(shortCount)
        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

        val monoFrames = shortCount / channels.coerceAtLeast(1)
        val mono = ShortArray(monoFrames)
        for (i in 0 until monoFrames) {
            var sum = 0
            for (ch in 0 until channels) {
                sum += shorts[i * channels + ch].toInt()
            }
            mono[i] = (sum / channels).toShort()
        }

        if (sampleRate == 16000) {
            val out = ByteBuffer.allocate(mono.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            out.asShortBuffer().put(mono)
            return out.array()
        }

        val ratio = sampleRate.toDouble() / 16000.0
        val outLength = (mono.size / ratio).toInt()
        val outBuffer = ByteBuffer.allocate(outLength * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until outLength) {
            val srcIdx = (i * ratio).toInt().coerceIn(0, mono.size - 1)
            outBuffer.putShort(mono[srcIdx])
        }
        return outBuffer.array()
    }
}
