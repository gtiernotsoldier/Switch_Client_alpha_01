package io.switchlite.core.strategy.click

/**
 * Weapon category for the 1.9+ weapon filter.
 *
 * Used by [WeaponFilter] to determine which items are
 * acceptable for attacking.
 */
enum class WeaponType {
    /** Swords (wooden through netherite). */
    SWORD,
    /** Axes (wooden through netherite). */
    AXE,
    /** Anything else (tools, bare hands, etc.). */
    OTHER
}

/**
 * Weapon filter for 1.9+ AutoClicker.
 *
 * Controls which held items the AutoClicker will activate for.
 * If the player's main-hand item does not match the filter,
 * the tick is skipped.
 *
 * @property ANY             Click with any item held.
 * @property SWORD           Only click when holding a sword.
 * @property AXE             Only click when holding an axe.
 * @property SWORD_AND_AXE   Click when holding a sword or axe.
 */
enum class WeaponFilter {
    ANY,
    SWORD,
    AXE,
    SWORD_AND_AXE;

    /**
     * Check whether the given [weaponType] passes this filter.
     */
    fun matches(weaponType: WeaponType): Boolean = when (this) {
        ANY -> true
        SWORD -> weaponType == WeaponType.SWORD
        AXE -> weaponType == WeaponType.AXE
        SWORD_AND_AXE -> weaponType == WeaponType.SWORD || weaponType == WeaponType.AXE
    }
}
