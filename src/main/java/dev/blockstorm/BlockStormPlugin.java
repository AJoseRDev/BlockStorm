package dev.blockstorm;

import dev.blockstorm.arena.ArenaManager;
import dev.blockstorm.command.BlockStormCommand;
import dev.blockstorm.game.GameManager;
import dev.blockstorm.gui.MenuListener;
import dev.blockstorm.listener.GameListener;
import dev.blockstorm.listener.WandManager;
import dev.blockstorm.stats.StatsManager;
import dev.blockstorm.util.Messages;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class BlockStormPlugin extends JavaPlugin {

    private Messages messages;
    private ArenaManager arenas;
    private GameManager games;
    private StatsManager stats;
    private WandManager wand;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);
        messages.load();
        arenas = new ArenaManager(this);
        arenas.load();
        stats = new StatsManager(this);
        stats.load();
        games = new GameManager(this);
        wand = new WandManager(this);

        BlockStormCommand command = new BlockStormCommand(this);
        PluginCommand pc = Objects.requireNonNull(getCommand("blockstorm"));
        pc.setExecutor(command);
        pc.setTabCompleter(command);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new GameListener(this), this);
        pm.registerEvents(new MenuListener(this), this);
        pm.registerEvents(wand, this);

        getLogger().info("Loaded " + arenas.all().size() + " arena(s).");
    }

    @Override
    public void onDisable() {
        if (games != null) games.shutdown();
        if (stats != null) stats.saveNow();
    }

    /** Stops every game, then reloads config, language files and arenas. */
    public void reloadAll() {
        games.shutdown();
        reloadConfig();
        messages.load();
        arenas.load();
    }

    public Messages messages() { return messages; }
    public ArenaManager arenas() { return arenas; }
    public GameManager games() { return games; }
    public StatsManager stats() { return stats; }
    public WandManager wand() { return wand; }
}
