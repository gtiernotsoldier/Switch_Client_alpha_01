package io.switchlite.core.model

import io.switchlite.core.util.Vec3

/**
 * PlatformCommand: Sealed class representing commands from Module to Adapter.
 * Ensures type-safe communication without exposing Minecraft objects.
 */
sealed class PlatformCommand {
    data class ModifyMotion(val motion: Vec3) : PlatformCommand()
    data class ClickBurst(val targetId: Int, val times: Int) : PlatformCommand()
    data class CancelPacket(val handle: Any) : PlatformCommand()
    data class Pass(val originalMotion: Vec3) : PlatformCommand()
    object NoOp : PlatformCommand()
}
