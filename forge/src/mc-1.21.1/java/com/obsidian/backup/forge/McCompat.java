package com.obsidian.backup.forge;

import net.minecraft.commands.CommandSourceStack;

/**
 * Minecraft version-specific compatibility shim (1.21.1).
 */
public final class McCompat {
    private McCompat() {}

    /** True if the command source is OP level 2 or above. */
    public static boolean isOp(CommandSourceStack source) {
        return source.hasPermission(2);
    }
}
