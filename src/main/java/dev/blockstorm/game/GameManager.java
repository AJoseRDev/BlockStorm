package dev.blockstorm.game;

import dev.blockstorm.BlockStormPlugin;
import dev.blockstorm.arena.Arena;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Owns one {@link Game} per arena and the on-disk crash-safety snapshots. */
public final class GameManager {

    private final BlockStormPlugin plugin;
    private final Map<String, Game> games = new HashMap<>();
    private final File snapshotDir;

    public GameManager(BlockStormPlugin plugin) {
        this.plugin = plugin;
        this.snapshotDir = new File(plugin.getDataFolder(), "snapshots");
    }

    private static String key(Arena a) { return a.getName().toLowerCase(Locale.ROOT); }

    public Game gameFor(Arena a) {
        return games.computeIfAbsent(key(a), k -> new Game(plugin, a));
    }

    /** Looks up a game without creating it. */
    public Game peek(Arena a) { return games.get(key(a)); }

    public Game gameOf(UUID id) {
        for (Game g : games.values()) if (g.isParticipant(id)) return g;
        return null;
    }

    public Game findByFalling(UUID entityId) {
        for (Game g : games.values()) if (g.ownsFalling(entityId)) return g;
        return null;
    }

    public boolean isBusy(Arena a) {
        Game g = peek(a);
        return g != null && g.size() > 0;
    }

    public void join(Player p, Arena a) {
        if (gameOf(p.getUniqueId()) != null) {
            plugin.messages().send(p, "general.already-playing");
            return;
        }
        String error = gameFor(a).join(p);
        if (error != null) plugin.messages().send(p, error);
    }

    /** Joins the fullest game that is still waiting for players. */
    public void quickJoin(Player p) {
        Arena best = null;
        int bestCount = -1;
        for (Arena a : plugin.arenas().all()) {
            if (!a.isEnabled() || a.validate() != null) continue;
            Game g = peek(a);
            GameState st = g == null ? GameState.WAITING : g.state();
            int n = g == null ? 0 : g.size();
            if (st != GameState.WAITING && st != GameState.COUNTDOWN) continue;
            if (n >= a.getMaxPlayers()) continue;
            if (n > bestCount) {
                best = a;
                bestCount = n;
            }
        }
        if (best == null) plugin.messages().send(p, "general.no-arena-available");
        else join(p, best);
    }

    public void leave(Player p) {
        Game g = gameOf(p.getUniqueId());
        if (g == null) plugin.messages().send(p, "general.not-playing");
        else g.leave(p.getUniqueId(), true);
    }

    public void removeArena(Arena a) {
        Game g = games.remove(key(a));
        if (g != null) g.forceStop();
    }

    public void shutdown() {
        for (Game g : games.values()) g.forceStop();
        games.clear();
    }

    // ── crash-safe inventory snapshots ──────────────────────
    private File snapshotFile(UUID id) { return new File(snapshotDir, id + ".yml"); }

    void saveSnapshot(UUID id, PlayerSnapshot snap) {
        try {
            snap.save(snapshotFile(id));
        } catch (IOException e) {
            plugin.getLogger().warning("Could not persist snapshot for " + id + ": " + e.getMessage());
        }
    }

    void deleteSnapshot(UUID id) {
        File f = snapshotFile(id);
        if (f.exists() && !f.delete()) f.deleteOnExit();
    }

    /** Gives back the inventory of a player whose game was interrupted by a crash. */
    public void restoreLeftover(Player p) {
        File f = snapshotFile(p.getUniqueId());
        if (!f.exists() || gameOf(p.getUniqueId()) != null) return;
        PlayerSnapshot snap = PlayerSnapshot.load(f);
        if (snap != null) {
            snap.restore(p, true);
            plugin.messages().send(p, "general.inventory-restored");
            f.delete();
        } else {
            plugin.getLogger().warning("Unreadable snapshot kept for manual recovery: " + f.getName());
        }
    }
}
