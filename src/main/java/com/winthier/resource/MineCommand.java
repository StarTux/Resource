package com.winthier.resource;

import com.cavetale.core.command.RemotePlayer;
import com.cavetale.core.event.player.PluginPlayerEvent.Detail;
import com.cavetale.core.event.player.PluginPlayerEvent;
import com.winthier.connect.message.RemotePlayerCommandMessage;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

@RequiredArgsConstructor
public final class MineCommand implements TabExecutor {
    protected static final List<NamedTextColor> COLORS = List
        .of(NamedTextColor.GREEN, NamedTextColor.AQUA,
            NamedTextColor.RED, NamedTextColor.LIGHT_PURPLE,
            NamedTextColor.YELLOW, NamedTextColor.DARK_AQUA,
            NamedTextColor.GOLD, NamedTextColor.BLUE);
    private final ResourcePlugin plugin;

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!plugin.isMineServer && sender instanceof Player player) {
            new RemotePlayerCommandMessage(player, label + " " + String.join(" ", args)).send(plugin.mineServerName);
            return true;
        }
        String cmd = args.length > 0 ? args[0].toLowerCase() : null;
        if (args.length == 0) {
            listBiomes(sender);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("random")) {
            random(sender);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("end")) {
            sender.sendMessage(Component.text("You need to find an End portal in a Mining Overworld Stronghold to get to the Mining End!",
                                              NamedTextColor.GOLD));
            return true;
        }
        biome(sender, String.join(" ", args));
        return true;
    }

    protected void listBiomes(CommandSender sender) {
        List<ComponentLike> biomeList = new ArrayList<>();
        biomeList.add((Component.text().content("[Random]").color(NamedTextColor.GREEN))
                      .clickEvent(ClickEvent.runCommand("/mine random"))
                      .hoverEvent(HoverEvent.showText(Component.join(JoinConfiguration.separator(Component.newline()), new Component[] {
                                      Component.text("/mine random", NamedTextColor.GREEN),
                                      Component.text("Random biome", NamedTextColor.GRAY),
                                  }))));
        for (BiomeGroup biomeGroup : plugin.biomeGroups) {
            if (biomeGroup.count == 0) continue;
            NamedTextColor color = COLORS.get(plugin.random.nextInt(COLORS.size()));
            biomeList.add(Component.text().content("[" + biomeGroup.name + "]").color(color)
                          .clickEvent(ClickEvent.runCommand("/mine " + biomeGroup.name))
                          .hoverEvent(HoverEvent.showText(Component.join(JoinConfiguration.separator(Component.newline()), new Component[] {
                                          Component.text("/mine " + biomeGroup.name, color),
                                          Component.text("Warp to " + biomeGroup.name + " Biome", NamedTextColor.GRAY),
                                      }))));
        }
        biomeList.add((Component.text().content("[End]").color(NamedTextColor.AQUA))
                      .clickEvent(ClickEvent.runCommand("/mine end"))
                      .hoverEvent(HoverEvent.showText(Component.join(JoinConfiguration.separator(Component.newline()), new Component[] {
                                      Component.text("Directions to the", NamedTextColor.GREEN),
                                      Component.text("Mining End", NamedTextColor.AQUA),
                                  }))));
        sender.sendMessage(Component.join(JoinConfiguration.noSeparators(), new Component[] {
                    Component.empty(),
                    Component.newline(),
                    Component.text("        ", NamedTextColor.BLUE, TextDecoration.STRIKETHROUGH),
                    Component.text("[ ", NamedTextColor.BLUE),
                    Component.text("Mining Biomes", NamedTextColor.WHITE),
                    Component.text(" ]", NamedTextColor.BLUE),
                    Component.text("        ", NamedTextColor.BLUE, TextDecoration.STRIKETHROUGH),
                    Component.newline(),
                    Component.join(JoinConfiguration.separator(Component.space()), biomeList),
                    Component.newline(),
                    Component.empty(),
                }));
    }

    protected void random(CommandSender sender) {
        if (plugin.randomPlaces.isEmpty()) {
            sender.sendMessage(Component.text("No biomes found", NamedTextColor.RED));
            return;
        }
        if (!sender.hasPermission("resource.nocooldown")) {
            int cd = plugin.getCooldownInSeconds(sender);
            if (cd > 0) {
                sender.sendMessage(Component.text("You have to wait " + cd + " more seconds.", NamedTextColor.RED));
                return;
            }
        }
        Place place = plugin.randomPlaces.get(plugin.random.nextInt(plugin.randomPlaces.size()));
        sender.sendMessage(Component.text("Warping to random mining biome...", NamedTextColor.GREEN));
        if (sender instanceof Player player) {
            place(player, place, "Random");
        } else if (sender instanceof RemotePlayer player) {
            place(player, place, "Random");
        } else {
            sender.sendMessage("[resource:mine] player expected");
        }
    }

    protected void biome(CommandSender sender, String biomeName) {
        BiomeGroup biomeGroup = null;
        for (BiomeGroup bg : plugin.biomeGroups) {
            if (bg.name.equalsIgnoreCase(biomeName)) {
                biomeGroup = bg;
                break;
            }
        }
        if (biomeGroup == null || biomeGroup.count == 0) {
            sender.sendMessage(Component.text("Mining biome not found: " + biomeName, NamedTextColor.RED));
            return;
        }
        if (!sender.hasPermission("resource.nocooldown")) {
            int cd = plugin.getCooldownInSeconds(sender);
            if (cd > 0) {
                sender.sendMessage(Component.text("You have to wait " + cd + " more seconds.", NamedTextColor.RED));
                return;
            }
        }
        Place place = biomeGroup.places.get(plugin.random.nextInt(biomeGroup.places.size()));
        sender.sendMessage(Component.text("Warping to " + biomeGroup.name + " mining biome...", NamedTextColor.GREEN));
        if (sender instanceof Player player) {
            place(player, place, biomeName);
        } else if (sender instanceof RemotePlayer player) {
            place(player, place, biomeName);
        } else {
            sender.sendMessage("[resource:mine] player expected");
        }
    }

    protected void place(Player player, Place place, String biomeName) {
        plugin.findLocation(place, location -> {
                if (location == null) {
                    player.sendMessage(Component.text("Something went wrong. Please try again", NamedTextColor.RED));
                    return;
                }
                Location pl = player.getLocation();
                location.setYaw(pl.getYaw());
                location.setPitch(pl.getPitch());
                plugin.setCooldownInSeconds(player, plugin.playerCooldown);
                player.teleport(location, TeleportCause.COMMAND);
                String log = String.format("[%s] Warp %s to %s %d %d %d",
                                           place.biome.name(), player.getName(), location.getWorld().getName(),
                                           location.getBlockX(), location.getBlockY(), location.getBlockZ());
                plugin.getLogger().info(log);
                PluginPlayerEvent.Name.USE_MINE.make(plugin, player)
                    .detail(Detail.NAME, biomeName.toLowerCase())
                    .callEvent();
            });
    }

    protected void place(RemotePlayer remote, Place place, String biomeName) {
        plugin.findLocation(place, location -> {
                if (location == null) {
                    remote.sendMessage(Component.text("Something went wrong. Please try again", NamedTextColor.RED));
                    return;
                }
                boolean ticket = location.getChunk().addPluginChunkTicket(plugin);
                remote.bring(plugin, location, player -> {
                        if (ticket) location.getChunk().removePluginChunkTicket(plugin);
                        if (player == null) {
                            remote.sendMessage(Component.text("You timed out!", NamedTextColor.RED));
                            return;
                        }
                        plugin.setCooldownInSeconds(player, plugin.playerCooldown);
                        String log = String.format("[%s] Set Spawn Location %s to %s %d %d %d",
                                                   place.biome.name(), player.getName(), location.getWorld().getName(),
                                                   location.getBlockX(), location.getBlockY(), location.getBlockZ());
                        plugin.getLogger().info(log);
                        PluginPlayerEvent.Name.USE_MINE.make(plugin, player)
                            .detail(Detail.NAME, biomeName.toLowerCase())
                            .callEvent();
                    });
            });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String arg = args[0].toLowerCase();
        List<String> result = new ArrayList<>();
        if ("random".contains(arg)) result.add("Random");
        for (BiomeGroup biomeGroup : plugin.biomeGroups) {
            if (!biomeGroup.name.toLowerCase().startsWith(arg)) continue;
            if (biomeGroup.count == 0) continue;
            result.add(biomeGroup.name);
        }
        return result;
    }
}
