# Bug 4: LineSink Prebuffer Race Condition

**Issue**: #453 — Choppy audio on Opus profiles (MQ+) during voice calls
**Branch**: `fix/voice-call-quality-453`
**Date**: 2026-02-13

## Root Cause

Two interacting bugs in LineSink caused the AudioTrack's internal buffer to start
nearly empty, leaving no jitter absorption for network gaps:

### 1. Late Buffer Limit Computation

`effectiveAutostartMin` defaults to `AUTOSTART_MIN = 5`. The time-based value
(e.g., 8 frames for MQ at 60ms = 480ms) isn't computed until `digestJob()`
processes the first frame. But `start()` is triggered by the autostart check
in `handleFrame()`, which fires when `frameQueue.size >= 5` — before
`updateBufferLimits()` has ever run.

Result: MQ gets 5 × 60ms = 300ms prebuffer instead of 8 × 60ms = 480ms.
LL gets 5 × 20ms = 100ms instead of 25 × 20ms = 500ms.

### 2. Play-Before-Write Race

`AudioTrack.play()` was called before any audio data was written to the
AudioTrack buffer. The DMA engine starts consuming immediately on `play()`,
racing with the `digestJob()` loop that writes frames. On first start, the
AudioTrack buffer is empty, so the first few frames are consumed as fast as
they're written — no buffering, no jitter absorption.

## Fixes Applied

### Fix A: Early Buffer Limit Detection (LineSink.kt)

Added `bufferLimitsInitialized` flag. Frame time is now detected in
`handleFrame()` from the first frame's size, and `updateBufferLimits()` runs
*before* the autostart check. This ensures `effectiveAutostartMin` is correct
when `start()` triggers.

### Fix B: Prebuffer Before Play (LineSink.kt + AudioDevice.kt)

1. `startPlayback()` now accepts `autoPlay: Boolean = true`
2. LineSink calls `startPlayback(autoPlay = false)` — creates AudioTrack
   without calling `play()`
3. Queued frames are written to AudioTrack BEFORE `play()` — data written
   before `play()` stays in the internal buffer without DMA consumption
4. `beginPlayback()` is called after prebuffering — playback starts with
   a full buffer
5. Pre-play writes are capped to `bufferCapacityBytes / frameSizeBytes` to
   avoid WRITE_BLOCKING deadlock with large-frame profiles (ULBW/VLBW)

### Fix C: writeAudio() Guard Relaxed (AudioDevice.kt)

`writeAudio()` previously returned early if `!isPlaying`. Changed to check
`audioTrack != null` instead, allowing pre-play writes for the prebuffer.

## Test Results (Galaxy S21 Ultra, SM-G998U1)

### Baseline (commit 04e14af, before any fixes)

| Test | Result | Underruns |
|------|--------|-----------|
| underrunRecovery_resumesCleanly | FAIL (flaky) | 0–4 |
| mq_sustainedGap_rebufferThenCleanPlayback | FAIL | 6–7 |
| shq_steadyPlayback_zeroUnderruns | FAIL | 1–2 |
| shq_fullDuplex_rxUnderrunsZero | FAIL | 1 |

### With Prebuffer Fixes (3 runs)

| Test | Run 1 | Run 2 | Run 3 |
|------|-------|-------|-------|
| underrunRecovery | FAIL (3) | PASS (0) | FAIL (2) |
| mq_sustainedGap | FAIL (5) | FAIL (1) | FAIL (4) |
| shq_steady | FAIL (1) | PASS (0) | PASS (0) |
| shq_fullDuplex | PASS (0) | PASS (0) | FAIL (1) |

**Improvement**: SHQ tests now frequently pass (0 underruns). MQ sustained
gap improved from 6–7 to 1–5 underruns. The prebuffer provides meaningful
jitter absorption that wasn't present before.

## Remaining Limitation

After a sustained gap (> REBUFFER_TRIGGER_MS), the WRITE_BLOCKING pacing
model breaks down. AudioTrack's internal buffer is partially drained during
the gap, and once `play()` has been called, WRITE_BLOCKING only blocks when
the buffer is *full*. After a gap, the buffer never refills to capacity —
it stays at the post-gap level, providing reduced jitter absorption for the
remainder of the call.

A more robust fix would involve flushing the AudioTrack and re-prebuffering
after sustained gaps, essentially restarting the prebuffer cycle. This is
deferred pending real-device testing with debug logs.

## Files Changed

- `lxst/src/main/java/tech/torlando/lxst/audio/LineSink.kt` — early buffer
  limit detection, prebuffer-before-play, capped pre-play writes
- `lxst/src/main/java/tech/torlando/lxst/core/AudioDevice.kt` — `autoPlay`
  parameter, `beginPlayback()` method, relaxed `writeAudio()` guard
