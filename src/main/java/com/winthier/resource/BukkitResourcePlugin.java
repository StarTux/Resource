package com.winthier.resource;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.NonNull;
import org.bukkit.ChatColor;
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
import org.bukkit.scheduler.BukkitRunnable;
import org.json.simple.JSONValue;

public class BukkitResourcePlugin extends JavaPlugin {
    static enum Config {
        WORLD_NAME("World"),
        CENTER("Center"),
        RADIUS("Radius"),
        SHOW_BIOMES("Biomes"),
        BIOMES_PATH("biomes.yml"),
        PERM_ADMIN("resource.admin"),
        X("X"),
        Z("Z"),
        CRAWLER_INTERVAL("CrawlerInterval"),
        PLAYER_COOLDOWN("PlayerCooldown"),
        ;
        final String key;
        Config(String key) {
            this.key = key;
        }
    }

    @Getter
    class Coordinate {
        final private int x, z;
        Coordinate(int x, int z) {
            this.x = x;
            this.z = z;
        }
        Coordinate(Location location) {
            this(location.getBlockX(), location.getBlockZ());
        }
        Location location() {
            return getWorld().getHighestBlockAt(x, z).getLocation().add(0.5, 0.5, 0.5);
        }
    }

    class Crawler extends BukkitRunnable {
        @Override
        public void run() {
            crawl();
        }
    }
    
    final Random random = new Random(System.currentTimeMillis());
    Crawler crawler = null;
    long crawlerInterval = 20L * 5L;
    int playerCooldown = 10;
    String worldName = "Resource";
    final Map<Biome, Coordinate> coordinates = new EnumMap<>(Biome.class);
    final Map<String, Object> buttons = new HashMap<>();
    final List<Biome> showBiomes = new ArrayList<>();
    final Map<UUID, Long> cooldowns = new HashMap<>();
    int centerX = 0, centerZ = 0;
    int radius = 1000;
    
    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadAll();
    }

    @Override
    public void onDisable() {
        storeAll();
        cooldowns.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String args[]) {
        Player player = sender instanceof Player ? (Player)sender : null;
        if (args.length == 0) {
            if (player != null) {
                List<Object> message = new ArrayList<>();
                message.add(format("&3&lResource Biomes "));
                message.add(makeButton("Random"));
                for (Biome biome : showBiomes) {
                    if (coordinates.containsKey(biome)) {
                        message.add(" ");
                        message.add(makeButton(biome.name()));
                    }
                }
                player.sendMessage("");
                tellRaw(player, message);
                player.sendMessage("");
            }
            if (sender.hasPermission(Config.PERM_ADMIN.key)) {
                send(sender, "&e/Resource Crawl|Reload|Save|Clear");
            }
            return true;
        }
        String firstArg = args[0].toLowerCase();
        if (args.length == 1 && firstArg.equals("random")) {
            int cd = getCooldownInSeconds(player);
            if (cd > 0) {
                send(player, "&cYou have to wait %d more seconds.", cd);
                return true;
            }
            Location location = randomLocation();
            if (location == null) {
                location = rollLocation();
                if (location != null) testLocation(location);
            }
            if (location == null) {
                send(sender, "&cNothing found. Please try again");
            } else {
                getLogger().info(String.format("Teleporting %s to %s %d,%d,%d", player.getName(), location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ()));
                send(sender, "&3Teleporting you to a random location in the resource world");
                player.teleport(location);
                setCooldownInSeconds(player, playerCooldown);
            }
        } else if (args.length == 1 && firstArg.equals("crawl")) {
            if (!sender.hasPermission(Config.PERM_ADMIN.key)) return false;
            crawl();
            send(sender, "&eCompleted one crawler iteration");
        } else if (args.length == 1 && firstArg.equals("reload")) {
            if (!sender.hasPermission(Config.PERM_ADMIN.key)) return false;
            loadAll();
            send(sender, "&eConfiguration reloaded");
        } else if (args.length == 1 && firstArg.equals("save")) {
            if (!sender.hasPermission(Config.PERM_ADMIN.key)) return false;
            storeAll();
            send(sender, "&eConfiguration saved");
        } else if (args.length == 1 && firstArg.equals("clear")) {
            if (!sender.hasPermission(Config.PERM_ADMIN.key)) return false;
            coordinates.clear();
            send(sender, "&eLocations cleared");
        } else {
            if (player == null) {
                send(sender, "&cPlayer expected");
                return true;
            }
            StringBuilder sb = new StringBuilder(args[0]);
            for (int j = 1; j < args.length; ++j) {
                sb.append("_").append(args[j]);
            }
            Biome biome;
            try {
                biome = Biome.valueOf(sb.toString().toUpperCase());
            } catch (IllegalArgumentException iae) {
                return false;
            }
            if (!showBiomes.contains(biome) && !player.hasPermission(Config.PERM_ADMIN.key)) {
                send(sender, "&cNo known location for %s.", camels(biome.name()));
                return true;
            }
            Location location = getLocation(biome);
            if (location == null) {
                send(sender, "&cNo known location for %s.", camels(biome.name()));
                return true;
            }
            int cd = getCooldownInSeconds(player);
            if (cd > 0) {
                send(player, "&cYou have to wait %d more seconds.", cd);
                return true;
            }
            getLogger().info(String.format("Teleporting %s to %s %d,%d,%d", player.getName(), location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ()));
            send(sender, "&3Teleporting you to a %s biome in the resource world.", biome.name().toLowerCase().replace("_", " "));;
            player.teleport(location);
            setCooldownInSeconds(player, playerCooldown);
        }
        return true;
    }

    private boolean checkLocation(Location location, Biome biome, boolean log) {
        // Check if location is in the right world
        if (!getWorld().equals(location.getWorld())) {
            if (log) getLogger().warning("Biome location not in the right world: " + biome);
            return false;
        }
        // Check if biome at location is still correct
        if (biome != null && !biome.equals(getWorld().getBiome(location.getBlockX(), location.getBlockZ()))) {
            if (log) getLogger().warning("Biome location not of the right biome: " + biome);
            return false;
        }
        // Check if biome at location is blocked
        if (!location.getBlock().getType().isTransparent() || !location.getBlock().getRelative(0, 1, 0).getType().isTransparent()) {
            if (log) getLogger().warning("Biome location is blocked: " + biome);
            return false;
        }
        // Check if biome at location is on solid ground
        if (!location.getBlock().getRelative(0, -1, 0).getType().isSolid()) {
            if (log) getLogger().warning("Biome location in the air: " + biome);
            return false;
        }
        return true;
    }

    Location getLocation(@NonNull Biome biome) {
        Coordinate coordinate = coordinates.get(biome);
        if (coordinate == null) return null;
        Location location = coordinate.location();
        if (!checkLocation(location, biome, true)) return null;
        return location;
    }

    static String format(@NonNull String message, Object... args) {
        message = ChatColor.translateAlternateColorCodes('&', message);
        if (args.length > 0) message = String.format(message, args);
        return message;
    }

    static void send(@NonNull CommandSender sender, @NonNull String message, Object... args) {
        sender.sendMessage(format(message, args));
    }

    World getWorld() {
        World result = getServer().getWorld(worldName);
        if (result == null) getLogger().warning("World not found: " + worldName);
        return result;
    }

    void loadAll() {
        reloadConfig();
        worldName = getConfig().getString(Config.WORLD_NAME.key, worldName);
        List<Integer> center = getConfig().getIntegerList(Config.CENTER.key);
        if (center.size() >= 1) centerX = center.get(0);
        if (center.size() >= 2) centerZ = center.get(1);
        radius = getConfig().getInt(Config.RADIUS.key, radius);
        crawlerInterval = getConfig().getLong(Config.CRAWLER_INTERVAL.key, crawlerInterval);
        playerCooldown = getConfig().getInt(Config.PLAYER_COOLDOWN.key, playerCooldown);
        showBiomes.clear();
        for (String entry : getConfig().getStringList(Config.SHOW_BIOMES.key)) {
            Biome biome;
            try {
                biome = Biome.valueOf(entry.toUpperCase());
            } catch (IllegalArgumentException iae) {
                getLogger().warning("Biome in config.yml (Biomes) not found: " + entry);
                continue;
            }
            if (showBiomes.contains(biome)) {
                getLogger().warning("Duplicate biome in config.yml (Biomes): " + biome);
            } else {
                showBiomes.add(biome);
            }
        }
        if (showBiomes.isEmpty()) getLogger().warning("Warning: Biomes list is empty. Not going to display any biomes");
        //
        File file = new File(getDataFolder(), Config.BIOMES_PATH.key);
        YamlConfiguration biomes = YamlConfiguration.loadConfiguration(file);
        coordinates.clear();
        for (String key : biomes.getKeys(false)) {
            Biome biome;
            try {
                biome = Biome.valueOf(key);
            } catch (IllegalArgumentException iae) {
                getLogger().warning("Biome from biomes.yml not found: " + key);
                continue;
            }
            ConfigurationSection section = biomes.getConfigurationSection(biome.name());
            if (section != null) {
                int x = section.getInt(Config.X.key, 0);
                int z = section.getInt(Config.Z.key, 0);
                coordinates.put(biome, new Coordinate(x, z));
            }
        }
        // Crawler
        if (crawler != null) {
            try {
                crawler.cancel();
                crawler = null;
            } catch (IllegalStateException ise) {}
        }
        crawler = new Crawler();
        crawler.runTaskTimer(this, crawlerInterval, crawlerInterval);
        // Cooldowns
        cooldowns.clear();
    }

    void storeAll() {
        saveConfig();
        YamlConfiguration biomes = new YamlConfiguration();
        for (Biome biome : Biome.values()) {
            Coordinate coordinate = coordinates.get(biome);
            if (coordinate != null) {
                ConfigurationSection section = biomes.createSection(biome.name());
                section.set(Config.X.key, coordinate.getX());
                section.set(Config.Z.key, coordinate.getZ());
            }
        }
        File file = new File(getDataFolder(), Config.BIOMES_PATH.key);
        try {
            biomes.save(file);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    boolean testLocation(@NonNull Location location) {
        if (!checkLocation(location, null, false)) return false;
        Biome biome = getWorld().getBiome(location.getBlockX(), location.getBlockZ());
        coordinates.put(biome, new Coordinate(location));
        return true;
    }

    Coordinate rollCoordinate() {
        int x = centerX - radius + random.nextInt(radius + radius);
        int z = centerZ - radius + random.nextInt(radius + radius);
        return new Coordinate(x, z );
    }

    Location rollLocation() {
        for (int i = 0; i < 32; ++i) {
            Coordinate coordinate = rollCoordinate();
            Location location = coordinate.location();
            if (checkLocation(location, null, false)) {
                return location;
            }
        }
        return null;
    }
    
    Location randomLocation() {
        List<Biome> shuf = Arrays.asList(Biome.values());
        Collections.shuffle(shuf);
        for (Biome biome : shuf) {
            if (coordinates.containsKey(biome)) {
                return coordinates.get(biome).location();
            }
        }
        return null;
    }

    void crawl() {
        Location rolled = rollLocation();
        if (rolled != null) testLocation(rolled);
        for (Player player : getWorld().getPlayers()) {
            Location loc = player.getLocation();
            loc = new Coordinate(loc).location();
            testLocation(loc);
        }
    }

    Object makeButton(String biome) {
        if (buttons.containsKey(biome)) return buttons.get(biome);
        Map<String, Object> result = new HashMap<>();
        result.put("text", format("&r[&a%s&r]", camels(biome)).replace(" ", format(" &a")));
        result.put("color", "green");
        Map<String, Object> event = new HashMap<>();
        result.put("clickEvent", event);
        event.put("action", "run_command");
        event.put("value", "/resource " + biome.toLowerCase());
        event = new HashMap<>();
        result.put("hoverEvent", event);
        event.put("action", "show_text");
        Map<String, Object> text = new HashMap<>();
        event.put("value", text);
        text.put("color", "dark_aqua");
        text.put("text", format("&a%s\n&oWarp\nTeleport to a %s biome.", camels(biome), camels(biome).toLowerCase()));
        buttons.put(biome, result);
        return result;
    }

    String camels(String biome) {
        StringBuilder sb = new StringBuilder();
        for (String token : biome.split("_")) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(token.substring(0, 1).toUpperCase());
            sb.append(token.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    void tellRaw(Player player, Object json) {
        CommandSender sender = getServer().getConsoleSender();
        String command = String.format("minecraft:tellraw %s %s", player.getName(), JSONValue.toJSONString(json));
        getServer().dispatchCommand(sender, command);
    }

    void setCooldownInSeconds(Player player, int sec) {
        long time = System.currentTimeMillis() + (long)sec * 1000;
        cooldowns.put(player.getUniqueId(), time);
    }

    int getCooldownInSeconds(Player player) {
        if (player.hasPermission(Config.PERM_ADMIN.key)) return 0;
        Long time = cooldowns.get(player.getUniqueId());
        if (time == null) return 0;
        long result = time - System.currentTimeMillis();
        if (result < 0) return 0;
        return (int)(result / 1000);
    }
}
