# Consumer ProGuard rules for LXST-kt
# These rules are automatically included by any app that depends on this library.

# ===== Python-Callable Classes (Chaquopy) =====
# PacketRouter methods onInboundPacket() and onInboundSignal() are called from
# Python (call_manager.py) via Chaquopy reflection. R8 cannot trace these calls,
# so we must explicitly keep the class and all its public methods.
-keep class tech.torlando.lxst.core.PacketRouter { *; }
-keep class tech.torlando.lxst.core.CallCoordinator { *; }
-keep class tech.torlando.lxst.core.AudioDevice { *; }

# ===== JNI RegisterNatives targets (Codec2 / Opus) =====
# NativeCodec2 and NativeOpus register their native methods from JNI_OnLoad via
# FindClass + RegisterNatives against the exact class name and a fixed method
# table (create/destroy/encode/decode/...). A consumer's global
# `-keepclasseswithmembernames class * { native <methods>; }` keeps the NAMES of
# surviving natives but ALLOWS SHRINKING — and R8 cannot trace the capture path
# (driven via Oboe native callbacks / Chaquopy), so it removes the `encode`
# native method as "unused". RegisterNatives then can't find `encode` on the
# class and JNI_OnLoad returns JNI_ERR, crashing outbound calls with
# UnsatisfiedLinkError -> NoClassDefFoundError. Pin both classes fully so every
# method in the native table is present.
-keep class tech.torlando.lxst.codec.NativeCodec2 { *; }
-keep class tech.torlando.lxst.codec.NativeOpus { *; }
