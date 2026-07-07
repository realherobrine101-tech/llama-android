package com.llama.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var binding: com.llama.app.databinding.ActivityMainBinding
    private var svc: LlamaServerService? = null
    private var extractJob: kotlinx.coroutines.Job? = null

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch { onModelPicked(uri) }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            svc = (service as LlamaServerService.LocalBinder).getService()
            svc?.logCallback = { line -> runOnUiThread { log(line) } }
            syncState()
        }
        override fun onServiceDisconnected(n: ComponentName) { svc = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = com.llama.app.databinding.ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        File(filesDir, "models").mkdirs()
        File(filesDir, "bin").mkdirs()

        if (!prefs().getBoolean("extracted", false)) {
            extractJob = lifecycleScope.launch { extractBinary() }
        }

        binding.btnPickModel.setOnClickListener { pickModel.launch(arrayOf("*/*")) }
        binding.btnToggle.setOnClickListener { toggle() }

        val p = prefs()
        val saved = p.getString("model_path", null)
        if (saved != null && p.getBoolean("remember", false) && File(saved).exists()) {
            binding.tvModelPath.text = File(saved).name
            binding.cbRemember.isChecked = true
            binding.btnToggle.isEnabled = true
            svc?.setModel(saved)
        }
        bindService(Intent(this, LlamaServerService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        unbindService(conn)
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences("llama", MODE_PRIVATE)

    private suspend fun onModelPicked(uri: Uri) {
        log("Copying model...")
        val name = uri.lastPathSegment ?: "model.gguf"
        val dest = File(filesDir, "models/$name")
        if (!dest.exists()) {
            withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { i ->
                    FileOutputStream(dest).use { o -> i.copyTo(o) }
                }
            }
        }
        val path = dest.absolutePath
        binding.tvModelPath.text = name
        binding.btnToggle.isEnabled = true
        log("Model: $path")
        if (binding.cbRemember.isChecked) {
            prefs().edit().putString("model_path", path).putBoolean("remember", true).apply()
        }
        extractJob?.join()
        svc?.setModel(path)
        Snackbar.make(binding.root, "Model ready", Snackbar.LENGTH_SHORT).show()
    }

    private fun toggle() {
        val s = svc ?: return
        if (s.isRunning()) {
            s.stop()
            binding.btnToggle.text = getString(R.string.start)
            binding.tvStatus.text = getString(R.string.status_stopped)
            binding.btnPickModel.isEnabled = true
            return
        }
        val model = s.getModel() ?: prefs().getString("model_path", null) ?: return
        if (!File(model).exists()) { log("Model not found: $model"); return }
        s.start(model)
        binding.btnToggle.text = getString(R.string.stop)
        binding.tvStatus.text = getString(R.string.server_starting)
        binding.btnPickModel.isEnabled = false
    }

    private suspend fun extractBinary() = withContext(Dispatchers.IO) {
        val binDir = File(filesDir, "bin")
        val libDir = File(filesDir, "lib")
        binDir.mkdirs()
        libDir.mkdirs()
        val f = File(binDir, "llama-server")
        if (!f.exists()) {
            assets.open("llama-server").use { i ->
                FileOutputStream(f).use { o -> i.copyTo(o) }
            }
            f.setWritable(false)
            f.setExecutable(true)
            withContext(Dispatchers.Main) { log("Binary: ${f.absolutePath}") }
        }
        assets.list("lib")?.forEach { name ->
            val lib = File(libDir, name)
            if (!lib.exists() || lib.length() == 0L) {
                assets.open("lib/$name").use { i ->
                    FileOutputStream(lib).use { o -> i.copyTo(o) }
                }
            }
        }
        try {
            val vulkanLink = File(libDir, "libvulkan.so.1")
            if (!vulkanLink.exists()) {
                Runtime.getRuntime().exec(arrayOf("ln", "-s", "/system/lib64/libvulkan.so", vulkanLink.absolutePath)).waitFor()
            }
        } catch (_: Exception) {}
        if (assets.list("lib")?.any { !File(libDir, it).exists() } == false) {
            prefs().edit().putBoolean("extracted", true).apply()
        }
        withContext(Dispatchers.Main) { log("Libraries extracted to ${libDir.absolutePath}") }
    }

    fun log(line: String) {
        binding.tvLog.append("$line\n")
        val l = binding.tvLog.layout ?: return
        if (l.lineCount > 0) {
            val top = l.getLineTop(l.lineCount - 1) - binding.tvLog.height
            binding.tvLog.scrollTo(0, if (top > 0) top else 0)
        }
    }

    private fun syncState() {
        val s = svc ?: return
        binding.btnToggle.text = if (s.isRunning()) getString(R.string.stop) else getString(R.string.start)
        binding.tvStatus.text = if (s.isRunning()) getString(R.string.status_running, 8080) else getString(R.string.status_stopped)
        binding.btnPickModel.isEnabled = !s.isRunning()
    }
}
