package com.winthier.resource;

import com.cavetale.core.command.AbstractCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

public final class AdminCommand extends AbstractCommand<ResourcePlugin> {
    protected AdminCommand(final ResourcePlugin plugin) {
        super(plugin, "mineadmin");
    }

    @Override
    protected void onEnable() {
        rootNode.addChild("info").denyTabCompletion()
            .description("Dump some biome info")
            .senderCaller(this::info);
        rootNode.addChild("reload").denyTabCompletion()
            .description("Reload configs and biomes")
            .senderCaller(this::reload);
    }

    boolean info(CommandSender sender, String[] args) {
        if (args.length != 0) return false;
        sender.sendMessage(Component.text("Places: " + plugin.places.size(), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Random Places: " + plugin.randomPlaces.size(), NamedTextColor.YELLOW));
        for (BiomeGroup biomeGroup : plugin.biomeGroups) {
            sender.sendMessage(Component.text(biomeGroup.name + ": " + biomeGroup.count,
                                              NamedTextColor.YELLOW));
        }
        sender.sendMessage(Component.text("Last Reset: " + plugin.lastReset, NamedTextColor.AQUA));
        sender.sendMessage(Component.text("Next Reset: " + plugin.nextReset, NamedTextColor.AQUA));
        return true;
    }

    boolean reload(CommandSender sender, String[] args) {
        plugin.loadAll();
        sender.sendMessage(Component.text("Configuration reloaded", NamedTextColor.YELLOW));
        return true;
    }
}
