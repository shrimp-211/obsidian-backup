package com.obsidian.backup.fabric;

import net.minecraft.commands.CommandSourceStack;

/**
 * Minecraft version-specific compatibility shim (1.21.1).
 *
 * MC 1.21.1 uses integer operator levels; 1.21.2+ uses the {@code Permission}
 * object system. This shim abstracts that difference.
 */
public final class McCompat {
    private McCompat() {}

    /** True if the command source is OP level 2 or above. */
    public static boolean isOp(CommandSourceStack source) {
        return source.hasPermission(2);
    }
}
