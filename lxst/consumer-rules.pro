# Consumer ProGuard rules for LXST-kt
# These rules are automatically included by any app that depends on this library.

# ===== Python-Callable Classes (Chaquopy) =====
# PacketRouter methods onInboundPacket() and onInboundSignal() are called from
# Python (call_manager.py) via Chaquopy reflection. R8 cannot trace these calls,
# so we must explicitly keep the class and all its public methods.
-keep class tech.torlando.lxst.core.PacketRouter { *; }
-keep class tech.torlando.lxst.core.CallCoordinator { *; }
-keep class tech.torlando.lxst.core.AudioDevice { *; }
