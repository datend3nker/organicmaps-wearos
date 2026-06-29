package app.organicmaps.wear

import android.util.Log
import app.organicmaps.sdk.Framework
import kotlinx.coroutines.*

/**
 * Serializes and coalesces calls to [Framework.nativeReloadWorldMaps].
 *
 * The native reload (RemoveLocalMaps + AddLocalMaps + Invalidate) is heavy and MUST run on the
 * main thread (Organic Maps framework is not thread-safe). Streaming fires reload on every footer
 * arrival, from 7 call sites — running each one strands the UI thread and produces ANRs.
 *
 * Two guarantees here:
 *  1. Coalescing: while a reload is in flight, additional requests collapse into a single follow-up
 *     run (the `dirty` flag) instead of stacking N back-to-back heavy reloads.
 *  2. Atomic pause/resume: the pause -> native -> resume sequence runs under [NonCancellable] with
 *     resume in a `finally`. Previously a cancel between pause and resume left the surface paused
 *     forever (frozen map, surfaced as `JobCancellationException` in logs). Only the debounce delay
 *     is cancellable now; the critical section always completes.
 */
object ReloadWorldMapsDebouncer {
    private const val TAG = "ReloadWorldMapsDebouncer"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var scheduled: Job? = null
    private var running = false
    private var dirty = false

    @Synchronized
    fun reload() = schedule(1000)

    @Synchronized
    fun reloadImmediate() = schedule(0)

    @Synchronized
    private fun schedule(delayMs: Long) {
        // A reload is already executing: just mark that another pass is wanted. Do NOT cancel it
        // (cancelling mid-execution is what stranded the paused surface).
        if (running) {
            dirty = true
            return
        }
        // Debounce: reset the timer. Cancelling here only cancels the delay, never the reload body.
        scheduled?.cancel()
        scheduled = scope.launch {
            if (delayMs > 0) delay(delayMs)
            runLoop()
        }
    }

    private suspend fun runLoop() {
        synchronized(this) {
            if (running) return
            running = true
            dirty = false
        }
        try {
            do {
                synchronized(this) { dirty = false }
                runReloadAtomic()
            } while (synchronized(this) { dirty })
        } finally {
            synchronized(this) {
                running = false
                scheduled = null
            }
        }
    }

    private suspend fun runReloadAtomic() {
        // pause -> native -> resume is the critical section. NonCancellable guarantees it runs to
        // completion (resume always fires) even if the enclosing scope is cancelled, so the surface
        // is never left paused. Running on Main also serializes against other Main-thread map work,
        // which shrinks the reload-vs-surface-detach window behind the native destroyed-mutex crash.
        withContext(Dispatchers.Main + NonCancellable) {
            try {
                Log.d(TAG, "Executing nativeReloadWorldMaps")
                app.organicmaps.sdk.Map.pauseSurfaceRendering()
                Framework.nativeReloadWorldMaps()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to reload world maps natively", e)
            } finally {
                app.organicmaps.sdk.Map.resumeSurfaceRendering()
            }
        }
    }
}
