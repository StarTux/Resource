package com.winthier.resource;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;

public final class ResourcePlugin extends JavaPlugin {
    protected final Random random = new Random(System.currentTimeMillis());
    // Configuration
    protected int playerCooldown = 5;
    protected final List<String> worldNames = new ArrayList<>();
    protected final List<BiomeGroup> biomeGroups = new ArrayList<>();
    protected final EnumMap<Biome, Integer> locatedBiomes = new EnumMap<>(Biome.class);
    protected SidebarListener sidebarListener = new SidebarListener(this);
    // State
    protected final Map<UUID, Long> cooldowns = new HashMap<>();
    protected final List<Place> places = new ArrayList<>();
    protected final List<Place> randomPlaces = new ArrayList<>();
    protected LocalDateTime lastReset;
    protected LocalDateTime nextReset;
    protected Duration timeUntilReset = Duration.ZERO;
    protected boolean resetImminent;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadAll();
        getCommand("resource").setExecutor(new MineCommand(this));
        new AdminCommand(this).enable();
        sidebarListener.enable();
        Bukkit.getScheduler().runTaskTimer(this, this::checkReset, 0L, 20L);
    }

    @Override
    public void onDisable() {
        cooldowns.clear();
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
        //
        File mineResetFile = new File("MINE_RESET");
        File lastResetFile = new File("MINE_WORLD");
        LocalDateTime now = LocalDateTime.now();
        this.lastReset = lastResetFile.exists()
            ? LocalDateTime.ofInstant(Instant.ofEpochMilli(lastResetFile.lastModified()), ZoneId.systemDefault())
            : now;
        if (mineResetFile.exists()) {
            resetImminent = true;
            this.nextReset = now;
        } else {
            resetImminent = false;
            this.nextReset = lastReset;
            do {
                nextReset = nextReset
                    .withHour(14)
                    .withMinute(0)
                    .withSecond(0)
                    .plusDays(1L);
            } while (!nextReset.isAfter(lastReset) || nextReset.getDayOfWeek() != DayOfWeek.TUESDAY);
            getLogger().info("Next reset: " + nextReset);
            checkReset();
        }
    }

    protected void checkReset() {
        if (resetImminent) return;
        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(nextReset)) {
            resetImminent = true;
            File mineResetFile = new File("MINE_RESET");
            if (!mineResetFile.setLastModified(System.currentTimeMillis())) {
                getLogger().warning("Could not touch " + mineResetFile);
            }
        } else {
            timeUntilReset = Duration.between(now, nextReset);
        }
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
