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

[MPL-2.0](LICENSE)
