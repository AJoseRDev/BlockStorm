package dev.blockstorm.command;

import dev.blockstorm.BlockStormPlugin;
import dev.blockstorm.arena.Arena;
import dev.blockstorm.game.Game;
import dev.blockstorm.game.GameState;
import dev.blockstorm.gui.ArenaMenu;
import dev.blockstorm.stats.StatsManager;
import dev.blockstorm.util.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static dev.blockstorm.util.Messages.ph;

public final class BlockStormCommand implements TabExecutor {

    private static final List<String> PLAYER_SUBS = List.of("help", "join", "leave", "list", "stats", "top");
    private static final List<String> ADMIN_SUBS = List.of("create", "delete", "wand", "setzone", "setlobby",
            "setspawn", "setmin", "setmax", "palette", "enable", "disable", "info", "forcestart", "forcestop", "reload");
    private static final List<String> ARENA_ARG_SUBS = List.of("delete", "setzone", "setlobby", "setspawn",
            "setmin", "setmax", "palette", "enable", "disable", "info", "forcestart", "forcestop");

    private final BlockStormPlugin plugin;

    public BlockStormCommand(BlockStormPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages msg() { return plugin.messages(); }

    private void send(CommandSender s, String key, TagResolver... r) { msg().send(s, key, r); }

    private void usage(CommandSender s, String usage) { send(s, "general.usage", ph("usage", usage)); }

    // ── dispatch ────────────────────────────────────────────
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(sender);
            case "join", "play" -> join(sender, args);
            case "leave", "quit" -> leave(sender);
            case "list" -> list(sender);
            case "stats" -> stats(sender, args);
            case "top" -> top(sender, args);
            default -> adminCommand(sender, sub, args);
        }
        return true;
    }

    private void help(CommandSender s) {
        msg().sendList(s, "help.player");
        if (s.hasPermission("blockstorm.admin")) msg().sendList(s, "help.admin");
    }

    // ── player commands ─────────────────────────────────────
    private void join(CommandSender s, String[] a) {
        Player p = player(s);
        if (p == null) return;
        if (!p.hasPermission("blockstorm.play")) {
            send(s, "general.no-permission");
            return;
        }
        if (a.length < 2) {
            if (plugin.getConfig().getBoolean("menu.enabled", true)) {
                if (plugin.arenas().all().isEmpty()) send(s, "general.no-arenas");
                else new ArenaMenu(plugin).open(p);
            } else {
                plugin.games().quickJoin(p);
            }
            return;
        }
        Arena arena = plugin.arenas().get(a[1]);
        if (arena == null) {
            send(s, "general.unknown-arena", ph("arena", a[1]));
            return;
        }
        plugin.games().join(p, arena);
    }

    private void leave(CommandSender s) {
        Player p = player(s);
        if (p != null) plugin.games().leave(p);
    }

    private void list(CommandSender s) {
        if (plugin.arenas().all().isEmpty()) {
            send(s, "general.no-arenas");
            return;
        }
        s.sendMessage(msg().component("list.header"));
        for (Arena a : plugin.arenas().all()) {
            Game g = plugin.games().peek(a);
            GameState st = g == null ? GameState.WAITING : g.state();
            boolean playable = a.isEnabled() && a.validate() == null;
            s.sendMessage(msg().component("list.line", ph("arena", a.getName()),
                    ph("players", g == null ? 0 : g.size()), ph("max", a.getMaxPlayers()),
                    Messages.comp("status", msg().component(playable
                            ? "status." + st.name().toLowerCase(Locale.ROOT) : "status.unavailable",
                            ph("countdown", g == null ? 0 : g.countdown())))));
        }
    }

    private void stats(CommandSender s, String[] a) {
        if (!s.hasPermission("blockstorm.stats")) {
            send(s, "general.no-permission");
            return;
        }
        UUID id;
        String name;
        if (a.length >= 2) {
            OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(a[1]);
            if (op == null) {
                send(s, "general.unknown-player", ph("player", a[1]));
                return;
            }
            id = op.getUniqueId();
            name = op.getName() == null ? a[1] : op.getName();
        } else {
            Player p = player(s);
            if (p == null) return;
            id = p.getUniqueId();
            name = p.getName();
        }
        StatsManager.PlayerStats ps = plugin.stats().get(id);
        if (ps == null) {
            send(s, "stats.none", ph("player", name));
            return;
        }
        msg().sendList(s, "stats.lines", ph("player", name), ph("games", ps.games), ph("wins", ps.wins),
                ph("best", fmt(ps.best)), ph("total", fmt((int) Math.min(Integer.MAX_VALUE, ps.total))));
    }

    private void top(CommandSender s, String[] a) {
        if (!s.hasPermission("blockstorm.stats")) {
            send(s, "general.no-permission");
            return;
        }
        String cat = a.length >= 2 ? a[1].toLowerCase(Locale.ROOT) : "best";
        if (!List.of("best", "wins", "games").contains(cat)) cat = "best";
        List<Map.Entry<UUID, StatsManager.PlayerStats>> top = plugin.stats().top(cat, 10);
        s.sendMessage(msg().component("top.header", Messages.comp("category", msg().component("top.category." + cat))));
        if (top.isEmpty()) {
            s.sendMessage(msg().component("top.empty"));
            return;
        }
        int pos = 1;
        for (Map.Entry<UUID, StatsManager.PlayerStats> e : top) {
            int value = StatsManager.valueOf(e.getValue(), cat);
            s.sendMessage(msg().component("top.line", ph("position", pos++), ph("player", e.getValue().name),
                    ph("value", cat.equals("best") ? fmt(value) : String.valueOf(value))));
        }
    }

    // ── admin commands ──────────────────────────────────────
    private void adminCommand(CommandSender s, String sub, String[] a) {
        if (!ADMIN_SUBS.contains(sub)) {
            help(s);
            return;
        }
        if (!s.hasPermission("blockstorm.admin")) {
            send(s, "general.no-permission");
            return;
        }
        switch (sub) {
            case "create" -> create(s, a);
            case "delete" -> delete(s, a);
            case "wand" -> wand(s);
            case "setzone" -> setZone(s, a);
            case "setlobby" -> setPoint(s, a, true);
            case "setspawn" -> setPoint(s, a, false);
            case "setmin" -> setPlayers(s, a, true);
            case "setmax" -> setPlayers(s, a, false);
            case "palette" -> palette(s, a);
            case "enable" -> toggle(s, a, true);
            case "disable" -> toggle(s, a, false);
            case "info" -> info(s, a);
            case "forcestart" -> forceStart(s, a);
            case "forcestop" -> forceStop(s, a);
            case "reload" -> {
                plugin.reloadAll();
                send(s, "general.reloaded");
            }
            default -> help(s);
        }
    }

    private void create(CommandSender s, String[] a) {
        if (a.length < 2) {
            usage(s, "/bs create <arena>");
            return;
        }
        if (!a[1].matches("[A-Za-z0-9_-]{1,24}")) {
            send(s, "admin.invalid-name");
            return;
        }
        if (plugin.arenas().get(a[1]) != null) {
            send(s, "admin.exists", ph("arena", a[1]));
            return;
        }
        plugin.arenas().create(a[1]);
        send(s, "admin.created", ph("arena", a[1]));
    }

    private void delete(CommandSender s, String[] a) {
        Arena arena = arena(s, a, 1, "/bs delete <arena>");
        if (arena == null) return;
        plugin.games().removeArena(arena);
        plugin.arenas().delete(arena.getName());
        send(s, "admin.deleted", ph("arena", arena.getName()));
    }

    private void wand(CommandSender s) {
        Player p = player(s);
        if (p == null) return;
        p.getInventory().addItem(plugin.wand().create());
        send(s, "admin.wand-given");
    }

    private void setZone(CommandSender s, String[] a) {
        Player p = player(s);
        if (p == null) return;
        Arena arena = arena(s, a, 1, "/bs setzone <arena>");
        if (arena == null || busy(s, arena)) return;
        Location[] sel = plugin.wand().selection(p.getUniqueId());
        if (sel == null || sel[0] == null || sel[1] == null) {
            send(s, "admin.zone-missing");
            return;
        }
        if (!sel[0].getWorld().equals(sel[1].getWorld())) {
            send(s, "admin.zone-world-mismatch");
            return;
        }
        arena.setZone(sel[0], sel[1]);
        plugin.arenas().save();
        send(s, "admin.zone-set", ph("arena", arena.getName()),
                ph("size", arena.width() + "x" + arena.height() + "x" + arena.depth()));
    }

    private void setPoint(CommandSender s, String[] a, boolean lobby) {
        Player p = player(s);
        if (p == null) return;
        Arena arena = arena(s, a, 1, lobby ? "/bs setlobby <arena>" : "/bs setspawn <arena>");
        if (arena == null || busy(s, arena)) return;
        Location loc = p.getLocation();
        if (lobby) {
            arena.setLobby(loc);
            send(s, "admin.lobby-set", ph("arena", arena.getName()));
        } else {
            if (arena.hasZone() && !arena.contains(loc)) {
                send(s, "admin.spawn-outside-zone");
                return;
            }
            arena.setSpawn(loc);
            send(s, "admin.spawn-set", ph("arena", arena.getName()));
        }
        plugin.arenas().save();
    }

    private void setPlayers(CommandSender s, String[] a, boolean min) {
        String usage = min ? "/bs setmin <arena> <amount>" : "/bs setmax <arena> <amount>";
        Arena arena = arena(s, a, 1, usage);
        if (arena == null || busy(s, arena)) return;
        if (a.length < 3) {
            usage(s, usage);
            return;
        }
        int v;
        try {
            v = Integer.parseInt(a[2]);
        } catch (NumberFormatException ex) {
            send(s, "general.invalid-number", ph("value", a[2]));
            return;
        }
        if (v < 1 || v > 100) {
            send(s, "general.invalid-number", ph("value", a[2]));
            return;
        }
        if (min) {
            arena.setMinPlayers(v);
            if (arena.getMaxPlayers() < v) arena.setMaxPlayers(v);
        } else {
            arena.setMaxPlayers(v);
            if (arena.getMinPlayers() > v) arena.setMinPlayers(v);
        }
        plugin.arenas().save();
        send(s, min ? "admin.min-set" : "admin.max-set", ph("arena", arena.getName()), ph("value", v));
    }

    private void palette(CommandSender s, String[] a) {
        Arena arena = arena(s, a, 1, "/bs palette <arena> <block> [block...]");
        if (arena == null || busy(s, arena)) return;
        if (a.length < 3) {
            usage(s, "/bs palette <arena> <block> [block...]");
            return;
        }
        List<Material> mats = new ArrayList<>();
        for (int i = 2; i < a.length; i++) {
            Material m = Material.matchMaterial(a[i]);
            if (m == null || !m.isBlock() || !m.isSolid()) {
                send(s, "admin.invalid-material", ph("value", a[i]));
                return;
            }
            if (!mats.contains(m)) mats.add(m);
        }
        arena.setPalette(mats);
        plugin.arenas().save();
        send(s, "admin.palette-set", ph("arena", arena.getName()), ph("count", mats.size()));
    }

    private void toggle(CommandSender s, String[] a, boolean enable) {
        Arena arena = arena(s, a, 1, enable ? "/bs enable <arena>" : "/bs disable <arena>");
        if (arena == null) return;
        if (!enable && busy(s, arena)) return;
        arena.setEnabled(enable);
        plugin.arenas().save();
        send(s, enable ? "admin.enabled" : "admin.disabled", ph("arena", arena.getName()));
    }

    private void info(CommandSender s, String[] a) {
        Arena arena = arena(s, a, 1, "/bs info <arena>");
        if (arena == null) return;
        String problem = arena.validate();
        Game g = plugin.games().peek(arena);
        GameState st = g == null ? GameState.WAITING : g.state();
        StringBuilder pal = new StringBuilder();
        for (Material m : arena.getPalette()) {
            if (pal.length() > 0) pal.append(", ");
            pal.append(m.name().toLowerCase(Locale.ROOT));
        }
        msg().sendList(s, "admin.info",
                ph("arena", arena.getName()),
                Messages.comp("status", msg().component("status." + st.name().toLowerCase(Locale.ROOT),
                        ph("countdown", g == null ? 0 : g.countdown()))),
                ph("size", arena.hasZone() ? arena.width() + "x" + arena.height() + "x" + arena.depth() : "-"),
                ph("min", arena.getMinPlayers()), ph("max", arena.getMaxPlayers()),
                ph("palette", pal.toString()),
                Messages.comp("problem", msg().component(problem == null ? "problem.none" : problem)));
    }

    private void forceStart(CommandSender s, String[] a) {
        Arena arena = arena(s, a, 1, "/bs forcestart <arena>");
        if (arena == null) return;
        Game g = plugin.games().peek(arena);
        if (g != null && g.forceStart()) send(s, "admin.force-started", ph("arena", arena.getName()));
        else send(s, "admin.cannot-start", ph("arena", arena.getName()));
    }

    private void forceStop(CommandSender s, String[] a) {
        Arena arena = arena(s, a, 1, "/bs forcestop <arena>");
        if (arena == null) return;
        Game g = plugin.games().peek(arena);
        if (g != null) g.forceStop();
        send(s, "admin.force-stopped", ph("arena", arena.getName()));
    }

    // ── helpers ─────────────────────────────────────────────
    private Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        send(s, "general.players-only");
        return null;
    }

    private Arena arena(CommandSender s, String[] a, int idx, String usage) {
        if (a.length <= idx) {
            usage(s, usage);
            return null;
        }
        Arena arena = plugin.arenas().get(a[idx]);
        if (arena == null) send(s, "general.unknown-arena", ph("arena", a[idx]));
        return arena;
    }

    private boolean busy(CommandSender s, Arena arena) {
        if (plugin.games().isBusy(arena)) {
            send(s, "general.arena-busy");
            return true;
        }
        return false;
    }

    private static String fmt(int secs) { return String.format("%d:%02d", secs / 60, secs % 60); }

    // ── tab completion ──────────────────────────────────────
    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        boolean admin = s.hasPermission("blockstorm.admin");
        if (a.length == 1) {
            List<String> subs = new ArrayList<>(PLAYER_SUBS);
            if (admin) subs.addAll(ADMIN_SUBS);
            return filter(subs, a[0]);
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 2) {
            if (sub.equals("join") || sub.equals("play") || (admin && ARENA_ARG_SUBS.contains(sub))) {
                return filter(plugin.arenas().names(), a[1]);
            }
            if (sub.equals("top")) return filter(List.of("best", "wins", "games"), a[1]);
            if (sub.equals("stats")) return null; // default: online player names
        }
        if (admin && sub.equals("palette") && a.length >= 3) {
            String prefix = a[a.length - 1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (Material m : Material.values()) {
                if (out.size() >= 40) break;
                if (m.isBlock() && m.isSolid() && !m.name().startsWith("LEGACY_")
                        && m.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(m.name().toLowerCase(Locale.ROOT));
                }
            }
            return out;
        }
        if (admin && (sub.equals("setmin") || sub.equals("setmax")) && a.length == 3) {
            return filter(List.of("1", "2", "4", "8", "10"), a[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }
}
