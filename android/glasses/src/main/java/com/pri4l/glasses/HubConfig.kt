package com.pri4l.glasses

import android.content.Context

/**
 * Hub connection config, persisted in SharedPreferences ("pri4l_glasses").
 *
 * Entering an IP via the temple touchpad is painful, so the host/port are stored and
 * default to the lab hub. Override without a UI via adb on a debug build:
 *   adb shell run-as com.pri4l.glasses sh -c \
 *     "echo \"<?xml ...><map><string name='host'>X.X.X.X</string></map>\" > shared_prefs/pri4l_glasses.xml"
 */
object HubConfig {
    private const val PREFS = "pri4l_glasses"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val DEFAULT_HOST = "192.168.68.129"
    private const val DEFAULT_PORT = 9090

    fun host(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST

    fun port(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_PORT, DEFAULT_PORT)

    fun setHost(context: Context, host: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_HOST, host).apply()
    }
}
