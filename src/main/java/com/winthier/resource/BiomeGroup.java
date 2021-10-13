package com.winthier.resource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.bukkit.block.Biome;

public final class BiomeGroup {
    public final String name;
    public final Set<Biome> biomes;
    public final List<Place> places = new ArrayList<>();
    protected int count;

    BiomeGroup(final String name, final Collection<Biome> biomes) {
        this.name = name;
        this.biomes = EnumSet.copyOf(biomes);
    }
}
