package com.example.smartclipper

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var cardVideoPicker: LinearLayout
    private lateinit var cardVideoPlayer: LinearLayout
    private lateinit var videoPreview: VideoView
    private lateinit var tvVideoInfo: TextView
    private lateinit var btnChangeVideo: TextView
    private lateinit var rgMode: RadioGroup
    private lateinit var rgDuration: RadioGroup
    private lateinit var rgRatio: RadioGroup
    private lateinit var btnAnalyze: TextView
    private lateinit var layoutProgress: LinearLayout
    private lateinit var tvProgressStatus: TextView
    private lateinit var layoutResultsHeader: LinearLayout
    private lateinit var tvSceneCount: TextView
    private lateinit var btnExportAll: TextView
    private lateinit var rvScenes: RecyclerView

    private lateinit var analyzer: SmartSceneAnalyzer
    private var selectedVideoUri: Uri? = null
    private val detectedScenes = mutableListOf<VideoScene>()
    private lateinit var sceneAdapter: SceneAdapter
    private val previewHandler = Handler(Looper.getMainLooper())
    private var stopPreviewRunnable: Runnable? = null

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                onVideoSelected(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        analyzer = SmartSceneAnalyzer(this)

        cardVideoPicker = findViewById(R.id.cardVideoPicker)
        cardVideoPlayer = findViewById(R.id.cardVideoPlayer)
        videoPreview = findViewById(R.id.videoPreview)
        tvVideoInfo = findViewById(R.id.tvVideoInfo)
        btnChangeVideo = findViewById(R.id.btnChangeVideo)
        rgMode = findViewById(R.id.rgMode)
        rgDuration = findViewById(R.id.rgDuration)
        rgRatio = findViewById(R.id.rgRatio)
        btnAnalyze = findViewById(R.id.btnAnalyze)
        layoutProgress = findViewById(R.id.layoutProgress)
        tvProgressStatus = findViewById(R.id.tvProgressStatus)
        layoutResultsHeader = findViewById(R.id.layoutResultsHeader)
        tvSceneCount = findViewById(R.id.tvSceneCount)
        btnExportAll = findViewById(R.id.btnExportAll)
        rvScenes = findViewById(R.id.rvScenes)

        sceneAdapter = SceneAdapter(
            scenes = detectedScenes,
            onPreviewClick = { scene -> playScenePreview(scene) },
            onExportClick = { scene -> exportSingleScene(scene) }
        )
        rvScenes.layoutManager = LinearLayoutManager(this)
        rvScenes.adapter = sceneAdapter

        cardVideoPicker.setOnClickListener { pickVideoLauncher.launch("video/*") }
        btnChangeVideo.setOnClickListener { pickVideoLauncher.launch("video/*") }
        btnAnalyze.setOnClickListener { analyzeSelectedVideo() }
        btnExportAll.setOnClickListener { exportAllScenes() }
    }

    private fun onVideoSelected(uri: Uri) {
        selectedVideoUri = uri
        cardVideoPicker.visibility = View.GONE
        cardVideoPlayer.visibility = View.VISIBLE
        layoutResultsHeader.visibility = View.GONE
        detectedScenes.clear()
        sceneAdapter.notifyDataSetChanged()

        val durationMs = analyzer.getVideoDurationMs(uri)
        val totalSec = durationMs / 1000L
        val min = totalSec / 60L
        val sec = totalSec % 60L
        tvVideoInfo.text = String.format(Locale.US, "Durasi Video: %02d:%02d • Siap Dianalisa", min, sec)

        videoPreview.setVideoURI(uri)
        videoPreview.setOnPreparedListener { mp ->
            mp.isLooping = false
            videoPreview.seekTo(100)
        }
    }

    private fun getSelectedClipDurationSec(): Int {
        return when (rgDuration.checkedRadioButtonId) {
            R.id.rb15s -> 15
            R.id.rb45s -> 45
            R.id.rb60s -> 60
            else -> 30
        }
    }

    private fun isSmartHighlightMode(): Boolean {
        return rgMode.checkedRadioButtonId == R.id.rbSmartHighlight
    }

    private fun isFullCropMode(): Boolean {
        return rgRatio.checkedRadioButtonId == R.id.rbCrop916
    }

    private fun analyzeSelectedVideo() {
        val uri = selectedVideoUri
        if (uri == null) {
            Toast.makeText(this, "Pilih video terlebih dahulu!", Toast.LENGTH_SHORT).show()
            return
        }

        layoutProgress.visibility = View.VISIBLE
        tvProgressStatus.text = "Menganalisa momen & membagi scene..."
        btnAnalyze.isEnabled = false

        lifecycleScope.launch {
            try {
                val results = analyzer.detectScenes(
                    uri = uri,
                    clipDurationSec = getSelectedClipDurationSec(),
                    smartHighlight = isSmartHighlightMode()
                )
                detectedScenes.clear()
                detectedScenes.addAll(results)
                sceneAdapter.notifyDataSetChanged()

                if (results.isNotEmpty()) {
                    layoutResultsHeader.visibility = View.VISIBLE
                    tvSceneCount.text = "${results.size} Scene Siap Export"
                } else {
                    Toast.makeText(this@MainActivity, "Gagal membaca durasi video.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error analisa: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                layoutProgress.visibility = View.GONE
                btnAnalyze.isEnabled = true
            }
        }
    }

    private fun playScenePreview(scene: VideoScene) {
        stopPreviewRunnable?.let { previewHandler.removeCallbacks(it) }
        videoPreview.seekTo(scene.startMs.toInt())
        videoPreview.start()
        Toast.makeText(this, "Memutar ${scene.label}...", Toast.LENGTH_SHORT).show()

        val duration = (scene.endMs - scene.startMs).coerceAtLeast(1000L)
        val runnable = Runnable {
            if (videoPreview.isPlaying) {
                videoPreview.pause()
            }
        }
        stopPreviewRunnable = runnable
        previewHandler.postDelayed(runnable, duration)
    }

    private fun exportSingleScene(scene: VideoScene) {
        val uri = selectedVideoUri ?: return
        layoutProgress.visibility = View.VISIBLE
        tvProgressStatus.text = "Merender Scene #${scene.index} ke format 9:16..."

        lifecycleScope.launch {
            try {
                val savedName = analyzer.exportSceneToGallery(uri, scene, isFullCropMode())
                Toast.makeText(
                    this@MainActivity,
                    "Tersimpan di Galeri (Movies/SmartClipper): $savedName",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Gagal export: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                layoutProgress.visibility = View.GONE
            }
        }
    }

    private fun exportAllScenes() {
        val uri = selectedVideoUri ?: return
        if (detectedScenes.isEmpty()) return

        layoutProgress.visibility = View.VISIBLE
        btnExportAll.isEnabled = false

        lifecycleScope.launch {
            var successCount = 0
            try {
                for ((idx, scene) in detectedScenes.withIndex()) {
                    tvProgressStatus.text = "Merender Scene ${idx + 1} dari ${detectedScenes.size} (9:16)..."
                    analyzer.exportSceneToGallery(uri, scene, isFullCropMode())
                    successCount++
                }
                Toast.makeText(
                    this@MainActivity,
                    "Selesai! $successCount klip tersimpan di Galeri (Movies/SmartClipper)",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Berhasil $successCount klip, terhenti: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                layoutProgress.visibility = View.GONE
                btnExportAll.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPreviewRunnable?.let { previewHandler.removeCallbacks(it) }
    }
}
