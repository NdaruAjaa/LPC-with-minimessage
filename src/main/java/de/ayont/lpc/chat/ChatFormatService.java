package de.ayont.lpc.chat;

import de.ayont.lpc.LPC;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.track.Track;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Resolves and renders chat lines for both the Paper and Spigot listeners.
 */
public final class ChatFormatService {

    public static final String DEFAULT_FORMAT = "{prefix}{name}<dark_gray> »<reset> {message}";

    private static final String MESSAGE_TOKEN = "{message}";
    private static final String MESSAGE_TAG_PREFIX = "lpcmsg";
    private static final String STYLE_INNER_TAG = "lpcstyled";
    private static final String DEFAULT_GROUP = "default";
    private static final Pattern GRADIENT_SPEC = Pattern.compile("[#a-zA-Z0-9:,_-]+");

    private final LPC plugin;
    private final LuckPerms luckPerms;
    private final MiniMessage trustedMiniMessage;

    private volatile String chatFormat;
    private volatile Map<String, String> groupFormats;
    private volatile Map<String, String> trackFormats;
    private volatile MiniMessage colorParser;
    private volatile boolean hasPapi;
    private volatile boolean messageStylesEnabled;
    private volatile Map<String, String> groupMessageStyles;
    private volatile Map<String, String> trackMessageStyles;
    private volatile String defaultMessageStyle;
    private volatile boolean gradientNamesEnabled;
    private volatile String gradientMetaKey;
    private volatile Map<String, String> groupNameGradients;

    public ChatFormatService(LPC plugin) {
        this.plugin = plugin;
        this.luckPerms = LuckPermsProvider.get();
        this.trustedMiniMessage = MiniMessage.miniMessage();
        reload();
    }

    public void reload() {
        FileConfiguration config = plugin.getConfig();
        this.chatFormat = config.getString("chat-format", DEFAULT_FORMAT);
        this.groupFormats = readStringSection(config, "group-formats");
        this.trackFormats = readStringSection(config, "track-formats");
        this.colorParser = PlayerMessages.colorParser(config.getBoolean("allow-gradient-tags", true));
        this.hasPapi = plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null;
        this.messageStylesEnabled = config.getBoolean("message-styles.enabled", false);
        this.groupMessageStyles = readStringSection(config, "group-message-styles");
        this.trackMessageStyles = readStringSection(config, "track-message-styles");
        this.defaultMessageStyle = config.getString("default-message-style", "");
        this.gradientNamesEnabled = config.getBoolean("gradient-names.enabled", false);
        this.gradientMetaKey = config.getString("gradient-names.default-meta-key", "username-gradient");
        this.groupNameGradients = readStringSection(config, "group-name-gradients");
    }

    private static Map<String, String> readStringSection(FileConfiguration config, String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return Map.of();
        }
        Map<String, String> values = new HashMap<>();
        for (String key : section.getKeys(false)) {
            String value = section.getString(key);
            if (value != null) {
                values.put(key, value);
            }
        }
        return Map.copyOf(values);
    }

    public Component messageComponent(String raw, boolean allowColor) {
        return PlayerMessages.render(colorParser, raw, allowColor);
    }

    public Component render(Player source, Component message, Component displayName) {
        CachedMetaData metaData = luckPerms.getPlayerAdapter(Player.class).getMetaData(source);
        String group = metaData.getPrimaryGroup();
        if (group == null) {
            group = DEFAULT_GROUP;
        }

        Component safeDisplayName = displayName != null ? displayName : Component.text(source.getName());
        Component styledMessage = applyMessageStyle(group, message);

        String messageTag = MESSAGE_TAG_PREFIX + UUID.randomUUID().toString().replace("-", "");
        String format = resolveFormat(group).replace(MESSAGE_TOKEN, "<" + messageTag + ">");

        format = applyMetaTokens(format, source, metaData, safeDisplayName);

        if (hasPapi) {
            format = PlaceholderAPI.setPlaceholders(source, format);
        }

        return trustedMiniMessage.deserialize(format, Placeholder.component(messageTag, styledMessage));
    }

    public Component renderTemplate(Player player, String template, Component displayName, TagResolver... extra) {
        CachedMetaData metaData = luckPerms.getPlayerAdapter(Player.class).getMetaData(player);
        Component safeDisplayName = displayName != null ? displayName : Component.text(player.getName());
        String format = applyMetaTokens(template, player, metaData, safeDisplayName);
        if (hasPapi) {
            format = PlaceholderAPI.setPlaceholders(player, format);
        }
        return trustedMiniMessage.deserialize(format, extra);
    }

    private Component applyMessageStyle(String group, Component message) {
        if (!messageStylesEnabled) {
            return message;
        }
        String style = resolveMessageStyle(group);
        if (style == null || style.isEmpty()) {
            return message;
        }
        String wrapped = style.replace(MESSAGE_TOKEN, "<" + STYLE_INNER_TAG + ">");
        return trustedMiniMessage.deserialize(wrapped, Placeholder.component(STYLE_INNER_TAG, message));
    }

    private String resolveMessageStyle(String group) {
        String style = groupMessageStyles.get(group);
        if (style == null) {
            style = resolveByTrack(trackMessageStyles, group);
        }
        if (style == null) {
            style = defaultMessageStyle;
        }
        return style;
    }

    private String resolveFormat(String group) {
        String format = groupFormats.get(group);
        if (format == null) {
            format = resolveByTrack(trackFormats, group);
        }
        if (format == null) {
            format = chatFormat;
        }
        return format == null || format.isEmpty() ? DEFAULT_FORMAT : format;
    }

    private String resolveByTrack(Map<String, String> byTrack, String group) {
        for (Map.Entry<String, String> entry : byTrack.entrySet()) {
            Track track = luckPerms.getTrackManager().getTrack(entry.getKey());
            if (track != null && track.containsGroup(group)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String applyMetaTokens(String format, Player source, CachedMetaData metaData, Component displayName) {
        return format
                .replace("{prefix}", translateLegacy(orEmpty(metaData.getPrefix())))
                .replace("{suffix}", translateLegacy(orEmpty(metaData.getSuffix())))
                .replace("{prefixes}", translateLegacy(String.join(" ", metaData.getPrefixes().values())))
                .replace("{suffixes}", translateLegacy(String.join(" ", metaData.getSuffixes().values())))
                .replace("{world}", source.getWorld().getName())
                .replace("{gradient-name}", gradientName(source, metaData))
                .replace("{name}", source.getName())
                .replace("{displayname}", trustedMiniMessage.serialize(displayName))
                .replace("{username-color}", translateLegacy(orEmpty(metaData.getMetaValue("username-color"))))
                .replace("{message-color}", translateLegacy(orEmpty(metaData.getMetaValue("message-color"))));
    }

    private String gradientName(Player source, CachedMetaData metaData) {
        if (!gradientNamesEnabled) {
            return source.getName();
        }
        String spec = metaData.getMetaValue(gradientMetaKey);
        if (spec == null || spec.isBlank()) {
            String group = metaData.getPrimaryGroup();
            if (group != null) {
                spec = groupNameGradients.get(group);
            }
        }
        if (spec == null || spec.isBlank() || !GRADIENT_SPEC.matcher(spec).matches()) {
            return source.getName();
        }
        return "<gradient:" + spec + ">" + source.getName() + "</gradient>";
    }

    private String translateLegacy(String input) {
        if (input == null || input.isEmpty()) return "";
        return trustedMiniMessage.serialize(LegacyComponentSerializer.legacyAmpersand().deserialize(input));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}