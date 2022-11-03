package com.winthier.resource;

import com.cavetale.core.connect.NetworkServer;
import com.cavetale.core.font.Unicode;
import com.cavetale.core.struct.Vec2i;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import static com.cavetale.structure.StructurePlugin.structureCache;
import static net.kyori.adventure.text.Component.join;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.JoinConfiguration.noSeparators;
import static net.kyori.adventure.text.format.NamedTextColor.*;

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
            Biome.DEEP_DARK,
        });
    protected final Random random = new Random(System.currentTimeMillis());
    // Configuration
    protected int playerCooldown = 5;
    protected List<String> worldNames = List.of();
    protected final List<BiomeGroup> biomeGroups = new ArrayList<>();
    protected final EnumMap<Biome, Integer> locatedBiomes = new EnumMap<>(Biome.class);
    protected SidebarListener sidebarListener;
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
    protected Component timeUntilResetFormat = Component.empty();
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
            sidebarListener = new SidebarListener(this);
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
                getLogger().severe("World not found: " + worldName);
                continue;
            }
            if (world.getEnvironment() == World.Environment.THE_END) {
                // Do not scan The End
                continue;
            }
            WorldBorder worldBorder = world.getWorldBorder();
            final double halfSize = worldBorder.getSize() * 0.5;
            final Location center = worldBorder.getCenter();
            final int borderWest = ((int) Math.ceil(center.getX() - halfSize)) >> 4;
            final int borderEast = ((int) Math.floor(center.getX() + halfSize)) >> 4;
            final int borderNorth = ((int) Math.ceil(center.getZ() - halfSize)) >> 4;
            final int borderSouth = ((int) Math.floor(center.getZ() + halfSize)) >> 4;
            int worldTotalPlaces = 0;
            int outsideBorderCount = 0;
            int netherPlainsCount = 0;
            final boolean nether = world.getEnvironment() == World.Environment.NETHER;
            Map<Vec2i, Biome> biomes = structureCache().allBiomes(world);
            for (Map.Entry<Vec2i, Biome> entry : biomes.entrySet()) {
                final Vec2i vec = entry.getKey();
                final Biome biome = entry.getValue();
                if (nether && biome == Biome.PLAINS) {
                    netherPlainsCount += 1;
                    continue;
                }
                if (vec.x <= borderWest || vec.x >= borderEast || vec.z <= borderNorth || vec.z >= borderSouth) {
                    outsideBorderCount += 1;
                    continue;
                }
                Place place = new Place(worldName, vec.x, vec.z, biome);
                places.add(place);
                int placeBiomeGroups = 0;
                for (BiomeGroup biomeGroup : biomeGroups) {
                    if (biomeGroup.biomes.contains(biome)) {
                        biomeGroup.places.add(place);
                        biomeGroup.count += 1;
                        placeBiomeGroups += 1;
                    }
                }
                if (!nether && !biome.name().contains("OCEAN")) {
                    randomPlaces.add(place);
                }
                if (placeBiomeGroups > 0) worldTotalPlaces += 1;
            }
            getLogger().info(worldName + " total=" + worldTotalPlaces + " outside=" + outsideBorderCount);
            if (nether && netherPlainsCount > 0) {
                getLogger().info(worldName + " netherPlains=" + netherPlainsCount);
            }
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
                boolean enough = false;
                while (!enough) {
                    nextReset = nextReset
                        .withHour(12)
                        .withMinute(0)
                        .withSecond(0)
                        .plusDays(1L);
                    enough = Duration.between(lastReset, nextReset).toDays() >= 13
                        && nextReset.getDayOfWeek() == DayOfWeek.TUESDAY;
                }
                getLogger().info("Next reset: " + nextReset);
                checkReset();
            }
        }
    }

    private static final Component SOON = text("Soon\u2122", DARK_RED);

    protected void checkReset() {
        if (resetImminent) {
            timeUntilResetFormat = SOON;
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isAfter(nextReset)) {
            resetImminent = true;
            File mineResetFile = new File("MINE_RESET");
            try {
                new FileOutputStream(mineResetFile).close();
            } catch (IOException ioe) {
                getLogger().warning("Could not create " + mineResetFile);
            }
            timeUntilResetFormat = SOON;
        } else {
            timeUntilReset = Duration.between(now, nextReset);
            timeUntilResetFormat = formatDuration(timeUntilReset);
        }
    }

    private static Component formatDuration(Duration duration) {
        final long seconds = duration.toSeconds();
        final long minutes = duration.toMinutes();
        final long hours = duration.toHours();
        final long days = duration.toDays();
        ArrayList<Component> list = new ArrayList<>(8);
        list.add(text(days, WHITE));
        list.add(text(Unicode.SMALLD.character, GRAY));
        list.add(text(hours % 24, WHITE));
        list.add(text(Unicode.SMALLH.character, GRAY));
        list.add(text(minutes % 60, WHITE));
        list.add(text(Unicode.SMALLM.character, GRAY));
        list.add(text(Math.max(0, seconds % 60), WHITE));
        list.add(text(Unicode.SMALLS.character, GRAY));
        return join(noSeparators(), list);
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
