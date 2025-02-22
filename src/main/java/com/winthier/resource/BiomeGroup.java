package com.winthier.resource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import lombok.Data;
import org.bukkit.block.Biome;
import org.bukkit.inventory.ItemStack;

@Data
public final class BiomeGroup {
    public final String name;
    public final Set<Biome> biomes;
    public final ItemStack icon;
    public final List<Place> places = new ArrayList<>();
    protected int count;

    BiomeGroup(final String name, final Collection<Biome> biomes, final ItemStack icon) {
        this.name = name;
        this.biomes = !biomes.isEmpty()
            ? Set.copyOf(biomes)
            : Set.of();
        this.icon = icon;
    }
}
