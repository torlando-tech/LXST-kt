# LXST-kt

Kotlin audio telephony library for [LXST](https://github.com/markqvist/LXST) voice calls over [Reticulum](https://reticulum.network). Extracted from [Columba](https://github.com/torlando-tech/columba).

## What's in the box

- **audio/** — Full-duplex pipeline: mic capture, speaker playback, mixing, tone generation, packetization
- **codec/** — JNI wrappers for Codec2 (narrowband) and Opus (wideband) with prebuilt `arm64-v8a` libs
- **telephone/** — Call state machine, profile negotiation (8 quality profiles from 1200 bps to 48 kHz), mute/unmute
- **core/** — Transport-agnostic coordination layer (CallCoordinator, PacketRouter, AudioDevice)

## Usage

LXST-kt is consumed by Columba via Gradle composite build:

```kotlin
// columba/settings.gradle.kts
includeBuild("LXST-kt") {
    dependencySubstitution {
        substitute(module("tech.torlando:lxst")).using(project(":lxst"))
    }
}
```

## Build

```bash
./gradlew :lxst:compileDebugKotlin        # compile
./gradlew :lxst:testDebugUnitTest          # unit tests
```

## License

LXST Copyright 2026 Mark Qvist. 

This implementation of LXST is derived from the LXST Python implementation,
and used under license for Columba. No derivatives or use outside Columba is permitted.

### Third-party libraries

| Library | License | Link |
|---------|---------|------|
| Opus | BSD-3-Clause | [opus-codec.org](https://opus-codec.org) |
| Codec2 | LGPL-2.1 | [github.com/drowe67/codec2](https://github.com/drowe67/codec2) |

Prebuilt shared libraries (`libopus.so`, `libcodec2.so`) are dynamically linked. JNI wrappers are MIT.
