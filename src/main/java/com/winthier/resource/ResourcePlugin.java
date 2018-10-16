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
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Location;
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
    static final String PERM_ADMIN = "resource.admin";
    private final Random random = new Random(System.currentTimeMillis());
    // Configuration
    private int crawlerInterval = 20;
    private int playerCooldown = 5;
    private int biomeDistance = 256;
    private final List<String> worldNames = new ArrayList<>();
    private final List<BiomeGroup> biomeGroups = new ArrayList<>();
    private final List<Place> knownPlaces = new ArrayList<>();
    private final List<Place> unknownPlaces = new ArrayList<>();
    private final EnumMap<Biome, Integer> locatedBiomes = new EnumMap<>(Biome.class);
    // State
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private int ticks;
    private boolean dirty;
    private long lastSave;
    private int attempts;

    @AllArgsConstructor
    final class Place {
        private final String world;
        private final int x, z;
        private Biome biome;

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

        Location getLocation() {
            World bworld = getServer().getWorld(world);
            if (bworld == null) return null;
            return new Location(bworld, (double)x + 0.5, 65.0, (double)z + 0.5, 0.0f, 0.0f);
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
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player player = sender instanceof Player ? (Player)sender : null;
        String cmd = args.length > 0 ? args[0].toLowerCase() : null;
        if (cmd == null) {
            List<ChatColor> colors = Arrays.asList(ChatColor.GREEN, ChatColor.AQUA, ChatColor.RED, ChatColor.LIGHT_PURPLE, ChatColor.YELLOW, ChatColor.DARK_AQUA, ChatColor.GOLD, ChatColor.BLUE);
            if (player == null) {
                warn(sender, "Player expected");
                return true;
            }
            ComponentBuilder cb = new ComponentBuilder("");
            cb.append("[Random]").color(ChatColor.GREEN)
                .event(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/resource random"))
                .event(new HoverEvent(HoverEvent.Action.SHOW_TEXT, TextComponent.fromLegacyText(ChatColor.GREEN + "/resource random\n" + ChatColor.RESET + ChatColor.ITALIC + "Random biome")));
            for (BiomeGroup biomeGroup : biomeGroups) {
                int total = 0;
                for (Biome biome: biomeGroup.biomes) {
                    total += locatedBiomes.get(biome);
                }
                if (total == 0) continue;
                cb.append(" ");
                String lowname = biomeGroup.name.toLowerCase();
                ChatColor color = colors.get(random.nextInt(colors.size()));
                cb.append("[" + biomeGroup.name + "]").color(color)
                .event(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/resource " + lowname))
                .event(new HoverEvent(HoverEvent.Action.SHOW_TEXT, TextComponent.fromLegacyText(color + "/resource " + lowname + "\n" + ChatColor.RESET + ChatColor.ITALIC + biomeGroup.name)));
            }
            player.sendMessage("");
            player.sendMessage("" + ChatColor.BLUE + ChatColor.STRIKETHROUGH + "        " + ChatColor.BLUE + "[ " + ChatColor.WHITE
                               + "Mining Biomes"
                               + ChatColor.BLUE + " ]" + ChatColor.STRIKETHROUGH + "        ");
            player.spigot().sendMessage(cb.create());
            player.sendMessage("");
            return true;
        } else if (cmd.equals("random") && args.length == 1) {
            if (player == null) {
                warn(sender, "Player expected");
                return true;
            }
            int cd = getCooldownInSeconds(player);
            if (cd > 0) {
                warn(player, "You have to wait %d more seconds.", cd);
                return true;
            }
            Collections.shuffle(knownPlaces, random);
            Place place = null;
            for (Place p: knownPlaces) {
                if (p.biome != Biome.NETHER && p.biome != Biome.OCEAN && p.biome != Biome.DEEP_OCEAN) {
                    place = p;
                    break;
                }
            }
            if (place == null) {
                warn(player, "No biomes found.");
                return true;
            }
            info(player, "Warping to random resource biome.");
            teleport(player, place);
            setCooldownInSeconds(player, playerCooldown);
        } else if (args.length == 1 && cmd.equals("crawl")) {
            if (!sender.hasPermission(PERM_ADMIN)) return false;
            crawl();
            send(sender, "&eCompleted one crawler iteration");
        } else if (args.length == 1 && cmd.equals("info")) {
            if (!sender.hasPermission(PERM_ADMIN)) return false;
            info(sender, "%d known and %d unknown places", knownPlaces.size(), unknownPlaces.size());
            for (BiomeGroup biomeGroup: biomeGroups) {
                int total = 0;
                for (Biome biome: biomeGroup.biomes) {
                    total += locatedBiomes.get(biome);
                }
                send(sender, biomeGroup.name + ": " + total);
            }
        } else if (args.length == 1 && cmd.equals("reload")) {
            if (!sender.hasPermission(PERM_ADMIN)) return false;
            loadAll();
            send(sender, "&eConfiguration reloaded");
        } else if (args.length == 1 && cmd.equals("save")) {
            if (!sender.hasPermission(PERM_ADMIN)) return false;
            saveAll();
            send(sender, "&eConfiguration saved");
        } else if (args.length == 1 && cmd.equals("setup")) {
            if (!sender.hasPermission(PERM_ADMIN)) return false;
            setup();
            send(sender, "&eSetup done. %d known and %d unknown places with a distance of %d blocks.", knownPlaces.size(), unknownPlaces.size(), biomeDistance);
        } else {
            if (player == null) {
                warn(sender, "Player expected");
                return true;
            }
            int cd = getCooldownInSeconds(player);
            if (cd > 0) {
                warn(player, "You have to wait %d more seconds.", cd);
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
                warn(player, "Resource biome not found: %s", name);
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
                warn(sender, "No known location for %s.", biomeGroup.name);
                return true;
            }
            info(player, "Warping to %s resource biome.", biomeGroup.name);
            teleport(player, place);
            setCooldownInSeconds(player, playerCooldown);
        }
        return true;
    }

    boolean teleport(Player player, Place place) {
        Location pl = player.getLocation();
        World bworld = getServer().getWorld(place.world);
        if (bworld == null) return false;
        bworld.getChunkAtAsync(place.x >> 4, place.z >> 4, (chunk) -> {
                if (!player.isValid()) return;
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
                    Block block = bworld.getHighestBlockAt(place.x, place.z);
                    target = block.getLocation().add(0.5, 1.5, 0.5);
                }
                if (target == null) return;
                target.setYaw(pl.getYaw());
                target.setPitch(pl.getPitch());
                player.teleport(target);
                getLogger().info(String.format("[%s] Warp %s to %s %d,%d,%d (%s) empty=%b", place.biome.name(), player.getName(), target.getWorld().getName(), target.getBlockX(), target.getBlockY(), target.getBlockZ(), target.getBlock().getType(), target.getBlock().isEmpty()));
            });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String arg = args.length == 0 ? "" : args[args.length - 1].toLowerCase();
        if (args.length == 0 || args.length == 1) {
            List<String> result = new ArrayList<>();
            for (BiomeGroup biomeGroup: biomeGroups) {
                if (!biomeGroup.name.toLowerCase().startsWith(arg)) continue;
                int total = 0;
                for (Biome biome: biomeGroup.biomes) {
                    total += locatedBiomes.get(biome);
                }
                if (total > 0) result.add(biomeGroup.name);
            }
            return result;
        }
        return null;
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
                    getLogger().warning("Unknown biome '" + name + "'. Ignoring");
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
            if (size > 50000.0) size = 50000.0;
            Location a = world.getWorldBorder().getCenter().add(-size, 0, -size);
            Location b = world.getWorldBorder().getCenter().add(size, 0, size);
            int ax = a.getBlockX();
            int az = a.getBlockZ();
            int bx = b.getBlockX();
            int bz = b.getBlockZ();
            int count = 0;
            for (int z = az; z <= bz; z += biomeDistance) {
                for (int x = ax; x <= bx; x += biomeDistance) {
                    if (world.getEnvironment() == World.Environment.NETHER) {
                        knownPlaces.add(new Place(world.getName(), x, z, Biome.NETHER));
                        locatedBiomes.put(Biome.NETHER, locatedBiomes.get(Biome.NETHER) + 1);
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
        World bworld = getServer().getWorld(place.world);
        if (bworld == null) return;
        int cx = place.x >> 4;
        int cz = place.z >> 4;
        bworld.getChunkAtAsync(cx, cz, (c) -> {
                Block block = bworld.getBlockAt(place.x, 65, place.z);
                place.biome = block.getBiome();
                knownPlaces.add(place);
                locatedBiomes.put(place.biome, locatedBiomes.get(place.biome) + 1);
                dirty = true;
                if (lastSave + 60000L < System.currentTimeMillis()) {
                    saveAll();
                }
            });
    }

    void resetLocatedBiomes() {
        for (Biome biome: Biome.values()) {
            locatedBiomes.put(biome, 0);
        }
    }

    // --- Messaging Utility

    String format(String msg, Object... args) {
        if (msg == null) return "";
        msg = ChatColor.translateAlternateColorCodes('&', msg);
        if (args.length > 0) {
            msg = String.format(msg, args);
        }
        return msg;
    }

    void send(CommandSender to, String msg, Object... args) {
        to.sendMessage(format(msg, args));
    }

    void info(CommandSender to, String msg, Object... args) {
        to.sendMessage(format("&r[&3Resource&r] ") + format(msg, args));
    }

    void warn(CommandSender to, String msg, Object... args) {
        to.sendMessage(format("&r[&cResource&r] &c") + format(msg, args));
    }
}
