package dev.blockstorm.util;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.entity.Player;

/** Uses namespaced keys so it stays binary compatible across every 1.21.x build. */
public final class Sounds {

    private Sounds() {}

    public static void play(Player p, String key, float volume, float pitch) {
        p.playSound(Sound.sound(Key.key(key), Sound.Source.MASTER, volume, pitch));
    }
}
