package com.obsidian.backup

import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permissions

/**
 * Minecraft version-specific compatibility shim (1.21.11).
 *
 * MC 1.21.2+ replaced integer permission levels with the `Permission` object
 * system. `GAMEMASTER` corresponds to the old operator level 2.
 */
object McCompat {
    /** True if the command source is OP level 2 (gamemaster) or above. */
    fun isOp(source: CommandSourceStack): Boolean =
        source.hasPermission(Permissions.COMMANDS_GAMEMASTER)

    /** True if the player is OP level 2 (gamemaster) or above. */
    fun isOp(player: ServerPlayer): Boolean =
        player.hasPermission(Permissions.COMMANDS_GAMEMASTER)
}
