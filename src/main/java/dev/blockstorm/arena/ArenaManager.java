package dev.blockstorm.arena;

import dev.blockstorm.BlockStormPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ArenaManager {

    private final BlockStormPlugin plugin;
    private final File file;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();

    public ArenaManager(BlockStormPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "arenas.yml");
    }

    public void load() {
        arenas.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("arenas");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s != null) arenas.put(key.toLowerCase(Locale.ROOT), Arena.load(key, s));
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Arena a : arenas.values()) a.save(y.createSection("arenas." + a.getName()));
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save arenas.yml: " + e.getMessage());
        }
    }

    public Arena get(String name) {
        return name == null ? null : arenas.get(name.toLowerCase(Locale.ROOT));
    }

    public Arena create(String name) {
        Arena a = new Arena(name);
        arenas.put(name.toLowerCase(Locale.ROOT), a);
        save();
        return a;
    }

    public boolean delete(String name) {
        boolean removed = arenas.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed) save();
        return removed;
    }

    public Collection<Arena> all() { return arenas.values(); }

    public List<String> names() {
        List<String> n = new ArrayList<>();
        for (Arena a : arenas.values()) n.add(a.getName());
        return n;
    }
}
