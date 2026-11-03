package dev.blockstorm.stats;

import dev.blockstorm.BlockStormPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Persistent player statistics (YAML, written asynchronously). */
public final class StatsManager {

    public static final class PlayerStats {
        public String name = "?";
        public int games;
        public int wins;
        public int best;      // best survival time in seconds
        public long total;    // total seconds survived
    }

    private final BlockStormPlugin plugin;
    private final File file;
    private final Map<UUID, PlayerStats> stats = new HashMap<>();

    public StatsManager(BlockStormPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
    }

    public void load() {
        stats.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("players");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            try {
                PlayerStats ps = new PlayerStats();
                ps.name = s.getString("name", "?");
                ps.games = s.getInt("games");
                ps.wins = s.getInt("wins");
                ps.best = s.getInt("best");
                ps.total = s.getLong("total");
                stats.put(UUID.fromString(key), ps);
            } catch (IllegalArgumentException ignored) {
                // malformed uuid, skip entry
            }
        }
    }

    public PlayerStats get(UUID id) { return stats.get(id); }

    public void recordGame(UUID id, String name, int seconds, boolean won) {
        PlayerStats ps = stats.computeIfAbsent(id, k -> new PlayerStats());
        ps.name = name;
        ps.games++;
        if (won) ps.wins++;
        ps.best = Math.max(ps.best, seconds);
        ps.total += seconds;
        saveAsync();
    }

    /** @param category best | wins | games */
    public List<Map.Entry<UUID, PlayerStats>> top(String category, int limit) {
        Comparator<PlayerStats> cmp = switch (category.toLowerCase(Locale.ROOT)) {
            case "wins" -> Comparator.comparingInt(s -> s.wins);
            case "games" -> Comparator.comparingInt(s -> s.games);
            default -> Comparator.comparingInt(s -> s.best);
        };
        List<Map.Entry<UUID, PlayerStats>> list = new ArrayList<>(stats.entrySet());
        list.removeIf(e -> cmp.compare(e.getValue(), new PlayerStats()) <= 0);
        list.sort((a, b) -> cmp.compare(b.getValue(), a.getValue()));
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    public static int valueOf(PlayerStats s, String category) {
        return switch (category.toLowerCase(Locale.ROOT)) {
            case "wins" -> s.wins;
            case "games" -> s.games;
            default -> s.best;
        };
    }

    private String serialize() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerStats> e : stats.entrySet()) {
            String base = "players." + e.getKey();
            PlayerStats s = e.getValue();
            y.set(base + ".name", s.name);
            y.set(base + ".games", s.games);
            y.set(base + ".wins", s.wins);
            y.set(base + ".best", s.best);
            y.set(base + ".total", s.total);
        }
        return y.saveToString();
    }

    private void saveAsync() {
        String data = serialize();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(data));
    }

    /** Synchronous save, used on shutdown. */
    public void saveNow() {
        write(serialize());
    }

    private synchronized void write(String data) {
        try {
            plugin.getDataFolder().mkdirs();
            Path tmp = file.toPath().resolveSibling("stats.yml.tmp");
            Files.writeString(tmp, data, StandardCharsets.UTF_8);
            Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save stats.yml: " + e.getMessage());
        }
    }
}
