package dev.blockstorm.gui;

import dev.blockstorm.BlockStormPlugin;
import dev.blockstorm.arena.Arena;
import dev.blockstorm.game.Game;
import dev.blockstorm.game.GameState;
import dev.blockstorm.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static dev.blockstorm.util.Messages.ph;

/** Clickable arena list shown by /bs join. */
public final class ArenaMenu implements InventoryHolder {

    private final Inventory inventory;
    private final Map<Integer, String> slots = new HashMap<>();

    public ArenaMenu(BlockStormPlugin plugin) {
        List<Arena> list = new ArrayList<>(plugin.arenas().all());
        int rows = Math.max(1, Math.min(6, (list.size() + 8) / 9));
        inventory = Bukkit.createInventory(this, rows * 9, plugin.messages().component("menu.title"));

        int slot = 0;
        for (Arena a : list) {
            if (slot >= rows * 9) break;
            Game g = plugin.games().peek(a);
            GameState st = g == null ? GameState.WAITING : g.state();
            int n = g == null ? 0 : g.size();
            boolean playable = a.isEnabled() && a.validate() == null;

            Material icon;
            if (!playable) icon = Material.GRAY_CONCRETE;
            else icon = switch (st) {
                case WAITING -> Material.LIME_CONCRETE;
                case COUNTDOWN -> Material.YELLOW_CONCRETE;
                default -> Material.RED_CONCRETE;
            };

            Messages m = plugin.messages();
            TagResolver[] r = {
                    ph("arena", a.getName()), ph("players", n), ph("max", a.getMaxPlayers()),
                    Messages.comp("status", m.component(playable
                            ? "status." + st.name().toLowerCase(Locale.ROOT) : "status.unavailable",
                            ph("countdown", g== null ? 0 : g.countdown())))
            };
            ItemStack item = new ItemStack(icon);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(m.component("menu.item-name", r).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            for (Component c : m.list("menu.lore", r)) lore.add(c.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
            slots.put(slot, a.getName());
            slot++;
        }
    }

    public void open(Player p) { p.openInventory(inventory); }

    public String arenaAt(int slot) { return slots.get(slot); }

    @Override
    public Inventory getInventory() { return inventory; }
}
