package com.winthier.resource;

import com.cavetale.sidebar.PlayerSidebarEvent;
import com.cavetale.sidebar.Priority;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.*;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

@RequiredArgsConstructor
public final class SidebarListener implements Listener {
    private final ResourcePlugin plugin;

    protected void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    protected void onPlayerSidebar(PlayerSidebarEvent event) {
        if (!plugin.worldNames.contains(event.getPlayer().getWorld().getName())) return;
        List<Component> lines = new ArrayList<>();
        lines.add(text("/mine", YELLOW)
                  .append(text(" world", AQUA)));
        if (plugin.doMineReset) {
            if (plugin.resetImminent) {
                lines.add(text("Reset ", AQUA)
                          .append(text("Imminent", RED)));
            } else {
                long days = plugin.timeUntilReset.toDays();
                String timeString = days > 0
                    ? (days + "d"
                       + " " + (plugin.timeUntilReset.toHours() % 24) + "h"
                       + " " + (plugin.timeUntilReset.toMinutes() % 60) + "m")
                    : ((plugin.timeUntilReset.toHours() % 24) + "h"
                       + " " + (plugin.timeUntilReset.toMinutes() % 60) + "m"
                       + " " + (plugin.timeUntilReset.toSeconds() % 60) + "s");

                lines.add(text("Reset in ", AQUA)
                          .append(text(timeString, WHITE)));
            }
        }
        event.add(plugin, Priority.LOW, lines);
    }
}
