package dev.blockstorm.util;

import dev.blockstorm.BlockStormPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** MiniMessage based, file driven messages with automatic fallback to the bundled English file. */
public final class Messages {

    private final BlockStormPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private YamlConfiguration lang = new YamlConfiguration();
    private YamlConfiguration fallback = new YamlConfiguration();

    public Messages(BlockStormPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File dir = new File(plugin.getDataFolder(), "lang");
        for (String bundled : List.of("en", "es")) {
            if (!new File(dir, bundled + ".yml").exists()) plugin.saveResource("lang/" + bundled + ".yml", false);
        }
        String code = plugin.getConfig().getString("language", "en").toLowerCase(Locale.ROOT);
        File f = new File(dir, code + ".yml");
        lang = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
        try (InputStream in = plugin.getResource("lang/en.yml")) {
            if (in != null) {
                try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    fallback = YamlConfiguration.loadConfiguration(r);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read bundled language file: " + e.getMessage());
        }
    }

    private String raw(String key) {
        String s = lang.getString(key);
        if (s == null) s = fallback.getString(key);
        return s == null ? "<red>Missing message: " + key : s;
    }

    public Component component(String key, TagResolver... resolvers) {
        return mm.deserialize(raw(key), resolvers);
    }

    public Component prefixed(String key, TagResolver... resolvers) {
        return mm.deserialize(raw("prefix") + raw(key), resolvers);
    }

    public List<Component> list(String key, TagResolver... resolvers) {
        List<String> raw = lang.getStringList(key);
        if (raw.isEmpty()) raw = fallback.getStringList(key);
        List<Component> out = new ArrayList<>(raw.size());
        for (String s : raw) out.add(mm.deserialize(s, resolvers));
        return out;
    }

    public void send(CommandSender to, String key, TagResolver... resolvers) {
        to.sendMessage(prefixed(key, resolvers));
    }

    public void sendList(CommandSender to, String key, TagResolver... resolvers) {
        for (Component c : list(key, resolvers)) to.sendMessage(c);
    }

    public static TagResolver ph(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    public static TagResolver comp(String name, Component value) {
        return Placeholder.component(name, value);
    }
}
