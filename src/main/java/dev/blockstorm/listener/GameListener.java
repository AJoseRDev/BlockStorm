package dev.blockstorm.listener;

import dev.blockstorm.BlockStormPlugin;
import dev.blockstorm.game.Game;
import dev.blockstorm.game.GameState;
import org.bukkit.Location;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Locale;

public final class GameListener implements Listener {

    private final BlockStormPlugin plugin;

    public GameListener(BlockStormPlugin plugin) {
        this.plugin = plugin;
    }

    private Game game(Player p) { return plugin.games().gameOf(p.getUniqueId()); }

    /** A falling block of ours became a real block. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLand(EntityChangeBlockEvent e) {
        if (!(e.getEntity() instanceof FallingBlock fb) || e.getTo().isAir()) return;
        Game g = plugin.games().findByFalling(fb.getUniqueId());
        if (g != null) g.onLand(fb.getUniqueId(), e.getBlock());
    }

    /** Turns lethal damage into an elimination and blocks all damage outside the running phase. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Game g = game(p);
        if (g == null) return;

        if (g.state() != GameState.RUNNING || !g.isAlive(p.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (e instanceof EntityDamageByEntityEvent by && by.getDamager() instanceof Player
                && !plugin.getConfig().getBoolean("game.pvp", false)) {
            e.setCancelled(true);
            return;
        }
        if (e.getFinalDamage() >= p.getHealth()) {
            e.setCancelled(true);
            String cause = switch (e.getCause()) {
                case SUFFOCATION, FALLING_BLOCK -> "crushed";
                case CUSTOM -> "hit";
                default -> "other";
            };
            g.eliminate(p, cause);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        Location from = e.getFrom();
        if (to == null || (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ())) return;
        Player p = e.getPlayer();
        Game g = game(p);
        if (g == null || g.state() != GameState.RUNNING || !g.isAlive(p.getUniqueId())) return;
        if (!g.arena().contains(to)) g.outOfBounds(p);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && game(p) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent e) {
        if (!(e.getEntity() instanceof Player p) || game(p) == null) return;
        if (plugin.getConfig().getBoolean("game.regeneration", false)) return;
        if (e.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED
                || e.getRegainReason() == EntityRegainHealthEvent.RegainReason.REGEN) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (game(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && game(p) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (game(p) == null || p.hasPermission("blockstorm.bypass")) return;
        String message = e.getMessage();
        if (message.length() < 2) return;
        String label = message.substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        final String cmd = colon >= 0 ? label.substring(colon + 1) : label;
        List<String> allowed = plugin.getConfig().getStringList("game.allowed-commands");
        if (allowed.stream().noneMatch(a -> a.equalsIgnoreCase(cmd))) {
            e.setCancelled(true);
            plugin.messages().send(p, "general.command-blocked");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Game g = game(e.getPlayer());
        if (g != null) g.leave(e.getPlayer().getUniqueId(), true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) plugin.games().restoreLeftover(p);
        }, 2L);
    }
}
