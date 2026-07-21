package io.switchlite.adapter.fabric.v1_20

import io.switchlite.adapter.fabric.common.combat.AimModule as CommonAimModule
import io.switchlite.adapter.fabric.common.combat.VelocityModule as CommonVelocityModule

/**
 * Version-specific compatibility layer for Fabric 1.20.x
 * Re-exports common modules with version-specific packet handling if needed
 */
object Compat {
    val aimModule = CommonAimModule
    val velocityModule = CommonVelocityModule
    
    // Add version-specific packet translators here if API changes require it
}
