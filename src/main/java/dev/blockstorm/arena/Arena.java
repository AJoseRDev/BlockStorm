package dev.blockstorm.arena;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A BlockStorm arena. The zone is the cuboid of air where players move and
 * where blocks are dropped from (top layer = maxY). Locations are stored by
 * world name so arenas still load when a world is loaded after the plugin.
 */
public final class Arena {

    /** World-name based location that survives unloaded worlds. */
    public record Point(String world, double x, double y, double z, float yaw, float pitch) {
        public static Point of(Location l) {
            return new Point(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
        }

        public Location toLocation() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }

        public void save(ConfigurationSection s) {
            s.set("world", world);
            s.set("x", x);
            s.set("y", y);
            s.set("z", z);
            s.set("yaw", yaw);
            s.set("pitch", pitch);
        }

        public static Point load(ConfigurationSection s) {
            if (s == null) return null;
            String w = s.getString("world");
            if (w == null) return null;
            return new Point(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                    (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
        }
    }

    private final String name;
    private String worldName;
    private boolean zoneSet;
    private int minX, minY, minZ, maxX, maxY, maxZ;
    private Point lobby;
    private Point spawn;
    private int minPlayers = 2;
    private int maxPlayers = 10;
    private boolean enabled = true;
    private List<Material> palette = new ArrayList<>(List.of(
            Material.RED_CONCRETE, Material.ORANGE_CONCRETE, Material.YELLOW_CONCRETE,
            Material.LIME_CONCRETE, Material.LIGHT_BLUE_CONCRETE, Material.PURPLE_CONCRETE));

    public Arena(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public World getWorld() { return worldName == null ? null : Bukkit.getWorld(worldName); }

    // ── zone ────────────────────────────────────────────────
    public void setZone(Location a, Location b) {
        worldName = a.getWorld().getName();
        minX = Math.min(a.getBlockX(), b.getBlockX());
        minY = Math.min(a.getBlockY(), b.getBlockY());
        minZ = Math.min(a.getBlockZ(), b.getBlockZ());
        maxX = Math.max(a.getBlockX(), b.getBlockX());
        maxY = Math.max(a.getBlockY(), b.getBlockY());
        maxZ = Math.max(a.getBlockZ(), b.getBlockZ());
        zoneSet = true;
    }

    public boolean hasZone() { return zoneSet; }
    public int getMinX() { return minX; }
    public int getMinY() { return minY; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return maxX; }
    public int getMaxY() { return maxY; }
    public int getMaxZ() { return maxZ; }
    public int width() { return maxX - minX + 1; }
    public int height() { return maxY - minY + 1; }
    public int depth() { return maxZ - minZ + 1; }

    /** True when the location is inside the playable volume (with a small vertical tolerance). */
    public boolean contains(Location l) {
        if (!zoneSet || l.getWorld() == null || !l.getWorld().getName().equals(worldName)) return false;
        return l.getX() >= minX && l.getX() < maxX + 1
                && l.getZ() >= minZ && l.getZ() < maxZ + 1
                && l.getY() >= minY - 1 && l.getY() < maxY + 4;
    }

    // ── points ──────────────────────────────────────────────
    public Location getLobby() { return lobby == null ? null : lobby.toLocation(); }
    public Location getSpawn() { return spawn == null ? null : spawn.toLocation(); }
    public void setLobby(Location l) { lobby = Point.of(l); }
    public void setSpawn(Location l) { spawn = Point.of(l); }

    // ── settings ────────────────────────────────────────────
    public int getMinPlayers() { return minPlayers; }
    public int getMaxPlayers() { return maxPlayers; }
    public void setMinPlayers(int v) { minPlayers = v; }
    public void setMaxPlayers(int v) { maxPlayers = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public List<Material> getPalette() { return Collections.unmodifiableList(palette); }
    public void setPalette(List<Material> p) { palette = new ArrayList<>(p); }

    /** @return null when playable, otherwise the language key describing the problem. */
    public String validate() {
        if (!zoneSet || getWorld() == null) return "problem.zone";
        if (getLobby() == null) return "problem.lobby";
        Location s = getSpawn();
        if (s == null) return "problem.spawn";
        if (!contains(s)) return "problem.spawn-outside";
        if (palette.isEmpty()) return "problem.palette";
        return null;
    }

    // ── persistence ─────────────────────────────────────────
    public void save(ConfigurationSection s) {
        s.set("enabled", enabled);
        s.set("min-players", minPlayers);
        s.set("max-players", maxPlayers);
        s.set("palette", palette.stream().map(Material::name).toList());
        if (zoneSet) {
            s.set("zone.world", worldName);
            s.set("zone.min", List.of(minX, minY, minZ));
            s.set("zone.max", List.of(maxX, maxY, maxZ));
        }
        if (lobby != null) lobby.save(s.createSection("lobby"));
        if (spawn != null) spawn.save(s.createSection("spawn"));
    }

    public static Arena load(String name, ConfigurationSection s) {
        Arena a = new Arena(name);
        a.enabled = s.getBoolean("enabled", true);
        a.minPlayers = Math.max(1, s.getInt("min-players", 2));
        a.maxPlayers = Math.max(a.minPlayers, s.getInt("max-players", 10));
        List<Integer> mn = s.getIntegerList("zone.min");
        List<Integer> mx = s.getIntegerList("zone.max");
        String w = s.getString("zone.world");
        if (w != null && mn.size() == 3 && mx.size() == 3) {
            a.worldName = w;
            a.minX = mn.get(0); a.minY = mn.get(1); a.minZ = mn.get(2);
            a.maxX = mx.get(0); a.maxY = mx.get(1); a.maxZ = mx.get(2);
            a.zoneSet = true;
        }
        a.lobby = Point.load(s.getConfigurationSection("lobby"));
        a.spawn = Point.load(s.getConfigurationSection("spawn"));
        List<Material> pal = new ArrayList<>();
        for (String str : s.getStringList("palette")) {
            Material m = Material.matchMaterial(str);
            if (m != null && m.isBlock() && m.isSolid()) pal.add(m);
        }
        if (!pal.isEmpty()) a.palette = pal;
        return a;
    }
}
