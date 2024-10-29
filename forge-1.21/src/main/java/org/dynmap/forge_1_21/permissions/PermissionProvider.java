package org.dynmap.forge_1_21.permissions;

import net.minecraft.server.level.ServerPlayer;

import java.util.Set;

public interface PermissionProvider {
    boolean has(ServerPlayer sender, String permission);

    boolean hasPermissionNode(ServerPlayer sender, String permission);

    Set<String> hasOfflinePermissions(String player, Set<String> perms);

    boolean hasOfflinePermission(String player, String perm);

}
