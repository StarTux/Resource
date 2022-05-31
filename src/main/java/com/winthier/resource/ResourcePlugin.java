package com.winthier.resource;

import com.cavetale.core.connect.NetworkServer;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

public final class ResourcePlugin extends JavaPlugin {
    // Put in this array all known biomes belonging to dimensions
    // which are not supported by this plugin.
    protected static final EnumSet<Biome> IGNORED_BIOMES = EnumSet.of(Biome.CUSTOM, new Biome[] {
            Biome.CUSTOM,
            Biome.DRIPSTONE_CAVES,
            Biome.END_BARRENS,
            Biome.END_HIGHLANDS,
            Biome.END_MIDLANDS,
            Biome.LUSH_CAVES,
            Biome.SMALL_END_ISLANDS,
            Biome.THE_END,
            Biome.THE_VOID,
        });
    protected final Random random = new Random(System.currentTimeMillis());
    // Configuration
    protected int playerCooldown = 5;
    protected List<String> worldNames = List.of();
    protected final List<BiomeGroup> biomeGroups = new ArrayList<>();
    protected final EnumMap<Biome, Integer> locatedBiomes = new EnumMap<>(Biome.class);
    protected SidebarListener sidebarListener = new SidebarListener(this);
    protected boolean isMineServer;
    protected boolean doMineReset;
    protected String mineServerName;
    // State
    protected final Map<UUID, Long> cooldowns = new HashMap<>();
    protected final List<Place> places = new ArrayList<>();
    protected final List<Place> randomPlaces = new ArrayList<>();
    protected LocalDateTime lastReset;
    protected LocalDateTime nextReset;
    protected Duration timeUntilReset = Duration.ZERO;
    protected boolean resetImminent;
    protected Set<String> warnedAboutBiomes = new HashSet<>();

    @Override
    public void onEnable() {
        NetworkServer networkServer = NetworkServer.current();
        switch (networkServer) {
        case MINE: case BETA:
            isMineServer = true;
            doMineReset = true;
            break;
        case ALPHA:
            isMineServer = false;
            doMineReset = false;
            mineServerName = "beta";
            break;
        case BINGO:
            isMineServer = true;
            doMineReset = false;
            break;
        default:
            isMineServer = false;
            mineServerName = "mine";
        }
        new MineCommand(this).enable();
        new AdminCommand(this).enable();
        if (isMineServer) {
            sidebarListener.enable();
        }
        if (isMineServer && doMineReset) {
            Bukkit.getScheduler().runTaskTimer(this, this::checkReset, 0L, 20L);
        }
        loadAll();
        getLogger().info("server=" + networkServer
                         + " isMineServer=" + isMineServer
                         + " doMineReset=" + doMineReset
                         + " worlds=" + worldNames);
    }

    @Override
    public void onDisable() {
        cooldowns.clear();
    }

    public void findLocation(Place place, Consumer<Location> callback) {
        World world = getServer().getWorld(place.world);
        if (world == null) {
            callback.accept(null);
            return;
        }
        world.getChunkAtAsync(place.x, place.z, (Consumer<Chunk>) chunk -> {
                Location target = world.getEnvironment() == World.Environment.NETHER
                    ? findLocationNether(place, chunk)
                    : findLocationOverworld(place, chunk);
                callback.accept(target);
                return;
            });
    }

    private Location findLocationOverworld(Place place, Chunk chunk) {
        int ax = place.x << 4;
        int az = place.z << 4;
        List<Block> possibleBlocks = new ArrayList<>(256);
        for (int z = 0; z < 16; z += 1) {
            for (int x = 0; x < 16; x += 1) {
                Block block = chunk.getWorld().getHighestBlockAt(ax + x, az + z);
                if (place.biome.name().contains("OCEAN")) {
                    if (block.getType() != Material.WATER
                        && !((block.getBlockData() instanceof Waterlogged w) && w.isWaterlogged())) {
                        continue;
                    }
                } else {
                    if (!block.isSolid()) continue;
                }
                if (isForbiddenBlock(block.getType())) continue;
                if (!canStandIn(block.getRelative(0, 1, 0))) continue;
                if (!canStandIn(block.getRelative(0, 2, 0))) continue;
                possibleBlocks.add(block);
            }
        }
        return possibleBlocks.isEmpty() ? null
            : possibleBlocks.get(random.nextInt(possibleBlocks.size())).getLocation().add(0.5, 1.0, 0.5);
    }

    private Location findLocationNether(Place place, Chunk chunk) {
        int score;
        List<Block> possibleBlocks = new ArrayList<>(256);
        for (int z = 0; z < 16; z += 1) {
            for (int x = 0; x < 16; x += 1) {
                score = 0;
                for (int y = 112; y > 16; y -= 1) {
                    Block block = chunk.getBlock(x, y, z);
                    if (block.isLiquid()) {
                        score = 0;
                        continue;
                    }
                    switch (score) {
                    case 0: case 1: {
                        if (canStandIn(block)) {
                            score += 1;
                        } else {
                            score = 0;
                        }
                        break;
                    }
                    case 2: default: {
                        if (canStandIn(block)) {
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
                        possibleBlocks.add(block);
                    }
                }
            }
        }
        return possibleBlocks.isEmpty() ? null
            : possibleBlocks.get(random.nextInt(possibleBlocks.size())).getLocation().add(0.5, 1.0, 0.5);
    }

    /**
     * Despite being a full block (or water in the ocean), determine
     * if a block is forbidden to stand on.
     */
    private static boolean isForbiddenBlock(Material mat) {
        return mat == Material.MAGMA_BLOCK
            || mat == Material.POWDER_SNOW
            || mat == Material.LAVA;
    }

    private static boolean canStandIn(Block block) {
        return !isForbiddenBlock(block.getType())
            && block.getCollisionShape().getBoundingBoxes().isEmpty();
    }

    protected void loadAll() {
        parseConfig();
        loadBiomes();
    }

    protected void parseConfig() {
        reloadConfig();
        worldNames = getConfig().getStringList("Worlds");
        playerCooldown = getConfig().getInt("PlayerCooldown");
        biomeGroups.clear();
        Set<Biome> excludedBiomes = EnumSet.complementOf(IGNORED_BIOMES);
        for (Map<?, ?> map: getConfig().getMapList("Biomes")) {
            ConfigurationSection section = getConfig().createSection("tmp", map);
            List<Biome> biomes = new ArrayList<>();
            for (String name: section.getStringList("Biomes")) {
                Biome biome;
                try {
                    biome = Biome.valueOf(name.toUpperCase());
                } catch (IllegalArgumentException iae) {
                    if (!warnedAboutBiomes.contains(name)) {
                        warnedAboutBiomes.add(name);
                        getLogger().warning("config.yml: Unknown biome '" + name + "'. Ignoring");
                    }
                    continue;
                }
                biomes.add(biome);
                excludedBiomes.remove(biome);
            }
            String name = section.getString("Name");
            if (biomes.isEmpty()) {
                getLogger().warning("Biome group is empty: " + name);
                continue;
            }
            biomeGroups.add(new BiomeGroup(name, biomes));
        }
        if (!excludedBiomes.isEmpty()) {
            getLogger().warning("Biomes not mentioned in config.yml: " + excludedBiomes);
        }
    }

    protected void loadBiomes() {
        if (!isMineServer) {
            for (BiomeGroup biomeGroup : biomeGroups) {
                biomeGroup.count = 1;
            }
            return;
        }
        places.clear();
        randomPlaces.clear();
        for (String worldName : worldNames) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                throw new IllegalStateException("World not found: " + worldName);
            }
            if (world.getEnvironment() == World.Environment.THE_END) {
                // Do not scan The End
                continue;
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
                            Biome biome;
                            String biomeName = toks2[0];
                            try {
                                biome = Biome.valueOf(biomeName);
                            } catch (IllegalArgumentException iae) {
                                if (!warnedAboutBiomes.contains(biomeName)) {
                                    warnedAboutBiomes.add(biomeName);
                                    getLogger().warning(biomesFile + ": Biome not found: " + biomeName);
                                }
                                continue;
                            }
                            int count = toks2.length >= 2
                                ? Integer.parseInt(toks2[1])
                                : 1; // legacy
                            if (count > max) {
                                max = count;
                                chunkBiome = biome;
                            }
                        }
                        if (chunkBiome == null) {
                            continue;
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
        if (isMineServer && doMineReset) {
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
    }

    protected void checkReset() {
        if (resetImminent) return;
        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(nextReset)) {
            resetImminent = true;
            File mineResetFile = new File("MINE_RESET");
            try {
                new FileOutputStream(mineResetFile).close();
            } catch (IOException ioe) {
                getLogger().warning("Could not create " + mineResetFile);
            }
        } else {
            timeUntilReset = Duration.between(now, nextReset);
        }
    }

    protected void setCooldownInSeconds(UUID uuid, int sec) {
        long time = System.currentTimeMillis() + (long) sec * 1000;
        cooldowns.put(uuid, time);
    }

    protected int getCooldownInSeconds(UUID uuid) {
        long time = cooldowns.getOrDefault(uuid, 0L);
        if (time == 0L) return 0;
        long result = time - System.currentTimeMillis();
        if (result < 0) return 0;
        return (int) (result / 1000);
    }
}
