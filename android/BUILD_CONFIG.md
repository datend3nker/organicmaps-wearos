# Organic Maps Wear OS - Build Configurations

This project uses a combination of **Product Flavors** and **Build Types** to support various wearable devices and optimization workflows.

## 1. IDE Run Configurations
I have streamlined the IDE dropdown menu to show clear, descriptive tasks:

| Configuration Name | Purpose |
| :--- | :--- |
| **`Run Wear App (Google Debug)`** | Standard development build with Phone Sync (GMS). |
| **`Run Wear App (OSS Standalone)`** | Pure standalone build with native rendering testing. |
| **`Run Phone App (Debug)`** | Launches the main mobile application. |
| **`OPTIMIZE: Generate Performance Profile`** | Executes benchmarks to pre-compile the app. |

## 2. Product Flavors
Flavors determine the external dependencies and syncing capabilities.

| Flavor | Purpose | Sync Method |
| :--- | :--- | :--- |
| **`google`** | Standard Wear OS build. | Google Play Services (GMS) Wearable Data Layer. |
| **`fdroid`** | Build for the F-Droid store. | GMS-compatible (works with MicroG). |
| **`oss`** | Pure Open Source version. | **Standalone only**. No Google dependencies. |

## 3. Build Types & Worker Variants
Build types determine whether the app is for development, optimization, or production.

| Type | Purpose | Status in IDE |
| :--- | :--- | :--- |
| **`debug`** | Active development. | Visible |
| **`release`** | Production release. | Visible |
| **`benchmarkRelease`** | **Worker Type**: Used by Baseline Profile generator. | Hidden (Technical) |
| **`nonMinifiedRelease`** | **Worker Type**: Used for un-shrunk profiling. | Hidden (Technical) |

---

## 4. Performance Optimization (Baseline Profiles)
The project includes a `:baselineprofile` module. This module pre-compiles critical code paths (Startup, Map Panning, Search) to eliminate JIT overhead.

**How to optimize:**
Run the shared run configuration: `OPTIMIZE: Generate Performance Profile`. 
This task executes the benchmarks on a connected device/emulator and saves the profile to the source code.
