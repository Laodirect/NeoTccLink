package Lao_direct;

import org.bukkit.Bukkit;
import org.bukkit.ServerLinks;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class NeoTccLinkPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private static final List<String> ROOT_COMMANDS = List.of("reload", "list", "types", "help");

    private final List<ServerLinks.ServerLink> managedLinks = new ArrayList<>();
    private final Map<ServerLinks.Type, URI> overwrittenTypeLinks = new EnumMap<>(ServerLinks.Type.class);
    private final List<LoadedLink> loadedLinks = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        PluginCommand command = getCommand("neotcclink");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        } else {
            getLogger().warning("Command 'neotcclink' was not registered. Check plugin.yml.");
        }

        int count = applyLinks(null);
        getLogger().info("NeoTccLink enabled. Applied " + count + " Server Links.");
    }

    @Override
    public void onDisable() {
        removeManagedLinks(getConfig().getBoolean("settings.restore-overwritten-type-links-on-reload", false));
        loadedLinks.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!has(sender, "neotcclink.reload")) {
                    msg(sender, getMessage("no-permission", "§cYou do not have permission."));
                    return true;
                }
                reloadConfig();
                int count = applyLinks(sender);
                String text = getMessage("reload-success", "§aReloaded. Applied §e%count% §aServer Links.")
                        .replace("%count%", String.valueOf(count));
                msg(sender, text);
                return true;
            }
            case "list" -> {
                if (!has(sender, "neotcclink.list")) {
                    msg(sender, getMessage("no-permission", "§cYou do not have permission."));
                    return true;
                }
                sendList(sender);
                return true;
            }
            case "types" -> {
                if (!has(sender, "neotcclink.types")) {
                    msg(sender, getMessage("no-permission", "§cYou do not have permission."));
                    return true;
                }
                msg(sender, "§b可用内置类型: §f" + Arrays.stream(ServerLinks.Type.values())
                        .map(Enum::name)
                        .collect(Collectors.joining("§7, §f")));
                msg(sender, "§7自定义按钮请使用 type: CUSTOM，并填写 label。内置类型由客户端翻译显示名。");
                return true;
            }
            default -> {
                msg(sender, "§c未知子命令: §f" + args[0]);
                sendHelp(sender, label);
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return ROOT_COMMANDS.stream()
                    .filter(s -> s.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private int applyLinks(CommandSender feedbackTarget) {
        removeManagedLinks(getConfig().getBoolean("settings.restore-overwritten-type-links-on-reload", false));
        loadedLinks.clear();

        FileConfiguration config = getConfig();
        if (!config.getBoolean("settings.enabled", true)) {
            if (feedbackTarget != null) {
                msg(feedbackTarget, getMessage("disabled", "§ePlugin is disabled in config."));
            }
            getLogger().info("Plugin is disabled in config.yml; no Server Links were added.");
            return 0;
        }

        ServerLinks serverLinks = Bukkit.getServerLinks();
        boolean clearExisting = config.getBoolean("settings.clear-existing-server-links-before-apply", true);
        if (clearExisting) {
            clearExistingLinks(serverLinks, feedbackTarget);
        }

        boolean onlyHttp = config.getBoolean("settings.only-http-and-https", true);
        boolean logLoaded = config.getBoolean("settings.log-loaded-links", true);
        ConfigurationSection linksSection = config.getConfigurationSection("links");
        if (linksSection == null) {
            getLogger().warning("No 'links' section found in config.yml.");
            return 0;
        }

        int applied = 0;

        for (String key : linksSection.getKeys(false)) {
            ConfigurationSection section = linksSection.getConfigurationSection(key);
            if (section == null) {
                warn(feedbackTarget, "Link '" + key + "' is not a valid section. Skipped.");
                continue;
            }
            if (!section.getBoolean("enabled", true)) {
                continue;
            }

            String rawUrl = section.getString("url", "").trim();
            URI uri;
            try {
                uri = parseUri(rawUrl, onlyHttp);
            } catch (IllegalArgumentException ex) {
                warn(feedbackTarget, "Link '" + key + "' has invalid url: " + rawUrl + " (" + ex.getMessage() + ")");
                continue;
            }

            String typeName = section.getString("type", "CUSTOM").trim().toUpperCase(Locale.ROOT);
            try {
                ServerLinks.ServerLink added;
                if (typeName.equals("CUSTOM") || typeName.isEmpty()) {
                    String label = section.getString("label", key).trim();
                    if (label.isEmpty()) {
                        warn(feedbackTarget, "Link '" + key + "' has empty label. Skipped.");
                        continue;
                    }
                    added = serverLinks.addLink(label, uri);
                    managedLinks.add(added);
                    loadedLinks.add(new LoadedLink(key, "CUSTOM", label, uri.toString(), false));
                } else {
                    ServerLinks.Type type = ServerLinks.Type.valueOf(typeName);
                    boolean override = section.getBoolean("override-existing-type", true);
                    if (override) {
                        ServerLinks.ServerLink previous = serverLinks.getLink(type);
                        if (previous != null && !managedLinks.contains(previous)) {
                            overwrittenTypeLinks.putIfAbsent(type, previous.getUrl());
                        }
                        added = serverLinks.setLink(type, uri);
                    } else {
                        added = serverLinks.addLink(type, uri);
                    }
                    managedLinks.add(added);
                    loadedLinks.add(new LoadedLink(key, type.name(), type.name(), uri.toString(), override));
                }
                applied++;
                if (logLoaded) {
                    getLogger().info("Loaded link '" + key + "' -> " + uri);
                }
            } catch (IllegalArgumentException ex) {
                warn(feedbackTarget, "Link '" + key + "' has unknown type '" + typeName + "'. Use /neotcclink types.");
            } catch (Exception ex) {
                warn(feedbackTarget, "Failed to add link '" + key + "': " + ex.getMessage());
            }
        }

        return applied;
    }

    private void clearExistingLinks(ServerLinks serverLinks, CommandSender feedbackTarget) {
        int removed = 0;
        for (ServerLinks.ServerLink link : new ArrayList<>(serverLinks.getLinks())) {
            try {
                serverLinks.removeLink(link);
                managedLinks.remove(link);
                removed++;
            } catch (Exception ex) {
                warn(feedbackTarget, "Failed to remove existing Server Link: " + ex.getMessage());
            }
        }
        if (removed > 0 && getConfig().getBoolean("settings.log-loaded-links", true)) {
            getLogger().info("Removed " + removed + " existing Server Links before applying config.yml.");
        }
    }

    private void removeManagedLinks(boolean restoreOverwritten) {
        ServerLinks serverLinks = Bukkit.getServerLinks();
        for (ServerLinks.ServerLink link : new ArrayList<>(managedLinks)) {
            try {
                serverLinks.removeLink(link);
            } catch (Exception ex) {
                getLogger().warning("Failed to remove managed Server Link: " + ex.getMessage());
            }
        }
        managedLinks.clear();

        if (restoreOverwritten) {
            for (Map.Entry<ServerLinks.Type, URI> entry : overwrittenTypeLinks.entrySet()) {
                try {
                    if (serverLinks.getLink(entry.getKey()) == null) {
                        serverLinks.setLink(entry.getKey(), entry.getValue());
                    }
                } catch (Exception ex) {
                    getLogger().warning("Failed to restore overwritten Server Link type " + entry.getKey() + ": " + ex.getMessage());
                }
            }
        }
        overwrittenTypeLinks.clear();
    }

    private URI parseUri(String rawUrl, boolean onlyHttp) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("url is empty");
        }
        try {
            URI uri = new URI(rawUrl);
            String scheme = uri.getScheme();
            if (scheme == null || scheme.isBlank()) {
                throw new IllegalArgumentException("url must include a scheme, e.g. https://");
            }
            if (onlyHttp && !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("only http:// and https:// are allowed by config");
            }
            return uri;
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException(ex.getMessage());
        }
    }

    private void sendHelp(CommandSender sender, String label) {
        msg(sender, "§bNeoTccLink §7- Server Links 配置插件");
        msg(sender, "§f/" + label + " reload §7- 重载 config.yml 并重新应用链接");
        msg(sender, "§f/" + label + " list §7- 查看当前由本插件加载的链接");
        msg(sender, "§f/" + label + " types §7- 查看可用内置 Server Links 类型");
    }

    private void sendList(CommandSender sender) {
        if (loadedLinks.isEmpty()) {
            msg(sender, "§e当前没有由 NeoTccLink 加载的链接。");
            return;
        }
        msg(sender, "§b当前已加载的 Server Links: §e" + loadedLinks.size());
        for (LoadedLink link : loadedLinks) {
            msg(sender, "§7- §f" + link.key + " §8| §b" + link.type + " §8| §f" + link.url +
                    (link.override ? " §8(override)" : ""));
        }
    }

    private boolean has(CommandSender sender, String permission) {
        return sender.hasPermission(permission) || sender.hasPermission("neotcclink.admin");
    }

    private void msg(CommandSender sender, String text) {
        sender.sendMessage(getPrefix() + color(text));
    }

    private void warn(CommandSender sender, String text) {
        getLogger().warning(text);
        if (sender != null) {
            msg(sender, "§e" + text);
        }
    }

    private String getPrefix() {
        return color(getConfig().getString("messages.prefix", "§8[§bNeoTccLink§8] §r"));
    }

    private String getMessage(String path, String fallback) {
        return color(getConfig().getString("messages." + path, fallback));
    }

    private String color(String text) {
        return Objects.requireNonNullElse(text, "").replace('&', '§');
    }

    private record LoadedLink(String key, String type, String label, String url, boolean override) {
    }
}
