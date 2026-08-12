package com.obsidian.backup.forge;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.Permissions;

/**
 * Minecraft version-specific compatibility shim (1.21.11).
 */
public final class McCompat {
    private McCompat() {}

    /** True if the command source is OP level 2 (gamemaster) or above. */
    public static boolean isOp(CommandSourceStack source) {
        return source.hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }
}
