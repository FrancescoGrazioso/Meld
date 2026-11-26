/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.metrolist.music.playback.MusicService

/**
 * YouTube Music style 4x1 widget.
 *
 * Its buttons reuse [MusicWidgetReceiver]'s actions, so the play/pause, like, next and previous
 * clicks are delivered straight to that receiver and handled by [MusicService] like any other
 * player widget. This class therefore only needs to ask for a re-render.
 */
class Music4x1WidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        requestWidgetUpdate(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        requestWidgetUpdate(context)
    }

    /**
     * Only triggers an update through [MusicService] if it is already running. This prevents
     * BackgroundServiceStartNotAllowedException on Android 14+.
     */
    private fun requestWidgetUpdate(context: Context) {
        if (!MusicService.isRunning) return

        val intent = Intent(context, MusicService::class.java).apply {
            action = MusicWidgetReceiver.ACTION_UPDATE_WIDGET
        }
        try {
            context.startService(intent)
        } catch (e: Exception) {
            // Service might be restricted in background
        }
    }
}
