package com.example.smartclipper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class SceneAdapter(
    private val scenes: List<VideoScene>,
    private val onPreviewClick: (VideoScene) -> Unit,
    private val onExportClick: (VideoScene) -> Unit
) : RecyclerView.Adapter<SceneAdapter.SceneViewHolder>() {

    class SceneViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSceneBadge: TextView = view.findViewById(R.id.tvSceneBadge)
        val tvScoreBadge: TextView = view.findViewById(R.id.tvScoreBadge)
        val tvTimeRange: TextView = view.findViewById(R.id.tvTimeRange)
        val btnPreviewScene: TextView = view.findViewById(R.id.btnPreviewScene)
        val btnExportScene: TextView = view.findViewById(R.id.btnExportScene)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SceneViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scene, parent, false)
        return SceneViewHolder(view)
    }

    override fun onBindViewHolder(holder: SceneViewHolder, position: Int) {
        val scene = scenes[position]
        val durationSec = ((scene.endMs - scene.startMs) / 1000L).toInt()
        holder.tvSceneBadge.text = "SCENE #${scene.index}"
        holder.tvScoreBadge.text = scene.label
        holder.tvTimeRange.text = "${formatTime(scene.startMs)} - ${formatTime(scene.endMs)} (${durationSec}d)"

        holder.btnPreviewScene.setOnClickListener { onPreviewClick(scene) }
        holder.btnExportScene.setOnClickListener { onExportClick(scene) }
    }

    override fun getItemCount(): Int = scenes.size

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000L
        val minutes = totalSec / 60L
        val seconds = totalSec % 60L
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
