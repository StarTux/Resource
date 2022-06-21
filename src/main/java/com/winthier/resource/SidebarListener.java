package com.winthier.resource;

import com.cavetale.core.event.hud.PlayerHudEvent;
import com.cavetale.core.event.hud.PlayerHudPriority;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import static com.cavetale.core.font.Unicode.tiny;
import static net.kyori.adventure.text.Component.join;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.JoinConfiguration.noSeparators;
import static net.kyori.adventure.text.format.NamedTextColor.*;

@RequiredArgsConstructor
public final class SidebarListener implements Listener {
    private final ResourcePlugin plugin;

    protected void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    protected void onPlayerHud(PlayerHudEvent event) {
        World world = event.getPlayer().getWorld();
        if (!plugin.worldNames.contains(world.getName())) return;
        List<Component> header = new ArrayList<>();
        final String displayName;
        switch (world.getEnvironment()) {
        case NETHER: displayName = "Nether"; break;
        case THE_END: displayName = "End"; break;
        default: displayName = "Overworld";
        }
        header.add(join(noSeparators(), text(tiny("mining world "), GRAY), text(displayName, AQUA)));
        if (plugin.doMineReset) {
            header.add(join(noSeparators(), text(tiny("mining reset "), GRAY), plugin.timeUntilResetFormat));
        }
        event.header(PlayerHudPriority.HIGH, header);
    }
}
