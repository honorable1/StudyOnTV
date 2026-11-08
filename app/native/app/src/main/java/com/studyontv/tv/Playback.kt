package com.studyontv.tv

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock

/**
 * 当前是否真的有媒体在播放（视频 / 音乐）。
 *
 * 为什么需要：部分电视把播放器直接做进了桌面 Activity。典型是海信 VIDAA 的「聚好看」，
 * HOME 包名本身就是 `com.jamdeo.tv.vod`，全屏看片时前台 Activity 仍是它自己的 LauncherActivity。
 * 只看包名会把「在看片」误判成「停在桌面」，于是不扣时间、到点也不弹题（issue #66）。
 *
 * 判定用两个公开信号取并集（AudioPlaybackConfiguration 的 playerState 是隐藏接口，不能用）：
 *  1. AudioManager.isMusicActive()
 *  2. getActivePlaybackConfigurations() 非空 —— 该列表只包含当前处于活动状态的播放器
 *
 * 并加 3 秒滞回，避免切视频、翻页时计时抖动。
 */
object Playback {
    private var lastActiveAt = 0L
    private const val HYSTERESIS_MS = 3000L

    fun hasActive(context: Context): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val music = musicActive(audio)
        val players = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) audio.activePlaybackConfigurations.isNotEmpty() else false
        } catch (_: Throwable) { false }
        val playing = music || players
        val now = SystemClock.elapsedRealtime()
        if (playing) lastActiveAt = now
        return playing || now - lastActiveAt < HYSTERESIS_MS
    }

    @Suppress("DEPRECATION")
    private fun musicActive(audio: AudioManager): Boolean = try { audio.isMusicActive } catch (_: Throwable) { false }
}
