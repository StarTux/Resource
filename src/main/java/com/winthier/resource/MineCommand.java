package com.winthier.resource;

import com.cavetale.core.command.AbstractCommand;
import com.cavetale.core.command.CommandArgCompleter;
import com.cavetale.core.command.CommandWarn;
import com.cavetale.core.command.RemotePlayer;
import com.cavetale.core.connect.Connect;
import com.cavetale.core.event.player.PluginPlayerEvent;
import com.cavetale.core.event.player.PluginPlayerEvent.Detail;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.TextColor;
import static net.kyori.adventure.text.Component.empty;
import static net.kyori.adventure.text.Component.join;
import static net.kyori.adventure.text.Component.newline;
import static net.kyori.adventure.text.Component.space;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.JoinConfiguration.noSeparators;
import static net.kyori.adventure.text.JoinConfiguration.separator;
import static net.kyori.adventure.text.event.ClickEvent.runCommand;
import static net.kyori.adventure.text.event.HoverEvent.showText;
import static net.kyori.adventure.text.format.NamedTextColor.*;
import static net.kyori.adventure.text.format.TextDecoration.*;

public final class MineCommand extends AbstractCommand<ResourcePlugin> {
    protected static final List<TextColor> COLORS = List.of(GREEN, AQUA, RED, LIGHT_PURPLE, YELLOW, DARK_AQUA, GOLD, BLUE);

    protected MineCommand(final ResourcePlugin plugin) {
        super(plugin, "resource");
    }

    @Override
    protected void onEnable() {
        rootNode.arguments("[biome]").description("Warp to a mining biome")
            .completers(CommandArgCompleter.supplyIgnoreCaseList(this::listBiomeNames))
            .remotePlayerCaller(this::mine);
    }

    protected boolean mine(RemotePlayer player, String[] args) {
        if (!plugin.isMineServer && player.isPlayer()) {
            Connect.get().dispatchRemoteCommand(player.getPlayer(), "mine " + String.join(" ", args), plugin.mineServerName);
            return true;
        }
        final String command = args.length > 0
            ? String.join(" ", args)
            : null;
        if (command == null) {
            listBiomes(player);
            return true;
        }
        if (command.equalsIgnoreCase("random")) {
            random(player, command);
            return true;
        }
        if (command.equalsIgnoreCase("end")) {
            player.sendMessage(text("There is no warp for the Mine End."
                                    + " You will always need to find an End portal in a"
                                    + " Mine world stronghold to get to the Mine End.",
                                    GOLD));
            return true;
        }
        biome(player, command);
        return true;
    }

    protected void listBiomes(RemotePlayer player) {
        List<ComponentLike> biomeList = new ArrayList<>();
        biomeList.add((text().content("[Random]").color(GREEN))
                      .clickEvent(runCommand("/mine random"))
                      .hoverEvent(showText(join(separator(newline()), new Component[] {
                                      text("/mine random", GREEN),
                                      text("Random biome", GRAY),
                                  }))));
        for (BiomeGroup biomeGroup : plugin.biomeGroups) {
            if (biomeGroup.count == 0) continue;
            TextColor color = COLORS.get(plugin.random.nextInt(COLORS.size()));
            biomeList.add(text().content("[" + biomeGroup.name + "]").color(color)
                          .clickEvent(runCommand("/mine " + biomeGroup.name))
                          .hoverEvent(showText(join(separator(newline()), new Component[] {
                                          text("/mine " + biomeGroup.name, color),
                                          text("Warp to " + biomeGroup.name + " Biome", GRAY),
                                      }))));
        }
        biomeList.add((text().content("[End]").color(AQUA))
                      .clickEvent(runCommand("/mine end"))
                      .hoverEvent(showText(join(separator(newline()), new Component[] {
                                      text("Directions to the", GREEN),
                                      text("Mining End", AQUA),
                                  }))));
        player.sendMessage(join(noSeparators(), new Component[] {
                    empty(),
                    newline(),
                    text("        ", BLUE, STRIKETHROUGH),
                    text("[ ", BLUE),
                    text("Mining Biomes", WHITE),
                    text(" ]", BLUE),
                    text("        ", BLUE, STRIKETHROUGH),
                    newline(),
                    join(separator(space()), biomeList),
                    newline(),
                    empty(),
                }));
    }

    protected void random(RemotePlayer player, String biomeName) {
        if (plugin.randomPlaces.isEmpty()) {
            throw new CommandWarn("No biomes found");
        }
        if (!player.hasPermission("resource.nocooldown")) {
            int cd = plugin.getCooldownInSeconds(player.getUniqueId());
            if (cd > 0) {
                throw new CommandWarn("You have to wait " + cd + " more seconds");
            }
        }
        Place place = plugin.randomPlaces.get(plugin.random.nextInt(plugin.randomPlaces.size()));
        player.sendMessage(text("Warping to random mining biome...", GREEN));
        place(player, place, biomeName);
    }

    protected void biome(RemotePlayer player, String biomeName) {
        BiomeGroup biomeGroup = null;
        for (BiomeGroup bg : plugin.biomeGroups) {
            if (bg.name.equalsIgnoreCase(biomeName)) {
                biomeGroup = bg;
                break;
            }
        }
        if (biomeGroup == null || biomeGroup.count == 0) {
            throw new CommandWarn("Mining biome not found: " + biomeName);
        }
        if (!player.hasPermission("resource.nocooldown")) {
            int cd = plugin.getCooldownInSeconds(player.getUniqueId());
            if (cd > 0) {
                throw new CommandWarn("You have to wait " + cd + " more seconds.");
            }
        }
        Place place = biomeGroup.places.get(plugin.random.nextInt(biomeGroup.places.size()));
        player.sendMessage(text("Warping to " + biomeGroup.name + " mining biome...", GREEN));
        place(player, place, biomeName);
    }

    protected void place(RemotePlayer player, Place place, String biomeName) {
        plugin.findLocation(place, location -> {
                if (location == null) {
                    player.sendMessage(text("Something went wrong. Please try again", RED));
                    return;
                }
                plugin.setCooldownInSeconds(player.getUniqueId(), plugin.playerCooldown);
                String log = String.format("[%s] Warp %s to %s %d %d %d",
                                           place.biome.name(), player.getName(), location.getWorld().getName(),
                                           location.getBlockX(), location.getBlockY(), location.getBlockZ());
                plugin.getLogger().info(log);
                player.bring(plugin, location, player2 -> {
                        if (player2 == null) return;
                        PluginPlayerEvent.Name.USE_MINE.make(plugin, player2)
                            .detail(Detail.NAME, biomeName.toLowerCase())
                            .callEvent();
                    });
            });
    }

    private List<String> listBiomeNames() {
        List<String> result = new ArrayList<>();
        result.add("random");
        for (BiomeGroup biomeGroup : plugin.biomeGroups) {
            result.add(biomeGroup.name);
        }
        result.add("end");
        return result;
    }
}
