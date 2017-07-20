package com.winthier.resource;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class ResourcePlugin extends JavaPlugin {
    final static String PERM_ADMIN = "resource.admin";
    final Random random = new Random(System.currentTimeMillis());
    // Configuration
    int crawlerInterval = 20;
    int playerCooldown = 5;
    int biomeDistance = 256;
    final List<String> worldNames = new ArrayList<>();
    final List<BiomeGroup> biomeGroups = new ArrayList<>();
    final List<Place> knownPlaces = new ArrayList<>();
    final List<Place> unknownPlaces = new ArrayList<>();
    final EnumMap<Biome, Integer> locatedBiomes = new EnumMap<>(Biome.class);
    // State
    final Map<UUID, Long> cooldowns = new HashMap<>();
    int ticks;
    boolean dirty;
    long lastSave;

    @AllArgsConstructor
    final class Place {
        final String world;
        final int x, z;
        Biome biome;

        Place(String world, int x, int z) {
            this(world, x, z, (Biome)null);
        }

        Place(ConfigurationSection config) {
            this.world = config.getString("world");
            this.x = config.getInt("x");
            this.z = config.getInt("z");
            if (config.isSet("biome")) {
                this.biome = Biome.valueOf(config.getString("biome").toUpperCase());
            }
        }

        Map<String, Object> serialize() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("world", world);
            result.put("x", x);
            result.put("z", z);
            if (biome != null) {
                result.put("biome", biome.name());
            }
            return result;
        }

        Block getBlock() {
            World bworld = getServer().getWorld(world);
            if (bworld == null) return null;
            return bworld.getHighestBlockAt(x, z);
        }

        Location getLocation() {
            World bworld = getServer().getWorld(world);
            if (bworld == null) return null;
            return bworld.getHighestBlockAt(x, z).getLocation().add(0.5, 0.0, 0.5);
        }
    }

    final class BiomeGroup {
        final String name;
        final Set<Biome> biomes = EnumSet.noneOf(Biome.class);

        BiomeGroup(String name, Collection<Biome> biomes) {
            this.name = name;
            this.biomes.addAll(biomes);
        }
    }

    @Override
    public void onEnable() {
        resetLocatedBiomes();
        saveDefaultConfig();
        loadAll();
        getServer().getScheduler().runTaskTimer(this, () -> onTick(), 1, 1);
    }

    @Override
    public void onDisable() {
        cooldowns.clear();
        if (dirty) saveAll();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String args[]) {
        Player player = sender instanceof Player ? (Player)sender : null;
        String cmd = args.length > 0 ? args[0].toLowerCase() : null;
        if (cmd == null) {
            List<ChatColor> colors = Arrays.asList(ChatColor.GREEN, ChatColor.AQUA, ChatColor.RED, ChatColor.LIGHT_PURPLE, ChatColor.YELLOW, ChatColor.DARK_AQUA, ChatColor.GOLD, ChatColor.BLUE);
            if (player == null) {
                Msg.warn(sender, "Player expected");
                return true;
            }
            List<Object> message = new ArrayList<>();
            message.add(" ");
            message.add(Msg.button(ChatColor.GREEN,
                                   "[Random]", null,
                                   "&a/resource random\n&r&oRandom biome",
                                   "/resource random"));
            for (BiomeGroup biomeGroup : biomeGroups) {
                int total = 0;
                for (Biome biome: biomeGroup.biomes) {
                    total += locatedBiomes.get(biome);
                }
                if (total < 0) continue;
                message.add(" ");
                String lowname = biomeGroup.name.toLowerCase();
                ChatColor color = colors.get(random.nextInt(colors.size()));
                message.add(Msg.button(color,
                                       "[" + biomeGroup.name + "]", null,
                                       "&a/resource " + lowname + "\n&r&o" + biomeGroup.name,
                                       "/resource " + lowname));
            }
            Msg.send(player, "&3&lResource Biomes ");
            Msg.raw(player, message);
            player.sendMessage("");
            return true;
        } else if (cmd.equals("random") && args.length == 1) {
            if (player == null) {
                Msg.warn(sender, "Player expected");
                return true;
            }
            int cd = getCooldownInSeconds(player);
            if (cd > 0) {
                Msg.info(player, "You have to wait %d more seconds.", cd);
                return true;
            }
            Collections.shuffle(knownPlaces, random);
            Place place = null;
            for (Place p: knownPlaces) {
                if (p.biome != Biome.HELL) {
                    place = p;
                    break;
                }
            }
            if (place == null) {
                Msg.warn(player, "No biomes found.");
                return true;
            }
            Location location = place.getLocation();
            Location pl = player.getLocation();
            location.setYaw(pl.getYaw());
            location.setPitch(pl.getPitch());
            getLogger().info(String.format("Teleporting %s to %s %d,%d,%d", player.getName(), location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ()));
            Msg.info(sender, "&aWarping to random location in the resource world");
            Msg.sendActionBar(player, "&aWarping to random location in the resource world");
            player.teleport(location);
            setCooldownInSeconds(player, playerCooldown);
        } else if (args.length == 1 && cmd.equals("crawl")) {
            if (!sender.hasPermission(PERM_ADMIN)) return false;
            crawl();
            Msg.send(sender, "&eCompleted one crawler iteration");
        } else if (args.length == 1 && cmd.equals("info")) {
            if (!sender.hasPermission(PERM_ADMIN)) return false;
            Msg.info(sender, "%d known and %d unknown places", knownPlaces.size(), unknownPlaces.size());
            for (BiomeGroup biomeGroup: biomeGroups) {
                int total = 0;
                for (Biome biome: biomeGroup.biomes) {
                    total += locatedBiomes.get(biome);
                }
                Msg.send(sender, biomeGroup.name + ": " + total);
            }
        } else if (args.length == 1 && cmd.equals("reload")) {
            if (!sender.hasPermission(PERM_ADMIN)) return false;
            loadAll();
            Msg.send(sender, "&eConfiguration reloaded");
        } else if (args.length == 1 && cmd.equals("save")) {
            if (!sender.hasPermission(PERM_ADMIN)) return false;
            saveAll();
            Msg.send(sender, "&eConfiguration saved");
        } else if (args.length == 1 && cmd.equals("setup")) {
            if (!sender.hasPermission(PERM_ADMIN)) return false;
            setup();
            Msg.send(sender, "&eSetup done. %d known and %d unknown places with a distance of %d blocks.", knownPlaces.size(), unknownPlaces.size(), biomeDistance);
        } else {
            if (player == null) {
                Msg.warn(sender, "Player expected");
                return true;
            }
            int cd = getCooldownInSeconds(player);
            if (cd > 0) {
                Msg.warn(player, "You have to wait %d more seconds.", cd);
                return true;
            }
            StringBuilder sb = new StringBuilder(args[0]);
            for (int j = 1; j < args.length; ++j) {
                sb.append(" ").append(args[j]);
            }
            String name = sb.toString();
            BiomeGroup biomeGroup = null;
            for (BiomeGroup bg: biomeGroups) {
                if (bg.name.equalsIgnoreCase(name)) {
                    biomeGroup = bg;
                    break;
                }
            }
            if (biomeGroup == null) {
                Msg.warn(player, "Resource biome not found: %s", name);
                return true;
            }
            Collections.shuffle(knownPlaces, random);
            Place place = null;
            for (Place p: knownPlaces) {
                if (biomeGroup.biomes.contains(p.biome)) {
                    place = p;
                    break;
                }
            }
            if (place == null) {
                Msg.warn(sender, "No known location for %s.", biomeGroup.name);
                return true;
            }
            Location location = place.getLocation();
            Location pl = player.getLocation();
            location.setYaw(pl.getYaw());
            location.setPitch(pl.getPitch());
            getLogger().info(String.format("[%s] Warp %s to %s %d,%d,%d", biomeGroup.name, player.getName(), location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ()));
            Msg.send(sender, "&aWarping to %s biome in the resource world.", biomeGroup.name);
            player.teleport(location);
            setCooldownInSeconds(player, playerCooldown);
        }
        return true;
    }

    void loadAll() {
        reloadConfig();
        worldNames.clear();
        worldNames.addAll(getConfig().getStringList("Worlds"));
        crawlerInterval = getConfig().getInt("CrawlerInterval");
        playerCooldown = getConfig().getInt("PlayerCooldown");
        biomeDistance = getConfig().getInt("BiomeDistance");
        biomeGroups.clear();
        for (Map<?, ?> map: getConfig().getMapList("Biomes")) {
            ConfigurationSection section = getConfig().createSection("tmp", map);
            List<Biome> biomes = new ArrayList<>();
            for (String name: section.getStringList("Biomes")) {
                try {
                    biomes.add(Biome.valueOf(name.toUpperCase()));
                } catch (IllegalArgumentException iae) {
                    System.err.println("Unknown biome '" + name + "'. Ignoring");
                    iae.printStackTrace();
                }
            }
            biomeGroups.add(new BiomeGroup(section.getString("Name"), biomes));
        }
        cooldowns.clear();
        // Places
        knownPlaces.clear();
        unknownPlaces.clear();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "places.yml"));
        knownPlaces.addAll(config.getMapList("known").stream().map(m -> new Place(config.createSection("tmp", m))).collect(Collectors.toList()));
        unknownPlaces.addAll(config.getMapList("unknown").stream().map(m -> new Place(config.createSection("tmp", m))).collect(Collectors.toList()));
        resetLocatedBiomes();
        for (Place place: knownPlaces) locatedBiomes.put(place.biome, locatedBiomes.get(place.biome) + 1);
    }

    void saveAll() {
        dirty = false;
        lastSave = System.currentTimeMillis();
        YamlConfiguration biomes = new YamlConfiguration();
        biomes.set("known", knownPlaces.stream().map(p -> p.serialize()).collect(Collectors.toList()));
        biomes.set("unknown", unknownPlaces.stream().map(p -> p.serialize()).collect(Collectors.toList()));
        File file = new File(getDataFolder(), "places.yml");
        try {
            biomes.save(file);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    void setup() {
        knownPlaces.clear();
        unknownPlaces.clear();
        resetLocatedBiomes();
        for (String worldName: worldNames) {
            World world = getServer().getWorld(worldName);
            if (world == null) {
                getLogger().warning("World not found: " + worldName);
                continue;
            }
            double size = world.getWorldBorder().getSize() * 0.5 - 128.0;
            if (size > 100000.0) size = 100000.0;
            Block a = world.getWorldBorder().getCenter().add(-size, 0, -size).getBlock();
            Block b = world.getWorldBorder().getCenter().add(size, 0, size).getBlock();
            int count = 0;
            for (int z = a.getZ(); z <= b.getZ(); z += biomeDistance) {
                for (int x = a.getX(); x <= b.getX(); x += biomeDistance) {
                    if (world.getEnvironment() == World.Environment.NETHER) {
                        knownPlaces.add(new Place(world.getName(), x, z, Biome.HELL));
                        locatedBiomes.put(Biome.HELL, locatedBiomes.get(Biome.HELL) + 1);
                    } else {
                        unknownPlaces.add(new Place(world.getName(), x, z));
                    }
                    count += 1;
                }
            }
            getLogger().info("Setup: " + world.getName() + " has " + count + " places.");
        }
        Collections.shuffle(unknownPlaces, random);
        saveAll();
    }

    void setCooldownInSeconds(Player player, int sec) {
        long time = System.currentTimeMillis() + (long)sec * 1000;
        cooldowns.put(player.getUniqueId(), time);
    }

    int getCooldownInSeconds(Player player) {
        if (player.hasPermission(PERM_ADMIN)) return 0;
        Long time = cooldowns.get(player.getUniqueId());
        if (time == null) return 0;
        long result = time - System.currentTimeMillis();
        if (result < 0) return 0;
        return (int)(result / 1000);
    }

    void onTick() {
        if (ticks < crawlerInterval) {
            ticks += 1;
        } else {
            ticks = 0;
            crawl();
        }
    }

    void crawl() {
        if (unknownPlaces.isEmpty()) return;
        Place place = unknownPlaces.remove(unknownPlaces.size() - 1);
        place.biome = place.getBlock().getBiome();
        knownPlaces.add(place);
        locatedBiomes.put(place.biome, locatedBiomes.get(place.biome) + 1);
        dirty = true;
        if (lastSave + 60000L < System.currentTimeMillis()) {
            saveAll();
        }
    }

    void resetLocatedBiomes() {
        for (Biome biome: Biome.values()) {
            locatedBiomes.put(biome, 0);
        }
    }
}
