package com.llama.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder

import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class LlamaServerService : Service() {
    private val binder = LocalBinder()
    private var process: Process? = null
    private var modelPath: String? = null
    var logCallback: ((String) -> Unit)? = null
    private var scope: CoroutineScope? = null

    inner class LocalBinder : Binder() {
        fun getService() = this@LlamaServerService
    }

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(CHANNEL_ID, "llama-server", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("llama-server")
            .setContentText("Running on port 8080")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        startForeground(1, n)
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = binder

    fun setModel(path: String) { modelPath = path }
    fun getModel() = modelPath
    fun isRunning() = process?.isAlive == true

    fun start(modelArg: String) {
        if (isRunning()) return
        modelPath = modelArg
        val binDir = File(filesDir, "bin")
        val binary = File(binDir, "llama-server")
        if (!binary.exists()) {
            logCallback?.invoke("ERROR: llama-server not found")
            return
        }
        val cmd = listOf(
            binary.absolutePath,
            "--model", modelArg,
            "--host", "127.0.0.1",
            "--port", "8080",
            "--ctx-size", "4096",
            "--n-gpu-layers", "0"
        )
        logCallback?.invoke("Starting: ${cmd.joinToString(" ")}")
        scope = CoroutineScope(SupervisorJob() + IO)
        scope?.launch {
            try {
                val libDir = File(filesDir, "lib").absolutePath
                val execArgs = listOf("/system/bin/linker64", binary.absolutePath) + cmd.drop(1)
                val pb = ProcessBuilder(execArgs)
                    .directory(binDir)
                    .redirectErrorStream(true)
                pb.environment()["LD_LIBRARY_PATH"] = "$libDir:/system/lib64:/vendor/lib64"
                process = pb.start()
                BufferedReader(InputStreamReader(process!!.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        logCallback?.invoke(line!!)
                    }
                }
                val exit = process!!.waitFor()
                logCallback?.invoke("Server exited with code $exit")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (e: Exception) {
                logCallback?.invoke("Server error: ${e.message}")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    fun stop() {
        process?.destroyForcibly()
        scope?.cancel()
        process = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "llama_server_channel"
    }
}
