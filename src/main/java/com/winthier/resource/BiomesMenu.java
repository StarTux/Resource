package com.winthier.resource;

import com.cavetale.core.font.GuiOverlay;
import com.cavetale.core.menu.MenuItemEvent;
import com.cavetale.mytems.Mytems;
import com.cavetale.mytems.util.Gui;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import static com.cavetale.mytems.util.Items.tooltip;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.textOfChildren;
import static net.kyori.adventure.text.format.NamedTextColor.*;
import static net.kyori.adventure.text.format.TextColor.color;

@RequiredArgsConstructor
public final class BiomesMenu {
    private final ResourcePlugin plugin;
    private final Player player;

    public void open() {
        final Gui gui = new Gui(plugin)
            .size(6 * 9)
            .title(text("Mining Biomes", GOLD))
            .layer(GuiOverlay.BLANK, color(0xd3b683));
        gui.setItem(9, Mytems.DICE.createIcon(List.of(text("Random", GRAY),
                                                      textOfChildren(Mytems.MOUSE_LEFT, text(" Warp", GRAY)))),
                    click -> {
                        if (!click.isLeftClick()) return;
                        player.performCommand("mine random");
                        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
                    });
        int nextGuiIndex = 10;
        for (BiomeGroup biomeGroup : plugin.getBiomeGroups()) {
            final int guiIndex = nextGuiIndex++;
            final ItemStack icon = tooltip(biomeGroup.getIcon().clone(),
                                           List.of(text(biomeGroup.getName(), GOLD),
                                                   textOfChildren(Mytems.MOUSE_LEFT, text(" Warp", GRAY))));
            gui.setItem(guiIndex, icon, click -> {
                    if (!click.isLeftClick()) return;
                    player.performCommand("mine " + biomeGroup.getName());
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f);
                });
        }
        gui.setItem(Gui.OUTSIDE, null, click -> {
                if (!click.isLeftClick()) return;
                MenuItemEvent.openMenu(player);
            });
        gui.open(player);
    }
}
