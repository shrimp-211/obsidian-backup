package com.obsidian.backup.fabric;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.Permissions;

/**
 * Minecraft version-specific compatibility shim (1.21.11).
 *
 * MC 1.21.2+ replaced integer operator levels with the {@code Permission}
 * object system. {@code GAMEMASTER} corresponds to the old level 2.
 */
public final class McCompat {
    private McCompat() {}

    /** True if the command source is OP level 2 (gamemaster) or above. */
    public static boolean isOp(CommandSourceStack source) {
        return source.hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }
}
