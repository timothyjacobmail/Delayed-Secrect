package com.example.delayedsecrect

import android.os.SystemClock

enum class RevealState { LOCKED, COUNTING_DOWN, REVEALED }

data class TimerInfo(
    val state: RevealState,
    val revealAtElapsedMs: Long = 0L,
    val hideAtElapsedMs: Long = 0L
)

class RevealTimer(
    private val secretId: String,
    private val delayMillis: Long,
    private val visibleMillis: Long,
    private val store: TimerStore
) {
    private var started = false
    private var revealAtElapsedMs = 0L
    private var hideAtElapsedMs = 0L
    private var revealAtWallClockMs = 0L
    private var hideAtWallClockMs = 0L

    init {
        reconcileFromDisk()
    }

    private fun reconcileFromDisk() {
        val record = store.get(secretId) ?: return
        if (!record.started) return

        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWallClock = System.currentTimeMillis()
        val currentBootReference = nowWallClock - nowElapsed

        val rebooted = kotlin.math.abs(currentBootReference - record.bootReferenceMs) > 3000 // 3s tolerance

        started = true
        if (!rebooted) {
            // Same boot session -> trust the tamper-proof elapsed values directly
            revealAtElapsedMs = record.revealAtElapsedMs
            hideAtElapsedMs = record.hideAtElapsedMs
        } else {
            // Reboot happened -> recompute elapsed targets from the wall-clock backup
            revealAtElapsedMs = nowElapsed + (record.revealAtWallClockMs - nowWallClock)
            hideAtElapsedMs = if (record.hideAtElapsedMs == 0L) 0L
            else nowElapsed + (record.hideAtWallClockMs - nowWallClock)
        }
        revealAtWallClockMs = record.revealAtWallClockMs
        hideAtWallClockMs = record.hideAtWallClockMs
    }

    private fun persist() {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWallClock = System.currentTimeMillis()
        store.save(
            secretId,
            TimerRecord(
                started = started,
                revealAtElapsedMs = revealAtElapsedMs,
                hideAtElapsedMs = hideAtElapsedMs,
                revealAtWallClockMs = revealAtWallClockMs,
                hideAtWallClockMs = hideAtWallClockMs,
                bootReferenceMs = nowWallClock - nowElapsed
            )
        )
    }

    fun startCountdown() {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWallClock = System.currentTimeMillis()
        revealAtElapsedMs = nowElapsed + delayMillis
        revealAtWallClockMs = nowWallClock + delayMillis
        hideAtElapsedMs = 0L
        hideAtWallClockMs = 0L
        started = true
        persist()
    }

    /** Only valid while counting down. Pushes the reveal time further out.
     *  One-way only — there is no function that pulls it back in. */
    fun extendCountdown(extraMillis: Long) {
        if (!started || hideAtElapsedMs != 0L) return // ignore if not actively counting down
        revealAtElapsedMs += extraMillis
        revealAtWallClockMs += extraMillis
        persist()
    }

    fun cancel() {
        reset()
    }

    fun closeEarly() {
        reset()
    }

    private fun reset() {
        started = false
        revealAtElapsedMs = 0L
        hideAtElapsedMs = 0L
        revealAtWallClockMs = 0L
        hideAtWallClockMs = 0L
        store.clear(secretId)
    }

    fun currentState(): TimerInfo {
        if (!started) return TimerInfo(RevealState.LOCKED)

        val nowElapsed = SystemClock.elapsedRealtime()

        if (hideAtElapsedMs == 0L && nowElapsed < revealAtElapsedMs) {
            return TimerInfo(RevealState.COUNTING_DOWN, revealAtElapsedMs, 0L)
        }

        if (hideAtElapsedMs == 0L && nowElapsed >= revealAtElapsedMs) {
            hideAtElapsedMs = nowElapsed + visibleMillis
            hideAtWallClockMs = System.currentTimeMillis() + visibleMillis
            persist()
        }

        if (nowElapsed < hideAtElapsedMs) {
            return TimerInfo(RevealState.REVEALED, revealAtElapsedMs, hideAtElapsedMs)
        }

        reset()
        return TimerInfo(RevealState.LOCKED)
    }
}