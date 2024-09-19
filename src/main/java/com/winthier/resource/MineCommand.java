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
import java.util.function.Consumer;

import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.TextColor;
import static net.kyori.adventure.text.Component.empty;
import static net.kyori.adventure.text.Component.join;
import static net.kyori.adventure.text.Component.newline;
import static net.kyori.adventure.text.Component.space;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.textOfChildren;
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

    private boolean mine(RemotePlayer player, String[] args) {
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
        if (command.equalsIgnoreCase("end")) {
            player.sendMessage(text("There is no warp for the Mine End."
                            + " You will always need to find an End portal in a"
                            + " Mine world stronghold to get to the Mine End.",
                    GOLD));
            return true;
        }
        if (command.equalsIgnoreCase("random")) { // Warp to random biome
            if (plugin.randomPlaces.isEmpty()) {
                throw new CommandWarn("No biomes found");
            }
            if (!player.hasPermission("resource.nocooldown")) {
                int cd = plugin.getCooldownInSeconds(player.getUniqueId());
                if (cd > 0) {
                    throw new CommandWarn("You have to wait " + cd + " more seconds");
                }
            }
            player.sendMessage(text("Warping to random biome...", GREEN));
            this.sendRandom(player, command, 3);
            return true;
        } // Warp to specified biome
        BiomeGroup biomeGroup = null;
        for (BiomeGroup bg : plugin.biomeGroups) {
            if (bg.name.equalsIgnoreCase(command)) {
                biomeGroup = bg;
                break;
            }
        }
        if (biomeGroup == null || biomeGroup.count == 0) {
            throw new CommandWarn("Mining biome not found: " + command);
        }
        if (!player.hasPermission("resource.nocooldown")) {
            int cd = plugin.getCooldownInSeconds(player.getUniqueId());
            if (cd > 0) {
                throw new CommandWarn("You have to wait " + cd + " more seconds.");
            }
        }
        player.sendMessage(text("Warping to " + biomeGroup.getName() + "...", GREEN));
        this.sendBiome(player, biomeGroup, 3); // Try warping 3 times
        return true;
    }

    private void sendRandom(RemotePlayer player, String command, int tries) {
        if (tries <= 0) {
            player.sendMessage(text("Warp failed. Please try again.", RED));
            return;
        }
        Place place = plugin.randomPlaces.get(plugin.random.nextInt(plugin.randomPlaces.size()));
        this.sendPlace(player, place, command, success -> {
            if (!success) this.sendRandom(player, command, tries - 1);
        });
    }

    private void sendBiome(RemotePlayer player, BiomeGroup biomes, int tries) {
        if (tries <= 0) {
            player.sendMessage(text("Warp failed. Please try again.", RED));
            return;
        }
        Place place = biomes.places.get(plugin.random.nextInt(biomes.places.size()));
        this.sendPlace(player, place, biomes.getName(), success -> {
            if (!success) this.sendBiome(player, biomes, tries - 1);
        });
    }

    private void sendPlace(RemotePlayer player, Place place, String biomeName, Consumer<Boolean> success) {
        plugin.findLocation(place, location -> {
            if (location == null) { // Warp failed, try again
                success.accept(false);
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
            success.accept(true);
        });
    }

    private void listBiomes(RemotePlayer player) {
        List<ComponentLike> biomeList = new ArrayList<>();
        biomeList.add((text("[Random]", GREEN))
                .clickEvent(runCommand("/mine random"))
                .hoverEvent(showText(join(separator(newline()),
                        List.of(text("/mine random", GREEN),
                                text("Random biome", GRAY))))));
        for (BiomeGroup biomeGroup : plugin.biomeGroups) {
            if (biomeGroup.count == 0) continue;
            TextColor color = COLORS.get(plugin.random.nextInt(COLORS.size()));
            biomeList.add(text("[" + biomeGroup.name + "]", color)
                    .clickEvent(runCommand("/mine " + biomeGroup.name))
                    .hoverEvent(showText(join(separator(newline()),
                            List.of(text("/mine " + biomeGroup.name, color),
                                    text("Warp to " + biomeGroup.name + " Biome", GRAY))))));
        }
        biomeList.add((text("[End]", AQUA))
                .clickEvent(runCommand("/mine end"))
                .hoverEvent(showText(join(separator(newline()),
                        List.of(text("Directions to the", GREEN),
                                text("Mining End", AQUA))))));
        player.sendMessage(join(separator(newline()),
                List.of(empty(),
                        textOfChildren(text("        ", BLUE, STRIKETHROUGH),
                                text("[ ", BLUE),
                                text("Mining Biomes", WHITE),
                                text(" ]", BLUE),
                                text("        ", BLUE, STRIKETHROUGH)),
                        join(separator(space()), biomeList),
                        empty())));
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
