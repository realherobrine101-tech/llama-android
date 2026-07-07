# llama.cpp Android App

Android app that bundles Termux-built `llama-server` binary + shared libs in APK assets, extracts at runtime, spawns server as foreground service subprocess, and exposes `/v1/chat/completions` on `localhost:8080`.

## Key Architecture

- **`MainActivity.kt`** — single activity: pick GGUF model, start/stop server, scrollable log. Extraction runs once (flag in SharedPreferences). Survives rotation.
- **`LlamaServerService.kt`** — foreground service. Launches binary via `ProcessBuilder` + `/system/bin/linker64` (bypasses Android's no-exec from app data). Reads stdout line-by-line into log callback.
- **Assets**: `assets/llama-server` (85MB), `assets/lib/*.so*` (9 libs incl. Termux's `libc++_shared.so`, `libssl.so.3`, etc.)

## Key Hurdles Solved

| Problem | Fix |
|---------|-----|
| Versioned sonames (`.so.0`, `.so.3`) stripped by AGP | Put libs in `assets/lib/` not `jniLibs/`, extract at runtime |
| `execve()` blocked from app data dir on SDK 36 | Run via `/system/bin/linker64` as the executable, binary path as arg |
| Missing `libvulkan.so.1` on device | Create symlink `libvulkan.so.1` -> `/system/lib64/libvulkan.so` at runtime |
| Missing `libc++_shared.so` (Termux-built binary) | Bundle Termux's `libc++_shared.so` in `assets/lib/` |
| Binary/extraction on every Activity recreate | Guard with `!f.exists()` + `prefs("extracted")` flag |

## Build & Deploy

```
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm clear com.llama.app   # reset for clean test
```

## Files

- `app/src/main/java/com/llama/app/MainActivity.kt`
- `app/src/main/java/com/llama/app/LlamaServerService.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/assets/llama-server` + `assets/lib/*.so*`
