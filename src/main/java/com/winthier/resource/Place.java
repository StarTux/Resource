package com.winthier.resource;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;

@Data @AllArgsConstructor
public final class Place {
    public final String world;
    public final int x;
    public final int z;
    public final Biome biome;

    public Place(final String world, final int x, final int z) {
        this(world, x, z, (Biome) null);
    }

    public Location getLocation() {
        World bworld = Bukkit.getWorld(world);
        if (bworld == null) return null;
        return new Location(bworld, (double) x + 0.5, 65.0, (double) z + 0.5, 0.0f, 0.0f);
    }
}
