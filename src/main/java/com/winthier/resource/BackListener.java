package com.winthier.resource;

import com.cavetale.core.back.Back;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

@RequiredArgsConstructor
public final class BackListener implements Listener {
    private final ResourcePlugin plugin;

    protected void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    private void onPlayerQuitBack(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (player.isDead()) return;
        if (!player.hasPermission("resource.back")) return;
        if (!plugin.isMineWorld(player.getWorld())) return;
        Back.setBackLocation(player, plugin, player.getLocation(), "Mine world logout");
    }

    @EventHandler
    private void onPlayerJoinBack(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("resource.back")) return;
        if (!plugin.isMineWorld(player.getWorld())) return;
        Back.resetBackLocation(player, plugin);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    private void onPlayerTeleportBack(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (player.isDead()) return;
        if (!player.hasPermission("resource.back")) return;
        if (event.getFrom().getWorld().equals(event.getTo().getWorld())) return;
        boolean from = plugin.isMineWorld(event.getFrom().getWorld());
        boolean to = plugin.isMineWorld(event.getTo().getWorld());
        if (from && !to) {
            // Warp out of a mining worlds
            Back.setBackLocation(player, plugin, player.getLocation(), "Mine world teleport");
        }
    }

    @EventHandler
    private void onPlayerDeathBack(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!player.isDead()) return;
        if (!player.hasPermission("resource.back")) return;
        if (!plugin.isMineWorld(player.getWorld())) return;
        Back.resetBackLocation(player);
    }
}
