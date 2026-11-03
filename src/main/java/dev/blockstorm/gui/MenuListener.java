package dev.blockstorm.gui;

import dev.blockstorm.BlockStormPlugin;
import dev.blockstorm.arena.Arena;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class MenuListener implements Listener {

    private final BlockStormPlugin plugin;

    public MenuListener(BlockStormPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof ArenaMenu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        String name = menu.arenaAt(e.getSlot());
        if (name == null) return;
        Arena arena = plugin.arenas().get(name);
        p.closeInventory();
        if (arena != null) plugin.games().join(p, arena);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof ArenaMenu) e.setCancelled(true);
    }
}
