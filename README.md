# Duo Fold Wallpaper

[![Build](https://github.com/Vyom-2007/DuoFoldWallpaper/actions/workflows/build.yml/badge.svg)](https://github.com/Vyom-2007/DuoFoldWallpaper/actions)
[![Release](https://img.shields.io/github/v/release/Vyom-2007/DuoFoldWallpaper)](https://github.com/Vyom-2007/DuoFoldWallpaper/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![minSdk](https://img.shields.io/badge/minSdk-33-3DDC84)](https://android-arsenal.com/api?level=33)
[![Target](https://img.shields.io/badge/Foldable-Ready-purple.svg)](https://developer.android.com/guide/topics/ui/foldables)

An open-source Android live wallpaper that ports the viral **iPhone Duo fold-transition effect** (hinge-anchored ray-plane perspective compression, progressive blur, and cover-display window projection) to Android foldables (Google Pixel Fold, Samsung Galaxy Z Fold series, and more).

Powered by `Sensor.TYPE_HINGE_ANGLE` and a hardware-accelerated **AGSL (Android Graphics Shading Language) `RuntimeShader`**.

---

## ⚡ Key Engineering Features
1. **Hardware Hinge-Angle Sensor (`Sensor.TYPE_HINGE_ANGLE`)**: Driven at 120 Hz with a low-pass smoothing filter to eliminate jitter.
2. **Battery-Conscious Choreographer Loop**: Renders only during active motion or lighting transitions; pauses automatically when idling.
3. **2D AGSL 3D Ray-Plane Projection Shader (`ShaderCode.kt`)**: Computes exact camera-to-surface ray intersections for authentic foreshortening and real-time lighting across the crease.
4. **Dual-Resolution 25-Tap Progressive Blur**: Scaled 5x5 weighted Gaussian kernel with downscaled texture sampling.
5. **Multi-Display Engine Architecture (`DuoWallpaperService.kt`)**: Independent engines per display supporting outer window projection.
6. **Dynamic Hardware Abstraction (`DeviceConfig.kt`)**: Dynamic geometry computation with pre-calibrated profiles for Pixel and Galaxy Z Fold devices.
7. **Automatic Solar Wallpaper Scheduler (`SolarScheduleManager.kt`)**: Location-aware twilight calculations that crossfade between day and night shaders.

---

## 🐛 Bug Fixes
- **Fixed Cover Screen Black Bar**: Resolved an aspect-ratio calculation issue causing unwanted black letterboxing/pillarboxing bars on the outer cover display when folded.

---

## 🛠️ Build & Installation

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 35 (Minimum API 33 required for AGSL `RuntimeShader`)

### Compile from Source
```bash
# Clone the repository
git clone [https://github.com/Vyom-2007/DuoFoldWallpaper.git](https://github.com/Vyom-2007/DuoFoldWallpaper.git)
cd DuoFoldWallpaper

# Build debug APK
./gradlew assembleDebug

# Install via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
