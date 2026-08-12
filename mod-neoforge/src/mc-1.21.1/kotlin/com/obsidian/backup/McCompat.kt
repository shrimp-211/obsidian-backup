package com.obsidian.backup

import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer

/**
 * Minecraft version-specific compatibility shim (1.21.1).
 *
 * In MC 1.21.1 and earlier, operator permissions are expressed as integer
 * levels via `hasPermission(level)`. MC 1.21.2+ replaces this with the
 * `Permission` object system; this object abstracts that difference so the
 * shared command code stays version-agnostic.
 */
object McCompat {
    /** True if the command source is OP level 2 (or level 4, integrated server). */
    fun isOp(source: CommandSourceStack): Boolean =
        source.hasPermission(2) || source.hasPermission(4)

    /** True if the player is OP level 2 or above. */
    fun isOp(player: ServerPlayer): Boolean = player.hasPermissions(2)
}
