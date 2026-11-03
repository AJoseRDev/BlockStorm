package dev.blockstorm.listener;

import dev.blockstorm.BlockStormPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.blockstorm.util.Messages.ph;

/** Built-in cuboid selection tool, so no WorldEdit is needed. */
public final class WandManager implements Listener {

    private final BlockStormPlugin plugin;
    private final NamespacedKey key;
    private final Map<UUID, Location[]> selections = new HashMap<>();

    public WandManager(BlockStormPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "wand");
    }

    public ItemStack create() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("BlockStorm Wand", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Left click: position 1", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Right click: position 2", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isWand(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** @return {pos1, pos2} (entries may be null) or null if the player never selected anything. */
    public Location[] selection(UUID id) { return selections.get(id); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !isWand(e.getItem())) return;
        Player p = e.getPlayer();
        if (!p.hasPermission("blockstorm.admin")) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        int index;
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) index = 0;
        else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) index = 1;
        else return;
        e.setCancelled(true);
        Location[] sel = selections.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
        sel[index] = b.getLocation();
        plugin.messages().send(p, "admin.wand-pos", ph("n", index + 1),
                ph("x", b.getX()), ph("y", b.getY()), ph("z", b.getZ()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        if (isWand(e.getPlayer().getInventory().getItemInMainHand())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        selections.remove(e.getPlayer().getUniqueId());
    }
}
