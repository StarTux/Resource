package com.winthier.resource;

import com.cavetale.core.event.player.PluginPlayerEvent.Detail;
import com.cavetale.core.event.player.PluginPlayerEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;

public final class ResourcePlugin extends JavaPlugin {
    protected static final String PERM_ADMIN = "resource.admin";
    private final Random random = new Random(System.currentTimeMillis());
    // Configuration
    private int playerCooldown = 5;
    private final List<String> worldNames = new ArrayList<>();
    private final List<BiomeGroup> biomeGroups = new ArrayList<>();
    private final EnumMap<Biome, Integer> locatedBiomes = new EnumMap<>(Biome.class);
    // State
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final List<Place> places = new ArrayList<>();
    private final List<Place> randomPlaces = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadAll();
    }

    @Override
    public void onDisable() {
        cooldowns.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = sender instanceof Player ? (Player) sender : null;
        String cmd = args.length > 0 ? args[0].toLowerCase() : null;
        if (cmd == null) {
            List<NamedTextColor> colors = Arrays
                .asList(NamedTextColor.GREEN, NamedTextColor.AQUA, NamedTextColor.RED, NamedTextColor.LIGHT_PURPLE,
                        NamedTextColor.YELLOW, NamedTextColor.DARK_AQUA, NamedTextColor.GOLD, NamedTextColor.BLUE);
            if (player == null) {
                sender.sendMessage("[resource:mine] player expected");
                return true;
            }
            List<Component> biomeList = new ArrayList<>();
            biomeList.add((Component.text().content("[Random]").color(NamedTextColor.GREEN))
                          .clickEvent(ClickEvent.runCommand("/mine random"))
                          .hoverEvent(Component.text()
                                      .append(Component.text("/mine random", NamedTextColor.GREEN))
                                      .append(Component.newline())
                                      .append(Component.text("Random biome", NamedTextColor.GRAY))
                                      .build())
                          .build());
            for (BiomeGroup biomeGroup : biomeGroups) {
                if (biomeGroup.count == 0) continue;
                String lowname = biomeGroup.name.toLowerCase();
                NamedTextColor color = colors.get(random.nextInt(colors.size()));
                biomeList.add(Component.text().content("[" + biomeGroup.name + "]").color(color)
                              .clickEvent(ClickEvent.runCommand("/mine " + lowname))
                              .hoverEvent(Component.text()
                                          .append(Component.text("/mine " + lowname, color))
                                          .append(Component.newline())
                                          .append(Component.text(biomeGroup.name, NamedTextColor.WHITE))
                                          .build())
                              .build());
            }
            player.sendMessage(Component.text()
                               .append(Component.empty())
                               .append(Component.newline())
                               .append(Component.text("        ", NamedTextColor.BLUE, TextDecoration.STRIKETHROUGH))
                               .append(Component.text("[ ", NamedTextColor.BLUE))
                               .append(Component.text("Mining Biomes", NamedTextColor.WHITE))
                               .append(Component.text(" ]", NamedTextColor.BLUE))
                               .append(Component.text("        ", NamedTextColor.BLUE, TextDecoration.STRIKETHROUGH))
                               .append(Component.newline())
                               .append(Component.join(JoinConfiguration.separator(Component.space()), biomeList))
                               .append(Component.newline())
                               .append(Component.empty()));
            return true;
        } else if (cmd.equals("random") && args.length == 1) {
            if (player == null) {
                sender.sendMessage("[resource:random] Player expected");
                return true;
            }
            if (randomPlaces.isEmpty()) {
                player.sendMessage(Component.text("No biomes found", NamedTextColor.RED));
                return true;
            }
            if (!player.hasPermission("resource.nocooldown")) {
                int cd = getCooldownInSeconds(player);
                if (cd > 0) {
                    player.sendMessage(Component.text("You have to wait " + cd + " more seconds.", NamedTextColor.RED));
                    return true;
                }
            }
            Place place = randomPlaces.get(random.nextInt(randomPlaces.size()));
            player.sendMessage(Component.text("Warping to random mining biome...", NamedTextColor.GREEN));
            teleport(player, place, () -> {
                    setCooldownInSeconds(player, playerCooldown);
                    PluginPlayerEvent.Name.USE_MINE.ultimate(this, player)
                        .detail(Detail.NAME, "random")
                        .call();
                }, () -> {
                    player.sendMessage(Component.text("Something went wrong. Please try again", NamedTextColor.RED));
                });
        } else if (args.length == 1 && cmd.equals("info")) {
            if (!sender.hasPermission(PERM_ADMIN)) return false;
            sender.sendMessage(Component.text("Places: " + places.size(), NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("Random Places: " + randomPlaces.size(), NamedTextColor.YELLOW));
            for (BiomeGroup biomeGroup: biomeGroups) {
                sender.sendMessage(Component.text(biomeGroup.name + ": " + biomeGroup.count,
                                                  NamedTextColor.YELLOW));
            }
        } else if (args.length == 1 && cmd.equals("reload")) {
            if (!sender.hasPermission(PERM_ADMIN)) return false;
            loadAll();
            sender.sendMessage(Component.text("Configuration reloaded", NamedTextColor.YELLOW));
        } else {
            if (player == null) {
                sender.sendMessage("[resource:mine] player expected");
                return true;
            }
            if (!player.hasPermission("resource.nocooldown")) {
                int cd = getCooldownInSeconds(player);
                if (cd > 0) {
                    player.sendMessage(Component.text("You have to wait " + cd + " more seconds.", NamedTextColor.RED));
                    return true;
                }
            }
            final String name = String.join(" ", args);
            BiomeGroup biomeGroup = null;
            for (BiomeGroup bg : biomeGroups) {
                if (bg.name.equalsIgnoreCase(name)) {
                    biomeGroup = bg;
                    break;
                }
            }
            if (biomeGroup == null || biomeGroup.count == 0) {
                player.sendMessage(Component.text("Mining biome not found: " + name, NamedTextColor.RED));
                return true;
            }
            Place place = biomeGroup.places.get(random.nextInt(biomeGroup.places.size()));
            player.sendMessage(Component.text("Warping to " + biomeGroup.name + " mining biome...", NamedTextColor.GREEN));
            teleport(player, place, () -> {
                    setCooldownInSeconds(player, playerCooldown);
                    PluginPlayerEvent.Name.USE_MINE.ultimate(this, player)
                        .detail(Detail.NAME, name.toLowerCase())
                        .call();
                }, () -> {
                    player.sendMessage(Component.text("Something went wrong. Please try again", NamedTextColor.RED));
                });
        }
        return true;
    }

    protected void teleport(Player player, Place place, Runnable callback, Runnable failCallback) {
        Location pl = player.getLocation();
        World bworld = getServer().getWorld(place.world);
        if (bworld == null) {
            if (failCallback != null) {
                failCallback.run();
            }
            return;
        }
        bworld.getChunkAtAsync(place.x, place.z, (Consumer<Chunk>) chunk -> {
                if (!player.isOnline()) return;
                Location target;
                if (bworld.getEnvironment() == World.Environment.NETHER) {
                    int score;
                    target = null;
                    BLOCKS:
                    for (int z = 0; z < 16; z += 1) {
                        for (int x = 0; x < 16; x += 1) {
                            score = 0;
                            for (int y = 112; y > 16; y -= 1) {
                                Block block = chunk.getBlock(x, y, z);
                                switch (score) {
                                case 0: case 1: {
                                    if (block.isEmpty()) {
                                        score += 1;
                                    } else {
                                        score = 0;
                                    }
                                    break;
                                }
                                case 2: default: {
                                    if (block.isEmpty()) {
                                        continue;
                                    } else if (block.getType().isSolid()) {
                                        score += 1;
                                    } else {
                                        score = 0;
                                    }
                                    break;
                                }
                                }
                                if (score == 3) {
                                    target = block.getLocation().add(0.5, 1.0, 0.5);
                                    break BLOCKS;
                                }
                            }
                        }
                    }
                } else {
                    int ax = place.x << 4;
                    int az = place.z << 4;
                    List<Block> possibleBlocks = new ArrayList<>(16 * 16);
                    for (int z = 0; z < 16; z += 1) {
                        for (int x = 0; x < 16; x += 1) {
                            Block block = bworld.getHighestBlockAt(ax + x, az + z);
                            if ((block.isSolid() || block.isLiquid())
                                && block.getRelative(0, 1, 0).isPassable()
                                && block.getRelative(0, 2, 0).isPassable()) {
                                possibleBlocks.add(block);
                            }
                        }
                    }
                    target = !possibleBlocks.isEmpty()
                        ? possibleBlocks.get(random.nextInt(possibleBlocks.size())).getLocation().add(0.5, 1.0, 0.5)
                        : null;
                }
                if (target == null) {
                    if (failCallback != null) {
                        failCallback.run();
                    }
                    return;
                }
                target.setYaw(pl.getYaw());
                target.setPitch(pl.getPitch());
                player.teleport(target, TeleportCause.COMMAND);
                String log = String.format("[%s] Warp %s to %s %d %d %d",
                                           place.biome.name(), player.getName(), target.getWorld().getName(),
                                           target.getBlockX(), target.getBlockY(), target.getBlockZ());
                getLogger().info(log);
                if (callback != null) callback.run();
            });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String arg = args.length == 0 ? "" : args[args.length - 1].toLowerCase();
        if (args.length == 0) return null;
        if (args.length == 1) {
            List<String> result = new ArrayList<>();
            for (BiomeGroup biomeGroup: biomeGroups) {
                if (!biomeGroup.name.toLowerCase().startsWith(arg)) continue;
                if (biomeGroup.count > 0) result.add(biomeGroup.name);
            }
            return result;
        }
        return null;
    }

    protected void loadAll() {
        reloadConfig();
        worldNames.clear();
        worldNames.addAll(getConfig().getStringList("Worlds"));
        playerCooldown = getConfig().getInt("PlayerCooldown");
        biomeGroups.clear();
        for (Map<?, ?> map: getConfig().getMapList("Biomes")) {
            ConfigurationSection section = getConfig().createSection("tmp", map);
            List<Biome> biomes = new ArrayList<>();
            for (String name: section.getStringList("Biomes")) {
                try {
                    biomes.add(Biome.valueOf(name.toUpperCase()));
                } catch (IllegalArgumentException iae) {
                    getLogger().warning("Unknown biome '" + name + "'. Ignoring");
                    iae.printStackTrace();
                }
            }
            biomeGroups.add(new BiomeGroup(section.getString("Name"), biomes));
        }
        places.clear();
        randomPlaces.clear();
        for (String worldName : worldNames) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                throw new IllegalStateException("World not found: " + worldName);
            }
            File biomesFile = new File(world.getWorldFolder(), "biomes.txt");
            if (!biomesFile.exists()) {
                throw new IllegalStateException("Biomes file not found: " + biomesFile);
            }
            int worldTotalPlaces = 0;
            try (BufferedReader reader = new BufferedReader(new FileReader(biomesFile))) {
                while (true) {
                    String line = reader.readLine();
                    if (line == null) break;
                    String[] toks = line.split(",");
                    if (toks.length < 3) continue;
                    int chunkX;
                    int chunkZ;
                    Biome chunkBiome = null;
                    int max = 0;
                    try {
                        chunkX = Integer.parseInt(toks[0]);
                        chunkZ = Integer.parseInt(toks[1]);
                        for (int i = 2; i < toks.length; i += 1) {
                            String tok = toks[i];
                            String[] toks2 = tok.split(":");
                            if (toks2.length > 2) throw new IllegalArgumentException(tok);
                            Biome biome = Biome.valueOf(toks2[0]);
                            int count = toks2.length >= 2
                                ? Integer.parseInt(toks2[1])
                                : 1; // legacy
                            if (count > max) {
                                max = count;
                                chunkBiome = biome;
                            }
                        }
                        if (chunkBiome == null) {
                            throw new IllegalStateException("biome=null " + line);
                        }
                        Place place = new Place(worldName, chunkX, chunkZ, chunkBiome);
                        places.add(place);
                        int placeBiomeGroups = 0;
                        for (BiomeGroup biomeGroup : biomeGroups) {
                            if (biomeGroup.biomes.contains(chunkBiome)) {
                                biomeGroup.places.add(place);
                                biomeGroup.count += 1;
                                placeBiomeGroups += 1;
                            }
                        }
                        if (world.getEnvironment() != World.Environment.NETHER && !chunkBiome.name().contains("OCEAN")) {
                            randomPlaces.add(place);
                        }
                        if (placeBiomeGroups > 0) worldTotalPlaces += 1;
                    } catch (IllegalArgumentException iae) {
                        getLogger().log(Level.SEVERE, "Invalid line: " + line, iae);
                        break;
                    }
                }
            } catch (IOException ioe) {
                throw new UncheckedIOException(ioe);
            }
            getLogger().info(worldName + ": Total Places: " + worldTotalPlaces);
        }
        cooldowns.clear();
    }

    protected void setCooldownInSeconds(Player player, int sec) {
        long time = System.currentTimeMillis() + (long) sec * 1000;
        cooldowns.put(player.getUniqueId(), time);
    }

    protected int getCooldownInSeconds(Player player) {
        Long time = cooldowns.get(player.getUniqueId());
        if (time == null) return 0;
        long result = time - System.currentTimeMillis();
        if (result < 0) return 0;
        return (int) (result / 1000);
    }
}
