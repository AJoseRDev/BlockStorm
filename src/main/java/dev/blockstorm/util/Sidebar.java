package dev.blockstorm.util;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.List;

/** Flicker-free sidebar built on teams (no line length limit, no score numbers shown). */
public final class Sidebar {

    private static final int MAX_LINES = 15;

    private final Scoreboard board;
    private final Objective objective;
    private int shown;

    public Sidebar(Component title) {
        board = Bukkit.getScoreboardManager().getNewScoreboard();
        objective = board.registerNewObjective("blockstorm", Criteria.DUMMY, title);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.numberFormat(NumberFormat.blank());
    }

    public Scoreboard board() { return board; }

    private static String entry(int i) {
        return "\u00a7" + Integer.toHexString(i) + "\u00a7r";
    }

    public void setLines(List<Component> lines) {
        int n = Math.min(lines.size(), MAX_LINES);
        for (int i = 0; i < n; i++) {
            String entry = entry(i);
            Team t = board.getTeam("l" + i);
            if (t == null) {
                t = board.registerNewTeam("l" + i);
                t.addEntry(entry);
            }
            t.prefix(lines.get(i));
            objective.getScore(entry).setScore(n - i);
        }
        for (int i = n; i < shown; i++) board.resetScores(entry(i));
        shown = n;
    }
}
