package dev.blockstorm.game;

import dev.blockstorm.BlockStormPlugin;
import dev.blockstorm.arena.Arena;
import dev.blockstorm.util.Messages;
import dev.blockstorm.util.Sidebar;
import dev.blockstorm.util.Sounds;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static dev.blockstorm.util.Messages.ph;

/**
 * One running instance of an arena: lobby, countdown, the block storm, elimination and cleanup.
 *
 * Only blocks that this game placed are removed when the round ends, so decoration
 * inside the zone is never touched (no WorldEdit required).
 */
public final class Game {

    private final BlockStormPlugin plugin;
    private final Arena arena;

    private GameState state = GameState.WAITING;
    private final Map<UUID, PlayerSnapshot> participants = new LinkedHashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Set<UUID> alive = new LinkedHashSet<>();
    private final Map<UUID, Integer> survived = new HashMap<>();
    private final Set<UUID> falling = new HashSet<>();
    private final Set<UUID> struck = new HashSet<>();
    private final Set<Block> landed = new HashSet<>();
    private final Map<UUID, Sidebar> boards = new HashMap<>();
    private final BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);

    private BukkitTask task;
    private int countdown;
    private long ticks;
    private int spawnCooldown;
    private int initialPlayers;
    private int lastLevel;

    public Game(BlockStormPlugin plugin, Arena arena) {
        this.plugin = plugin;
        this.arena = arena;
    }

    // ── accessors ───────────────────────────────────────────
    public GameState state() { return state; }
    public Arena arena() { return arena; }
    public int size() { return participants.size(); }
    public boolean isParticipant(UUID id) { return participants.containsKey(id); }
    public boolean isAlive(UUID id) { return alive.contains(id); }
    public boolean ownsFalling(UUID id) { return falling.contains(id); }
    public int countdown() { return countdown; }

    private FileConfiguration cfg() { return plugin.getConfig(); }
    private Messages msg() { return plugin.messages(); }
    private int effectiveMin() { return Math.max(1, arena.getMinPlayers()); }
    private int level() {
        return (int) ((ticks / 20) / Math.max(1, cfg().getInt("difficulty.ramp-seconds", 10)));
    }

    // ── joining / leaving ───────────────────────────────────
    /** @return null on success, otherwise a language key explaining the refusal. */
    public String join(Player p) {
        if (!arena.isEnabled()) return "error.arena-disabled";
        if (arena.validate() != null) return "error.arena-not-ready";
        if (state == GameState.RUNNING || state == GameState.ENDING) return "error.arena-in-progress";
        if (participants.size() >= arena.getMaxPlayers()) return "error.arena-full";

        UUID id = p.getUniqueId();
        PlayerSnapshot snap = PlayerSnapshot.capture(p);
        plugin.games().saveSnapshot(id, snap);
        participants.put(id, snap);
        names.put(id, p.getName());
        prepare(p);
        p.teleport(arena.getLobby());
        p.showBossBar(bar);
        Sidebar sidebar = new Sidebar(msg().component("sidebar.title"));
        boards.put(id, sidebar);
        p.setScoreboard(sidebar.board());
        Sounds.play(p, "entity.player.levelup", 0.6f, 1.4f);

        broadcast("game.joined", ph("player", p.getName()), ph("count", participants.size()),
                ph("max", arena.getMaxPlayers()));

        if (state == GameState.WAITING && cfg().getBoolean("game.autostart", true)
                && participants.size() >= effectiveMin()) {
            startCountdown();
        } else if (state == GameState.COUNTDOWN && participants.size() >= arena.getMaxPlayers() && countdown > 5) {
            countdown = 5;
        }
        refresh();
        return null;
    }

    public void leave(UUID id, boolean notify) {
        if (!participants.containsKey(id)) return;
        String name = names.getOrDefault(id, "?");
        if (state == GameState.RUNNING && alive.remove(id)) {
            int secs = (int) (ticks / 20);
            survived.put(id, secs);
            plugin.stats().recordGame(id, name, secs, false);
        }
        detach(id);
        participants.remove(id);
        boards.remove(id);
        if (notify) broadcast("game.left", ph("player", name));

        switch (state) {
            case COUNTDOWN -> {
                if (participants.size() < effectiveMin()) {
                    cancelTask();
                    state = GameState.WAITING;
                    broadcast("game.countdown-cancelled");
                }
            }
            case RUNNING -> {
                if (participants.isEmpty()) forceStop();
                else checkEnd();
            }
            default -> { }
        }
        refresh();
    }

    /** Puts a player into lobby condition. */
    @SuppressWarnings("deprecation")
    private void prepare(Player p) {
        p.getInventory().clear();
        for (var e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        p.setGameMode(GameMode.ADVENTURE);
        p.setAllowFlight(false);
        p.setFlying(false);
        p.setHealth(Math.min(20.0, p.getMaxHealth()));
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setLevel(0);
        p.setExp(0f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
    }

    /** Returns the player's saved state and removes the plugin's visuals from them. */
    private void detach(UUID id) {
        PlayerSnapshot snap = participants.get(id);
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            p.hideBossBar(bar);
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            if (snap != null) snap.restore(p, cfg().getBoolean("game.teleport-back", true));
        }
        plugin.games().deleteSnapshot(id);
    }

    // ── lifecycle ───────────────────────────────────────────
    private void startCountdown() {
        cancelTask();
        state = GameState.COUNTDOWN;
        countdown = Math.max(1, cfg().getInt("game.countdown-seconds", 15));
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (countdown <= 0) {
                startRound();
                return;
            }
            for (UUID id : participants.keySet()) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                if (countdown <= 5) {
                    title(p, "game.countdown-title", "game.countdown-subtitle", ph("seconds", countdown));
                    Sounds.play(p, "block.note_block.pling", 1f, 1f + (5 - countdown) * 0.1f);
                } else {
                    p.sendActionBar(msg().component("game.countdown", ph("seconds", countdown)));
                }
            }
            countdown--;
            refresh();
        }, 0L, 20L);
    }

    /** Skips the countdown. @return false if the game cannot be started right now. */
    public boolean forceStart() {
        if ((state != GameState.WAITING && state != GameState.COUNTDOWN) || participants.isEmpty()) return false;
        startRound();
        return true;
    }

    private void startRound() {
        cancelTask();
        state = GameState.RUNNING;
        initialPlayers = participants.size();
        alive.clear();
        alive.addAll(participants.keySet());
        survived.clear();
        ticks = 0;
        spawnCooldown = 20;
        lastLevel = 0;
        for (UUID id : participants.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.teleport(spreadSpawn());
            title(p, "game.start-title", "game.start-subtitle");
            Sounds.play(p, "entity.ender_dragon.growl", 0.6f, 1.2f);
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        refresh();
    }

    private void tick() {
        ticks++;
        if (--spawnCooldown <= 0) {
            spawnWave();
            spawnCooldown = currentInterval();
        }
        checkHits();
        if (ticks % 20 == 0) onSecond();
    }

    private int currentInterval() {
        int init = cfg().getInt("difficulty.initial-interval-ticks", 24);
        int min = Math.max(1, cfg().getInt("difficulty.min-interval-ticks", 4));
        int step = cfg().getInt("difficulty.interval-step-ticks", 2);
        return Math.max(min, init - level() * step);
    }

    private void spawnWave() {
        int every = Math.max(1, cfg().getInt("difficulty.extra-block-every-levels", 3));
        int count = Math.min(Math.max(1, cfg().getInt("difficulty.max-blocks-per-wave", 5)), 1 + level() / every);
        List<Player> targets = alivePlayers();
        for (int i = 0; i < count; i++) spawnOne(targets);
    }

    private void spawnOne(List<Player> targets) {
        World w = arena.getWorld();
        if (w == null) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int x;
        int z;
        if (!targets.isEmpty() && rnd.nextDouble() < cfg().getDouble("difficulty.target-chance", 0.25)) {
            Location t = targets.get(rnd.nextInt(targets.size())).getLocation();
            x = clamp(t.getBlockX() + rnd.nextInt(-1, 2), arena.getMinX(), arena.getMaxX());
            z = clamp(t.getBlockZ() + rnd.nextInt(-1, 2), arena.getMinZ(), arena.getMaxZ());
        } else {
            x = rnd.nextInt(arena.getMinX(), arena.getMaxX() + 1);
            z = rnd.nextInt(arena.getMinZ(), arena.getMaxZ() + 1);
        }
        Block top = w.getBlockAt(x, arena.getMaxY(), z);
        if (!top.getType().isAir()) return; // column is full
        List<Material> palette = arena.getPalette();
        Material m = palette.get(rnd.nextInt(palette.size()));
        FallingBlock fb = w.spawnFallingBlock(top.getLocation().add(0.5, 0, 0.5), m.createBlockData());
        fb.setDropItem(false);
        fb.setHurtEntities(false); // hits are handled by checkHits() for consistent behaviour
        fb.setPersistent(false);
        falling.add(fb.getUniqueId());
    }

    /** Mid-air collision: a falling block that touches a living player hurts them once. */
    private void checkHits() {
        if (falling.isEmpty() || alive.isEmpty()) return;
        double damage = cfg().getDouble("difficulty.hit-damage", 3.0);
        if (damage <= 0) return;
        List<Player> players = alivePlayers();
        Iterator<UUID> it = falling.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            Entity e = Bukkit.getEntity(id);
            if (e == null || !e.isValid()) {
                it.remove();
                struck.remove(id);
                continue;
            }
            if (struck.contains(id)) continue;
            BoundingBox box = e.getBoundingBox();
            for (Player p : players) {
                if (!alive.contains(p.getUniqueId())) continue;
                if (box.overlaps(p.getBoundingBox())) {
                    struck.add(id);
                    p.damage(damage);
                    Sounds.play(p, "entity.player.hurt", 1f, 0.8f);
                }
            }
        }
    }

    /** Called by the listener when one of this game's falling blocks turns into a real block. */
    public void onLand(UUID entityId, Block block) {
        falling.remove(entityId);
        struck.remove(entityId);
        landed.add(block);
        if (state != GameState.RUNNING) return;
        BoundingBox box = BoundingBox.of(block);
        List<Player> trapped = new ArrayList<>();
        for (Player p : alivePlayers()) if (p.getBoundingBox().overlaps(box)) trapped.add(p);
        if (trapped.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player p : trapped) handleCrushed(p, block);
        });
    }

    private void handleCrushed(Player p, Block block) {
        if (state != GameState.RUNNING || !alive.contains(p.getUniqueId())) return;
        if (cfg().getBoolean("game.crush-eliminates", false)) {
            eliminate(p, "crushed");
            return;
        }
        Location up = p.getLocation();
        up.setY(block.getY() + 1.0);
        Block feet = up.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        if (block.getY() + 2 > arena.getMaxY() || !feet.isPassable() || !head.isPassable()) {
            eliminate(p, "crushed");
            return;
        }
        p.teleport(up);
        Sounds.play(p, "block.anvil.land", 0.5f, 1.3f);
    }

    private void onSecond() {
        int secs = (int) (ticks / 20);
        int lvl = level();
        if (lvl > lastLevel) {
            lastLevel = lvl;
            for (Player p : alivePlayers()) {
                p.sendActionBar(msg().component("game.level-up", ph("level", lvl + 1)));
                Sounds.play(p, "entity.experience_orb.pickup", 0.8f, 0.7f);
            }
        }
        refresh();
        int max = cfg().getInt("game.max-duration-seconds", 0);
        if (max > 0 && secs >= max) endGame();
    }

    // ── elimination & end ───────────────────────────────────
    public void eliminate(Player p, String cause) {
        UUID id = p.getUniqueId();
        if (state != GameState.RUNNING || !alive.remove(id)) return;
        int secs = (int) (ticks / 20);
        survived.put(id, secs);
        plugin.stats().recordGame(id, p.getName(), secs, false);

        p.setGameMode(GameMode.SPECTATOR);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.teleport(spectatorSpot());
        title(p, "game.eliminated-title", "game.eliminated-subtitle", ph("time", secs));
        Sounds.play(p, "entity.wither.hurt", 0.7f, 1f);
        broadcast("game.eliminated." + cause, ph("player", p.getName()), ph("time", secs));
        refresh();
        checkEnd();
    }

    public void outOfBounds(Player p) {
        if (cfg().getBoolean("game.out-of-bounds-eliminates", true)) eliminate(p, "out-of-bounds");
        else Bukkit.getScheduler().runTask(plugin, () -> p.teleport(spreadSpawn()));
    }

    private void checkEnd() {
        if (state != GameState.RUNNING) return;
        boolean over = initialPlayers > 1 ? alive.size() <= 1 : alive.isEmpty();
        if (over) endGame();
    }

    private void endGame() {
        if (state != GameState.RUNNING) return;
        cancelTask();
        state = GameState.ENDING;
        int secs = (int) (ticks / 20);
        boolean multi = initialPlayers > 1;
        List<UUID> winners = new ArrayList<>(alive);
        for (UUID id : winners) {
            survived.put(id, secs);
            plugin.stats().recordGame(id, names.getOrDefault(id, "?"), secs, multi);
        }
        for (UUID id : winners) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && multi) {
                title(p, "game.victory-title", "game.victory-subtitle", ph("time", secs));
                Sounds.play(p, "ui.toast.challenge_complete", 1f, 1f);
            }
        }
        announceResults(winners, multi, secs);
        runRewards(winners, multi, secs);
        refresh();
        long delay = Math.max(1, cfg().getInt("game.end-delay-seconds", 6)) * 20L;
        task = Bukkit.getScheduler().runTaskLater(plugin, this::cleanup, delay);
    }

    private void announceResults(List<UUID> winners, boolean multi, int secs) {
        if (!multi) {
            broadcast("game.solo-over", ph("time", secs));
            return;
        }
        if (winners.size() == 1) {
            broadcast("game.winner", ph("player", names.getOrDefault(winners.get(0), "?")), ph("time", secs));
        } else {
            broadcast("game.draw", ph("time", secs));
        }
        broadcast("game.results-header");
        List<Map.Entry<UUID, Integer>> sorted = new ArrayList<>(survived.entrySet());
        sorted.sort(Map.Entry.<UUID, Integer>comparingByValue().reversed());
        int pos = 1;
        for (Map.Entry<UUID, Integer> e : sorted) {
            if (pos > 3) break;
            broadcast("game.results-line", ph("position", pos),
                    ph("player", names.getOrDefault(e.getKey(), "?")), ph("time", e.getValue()));
            pos++;
        }
    }

    private void runRewards(List<UUID> winners, boolean multi, int secs) {
        for (UUID id : participants.keySet()) {
            String name = names.getOrDefault(id, "?");
            int mine = survived.getOrDefault(id, secs);
            dispatch("rewards.participation", name, mine);
            if (multi && winners.contains(id)) dispatch("rewards.winner", name, mine);
        }
    }

    private void dispatch(String path, String player, int seconds) {
        for (String c : cfg().getStringList(path)) {
            String cmd = c.replace("%player%", player)
                    .replace("%arena%", arena.getName())
                    .replace("%seconds%", String.valueOf(seconds));
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            if (!cmd.isBlank()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }
    }

    /** Stops immediately (admin command, reload, shutdown) and returns everybody. */
    public void forceStop() {
        cancelTask();
        cleanup();
    }

    private void cleanup() {
        cancelTask();
        resetArena();
        for (UUID id : new ArrayList<>(participants.keySet())) detach(id);
        participants.clear();
        boards.clear();
        alive.clear();
        survived.clear();
        names.clear();
        ticks = 0;
        countdown = 0;
        state = GameState.WAITING;
    }

    /** Removes only the blocks this game placed and any block still falling. */
    private void resetArena() {
        World w = arena.getWorld();
        if (w != null && arena.hasZone()) {
            BoundingBox zone = BoundingBox.of(
                    new Vector(arena.getMinX(), arena.getMinY() - 1, arena.getMinZ()),
                    new Vector(arena.getMaxX() + 1, arena.getMaxY() + 2, arena.getMaxZ() + 1));
            w.getNearbyEntities(zone, e -> e instanceof FallingBlock).forEach(Entity::remove);
        }
        falling.clear();
        struck.clear();
        for (Block b : landed) b.setType(Material.AIR, false);
        landed.clear();
    }

    // ── helpers ─────────────────────────────────────────────
    private void cancelTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private List<Player> alivePlayers() {
        List<Player> l = new ArrayList<>();
        for (UUID id : alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) l.add(p);
        }
        return l;
    }

    private Location spreadSpawn() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Location base = arena.getSpawn();
        double x = Math.max(arena.getMinX() + 0.5, Math.min(arena.getMaxX() + 0.5, base.getX() + rnd.nextDouble(-2, 2)));
        double z = Math.max(arena.getMinZ() + 0.5, Math.min(arena.getMaxZ() + 0.5, base.getZ() + rnd.nextDouble(-2, 2)));
        return new Location(base.getWorld(), x, base.getY(), z, base.getYaw(), base.getPitch());
    }

    private Location spectatorSpot() {
        return new Location(arena.getWorld(),
                (arena.getMinX() + arena.getMaxX() + 1) / 2.0, arena.getMaxY() + 2,
                (arena.getMinZ() + arena.getMaxZ() + 1) / 2.0);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static String formatTime(int secs) { return String.format("%d:%02d", secs / 60, secs % 60); }

    private void broadcast(String key, TagResolver... r) {
        for (UUID id : participants.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) msg().send(p, key, r);
        }
    }

    private void title(Player p, String titleKey, String subKey, TagResolver... r) {
        p.showTitle(Title.title(msg().component(titleKey, r), msg().component(subKey, r),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(900), Duration.ofMillis(250))));
    }

    /** Updates sidebars and the boss bar for everyone in the game. */
    private void refresh() {
        int secs = (int) (ticks / 20);
        int ramp = Math.max(1, cfg().getInt("difficulty.ramp-seconds", 10));
        String stateKey = state.name().toLowerCase(Locale.ROOT);
        TagResolver[] base = {
                ph("arena", arena.getName()), ph("players", participants.size()), ph("max", arena.getMaxPlayers()),
                ph("min", effectiveMin()), ph("alive", alive.size()), ph("time", formatTime(secs)),
                ph("level", level() + 1), ph("countdown", countdown)
        };
        
        TagResolver[] r = new TagResolver[base.length + 1];
        System.arraycopy(base, 0, r, 0, base.length);
        r[base.length] = Messages.comp("status", msg().component("status." + stateKey, base));
        
        List<Component> lines = msg().list(
                state == GameState.RUNNING || state == GameState.ENDING ? "sidebar.running" : "sidebar.waiting", r);
        for (Sidebar sb : boards.values()) sb.setLines(lines);

        bar.name(msg().component("bossbar." + stateKey, r));
        float progress = switch (state) {
            case COUNTDOWN -> countdown / (float) Math.max(1, cfg().getInt("game.countdown-seconds", 15));
            case RUNNING -> (secs % ramp) / (float) ramp;
            default -> 1f;
        };
        bar.progress(Math.max(0f, Math.min(1f, progress)));
        bar.color(state == GameState.RUNNING ? BossBar.Color.RED : BossBar.Color.YELLOW);
    }
}
