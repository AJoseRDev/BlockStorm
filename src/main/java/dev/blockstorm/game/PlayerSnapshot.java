package dev.blockstorm.game;

import dev.blockstorm.arena.Arena;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Everything needed to give a player back exactly what they had before joining.
 * It is also written to disk, so a crash mid-game never costs anyone their inventory.
 */
public final class PlayerSnapshot {

    private final ItemStack[] contents;
    private final Arena.Point origin;
    private final GameMode gameMode;
    private final double health;
    private final int food;
    private final float saturation;
    private final int level;
    private final float exp;
    private final boolean allowFlight;
    private final List<PotionEffect> effects;

    private PlayerSnapshot(ItemStack[] contents, Arena.Point origin, GameMode gameMode, double health, int food,
                           float saturation, int level, float exp, boolean allowFlight, List<PotionEffect> effects) {
        this.contents = contents;
        this.origin = origin;
        this.gameMode = gameMode;
        this.health = health;
        this.food = food;
        this.saturation = saturation;
        this.level = level;
        this.exp = exp;
        this.allowFlight = allowFlight;
        this.effects = effects;
    }

    public static PlayerSnapshot capture(Player p) {
        ItemStack[] src = p.getInventory().getContents();
        ItemStack[] copy = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) copy[i] = src[i] == null ? null : src[i].clone();
        return new PlayerSnapshot(copy, Arena.Point.of(p.getLocation()), p.getGameMode(), p.getHealth(),
                p.getFoodLevel(), p.getSaturation(), p.getLevel(), p.getExp(), p.getAllowFlight(),
                new ArrayList<>(p.getActivePotionEffects()));
    }

    @SuppressWarnings("deprecation")
    public void restore(Player p, boolean teleport) {
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        p.setGameMode(gameMode);
        p.getInventory().clear();
        p.getInventory().setContents(contents);
        p.setLevel(level);
        p.setExp(exp);
        p.setFoodLevel(food);
        p.setSaturation(saturation);
        p.setAllowFlight(allowFlight);
        p.addPotionEffects(effects);
        p.setHealth(Math.max(0.5, Math.min(health, p.getMaxHealth())));
        p.setFireTicks(0);
        p.setFallDistance(0f);
        if (teleport && origin != null) {
            Location l = origin.toLocation();
            if (l != null) p.teleport(l);
        }
    }

    public void save(File f) throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.set("contents", Arrays.asList(contents));
        if (origin != null) origin.save(y.createSection("origin"));
        y.set("gamemode", gameMode.name());
        y.set("health", health);
        y.set("food", food);
        y.set("saturation", (double) saturation);
        y.set("level", level);
        y.set("exp", (double) exp);
        y.set("allow-flight", allowFlight);
        y.set("effects", effects);
        f.getParentFile().mkdirs();
        y.save(f);
    }

    /** @return the snapshot, or null if the file is unreadable. */
    public static PlayerSnapshot load(File f) {
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            List<?> raw = y.getList("contents", List.of());
            ItemStack[] items = new ItemStack[raw.size()];
            for (int i = 0; i < items.length; i++) items[i] = raw.get(i) instanceof ItemStack s ? s : null;
            List<PotionEffect> eff = new ArrayList<>();
            for (Object o : y.getList("effects", List.of())) if (o instanceof PotionEffect pe) eff.add(pe);
            GameMode gm;
            try {
                gm = GameMode.valueOf(y.getString("gamemode", "SURVIVAL"));
            } catch (IllegalArgumentException ex) {
                gm = GameMode.SURVIVAL;
            }
            return new PlayerSnapshot(items, Arena.Point.load(y.getConfigurationSection("origin")), gm,
                    y.getDouble("health", 20.0), y.getInt("food", 20), (float) y.getDouble("saturation", 5.0),
                    y.getInt("level"), (float) y.getDouble("exp"), y.getBoolean("allow-flight"), eff);
        } catch (Exception e) {
            return null;
        }
    }
}
