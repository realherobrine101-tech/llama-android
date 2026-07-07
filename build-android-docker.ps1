param(
    [int]$TimeoutMinutes = 30
)

$root = Split-Path $PSScriptRoot -Parent
$llamaCpp = Join-Path $root "llama.cpp"

# Build the Docker image (cached after first run)
Write-Host "Building Docker image (first run downloads NDK, may take 10-20 min)..."
docker build -t llama-android-builder -f "$PSScriptRoot\scripts\build-android-env.dockerfile" "$PSScriptRoot\scripts"

if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker build failed" -ForegroundColor Red
    exit 1
}

Write-Host "Running build for Android arm64-v8a..."
docker run --rm -v "${llamaCpp}:/src" llama-android-builder bash /src/scripts/build-android.sh

if ($LASTEXITCODE -eq 0) {
    Write-Host "Build succeeded!" -ForegroundColor Green
    $output = Join-Path $llamaCpp "build-android-arm64/output"
    Write-Host "Artifacts in: $output"
    Get-ChildItem $output
} else {
    Write-Host "Build failed" -ForegroundColor Red
}
