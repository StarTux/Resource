package com.winthier.resource;

import com.cavetale.core.event.player.PluginPlayerEvent.Detail;
import com.cavetale.core.event.player.PluginPlayerEvent;
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
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

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
        if (!(sender instanceof Player)) {
            sender.sendMessage("[resource:mine] player expected");
            return true;
        }
        Player player = (Player) sender;
        String cmd = args.length > 0 ? args[0].toLowerCase() : null;
        if (args.length == 0) {
            listBiomes(player);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("random")) {
            random(player);
            return true;
        }
        biome(player, String.join(" ", args));
        return true;
    }

    protected void listBiomes(Player player) {
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
        player.sendMessage(Component.join(JoinConfiguration.noSeparators(), new Component[] {
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

    protected void random(Player player) {
        if (plugin.randomPlaces.isEmpty()) {
            player.sendMessage(Component.text("No biomes found", NamedTextColor.RED));
            return;
        }
        if (!player.hasPermission("resource.nocooldown")) {
            int cd = plugin.getCooldownInSeconds(player);
            if (cd > 0) {
                player.sendMessage(Component.text("You have to wait " + cd + " more seconds.", NamedTextColor.RED));
                return;
            }
        }
        Place place = plugin.randomPlaces.get(plugin.random.nextInt(plugin.randomPlaces.size()));
        player.sendMessage(Component.text("Warping to random mining biome...", NamedTextColor.GREEN));
        plugin.teleport(player, place, () -> {
                plugin.setCooldownInSeconds(player, plugin.playerCooldown);
                PluginPlayerEvent.Name.USE_MINE.ultimate(plugin, player)
                    .detail(Detail.NAME, "random")
                    .call();
            }, () -> {
                player.sendMessage(Component.text("Something went wrong. Please try again", NamedTextColor.RED));
            });
    }

    protected void biome(Player player, String biomeName) {
        BiomeGroup biomeGroup = null;
        for (BiomeGroup bg : plugin.biomeGroups) {
            if (bg.name.equalsIgnoreCase(biomeName)) {
                biomeGroup = bg;
                break;
            }
        }
        if (biomeGroup == null || biomeGroup.count == 0) {
            player.sendMessage(Component.text("Mining biome not found: " + biomeName, NamedTextColor.RED));
            return;
        }
        if (!player.hasPermission("resource.nocooldown")) {
            int cd = plugin.getCooldownInSeconds(player);
            if (cd > 0) {
                player.sendMessage(Component.text("You have to wait " + cd + " more seconds.", NamedTextColor.RED));
                return;
            }
        }
        Place place = biomeGroup.places.get(plugin.random.nextInt(biomeGroup.places.size()));
        player.sendMessage(Component.text("Warping to " + biomeGroup.name + " mining biome...", NamedTextColor.GREEN));
        plugin.teleport(player, place, () -> {
                plugin.setCooldownInSeconds(player, plugin.playerCooldown);
                PluginPlayerEvent.Name.USE_MINE.ultimate(plugin, player)
                    .detail(Detail.NAME, biomeName.toLowerCase())
                    .call();
            }, () -> {
                player.sendMessage(Component.text("Something went wrong. Please try again", NamedTextColor.RED));
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
