package com.elmakers.mine.bukkit.magic.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import com.elmakers.mine.bukkit.api.spell.SpellTemplate;
import com.elmakers.mine.bukkit.automata.Automaton;
import com.elmakers.mine.bukkit.automata.AutomatonTemplate;
import com.elmakers.mine.bukkit.automata.Nearby;
import com.elmakers.mine.bukkit.magic.MagicController;
import com.elmakers.mine.bukkit.spell.BaseSpell;
import com.elmakers.mine.bukkit.utility.ConfigurationUtils;
import com.elmakers.mine.bukkit.utility.InventoryUtils;
import com.elmakers.mine.bukkit.utility.TextUtils;
import com.google.common.collect.ImmutableSet;

public class MagicAutomataCommandExecutor extends MagicTabExecutor {
    private final MagicController magicController;
    private final AutomatonSelectionManager selections;

    private static final ImmutableSet<String> PROPERTY_KEYS = ImmutableSet.of(
        "name", "interval", "effects",
        "spawn.mobs", "spawn.probability", "spawn.player_range", "spawn.min_players",
        "spawn.limit", "spawn.limit_range", "spawn.vertical_range", "spawn.radius",
        "spawn.vertical_radius", "spawn.retries", "min_players", "player_range",
        "min_time", "max_time", "min_moon_phase", "max_moon_phase", "moon_phase",
        "cast.spells", "cast.recast", "cast.undo_all", "spawn.count", "spawn.leash",
        "spawn.interval", "spawn.parameters"
    );
    private static final ImmutableSet<String> IGNORE_PROPERTIES = ImmutableSet.of("name", "description");

    public MagicAutomataCommandExecutor(MagicController controller) {
        super(controller.getAPI(), "mauto");
        selections = new AutomatonSelectionManager(controller);
        this.magicController = controller;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!api.hasPermission(sender, getPermissionNode())) {
            sendNoPermission(sender);
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "Usage: mauto [add|select|remove|list|configure|describe|name]");
            return true;
        }

        String subCommand = args[0];
        args = Arrays.copyOfRange(args, 1, args.length);
        Automaton selection = selections.getSelected(sender);

        if (subCommand.equalsIgnoreCase("list")) {
            onListAutomata(sender, args);
            return true;
        }

        if (subCommand.equalsIgnoreCase("select")) {
            onSelectAutomata(sender, args);
            return true;
        }

        if (subCommand.equalsIgnoreCase("remove")) {
            onRemoveAutomata(sender, selection);
            return true;
        }

        if (subCommand.equalsIgnoreCase("tp")) {
            onTPAutomata(sender, selection);
            return true;
        }

        if (subCommand.equalsIgnoreCase("move") || subCommand.equalsIgnoreCase("tphere")) {
            onMoveAutomata(sender, selection);
            return true;
        }

        if (subCommand.equalsIgnoreCase("describe")) {
            onDescribeAutomata(sender, selection);
            return true;
        }

        if (subCommand.equalsIgnoreCase("configure")) {
            onConfigureAutomata(sender, selection, args);
            return true;
        }

        if (subCommand.equalsIgnoreCase("name")) {
            onNameAutomata(sender, selection, args);
            return true;
        }

        Player player = (sender instanceof Player) ? (Player)sender : null;
        if (player == null) {
            sender.sendMessage(ChatColor.RED + "This command can only be used in-game");
            return true;
        }

        if (subCommand.equalsIgnoreCase("add")) {
            onAddAutomata(player, args);
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Usage: mauto [add|select|remove|list|configure|describe]");
        return true;
    }

    private void onListAutomata(CommandSender sender, String[] args) {
        selections.list(sender, args);
    }

    private void onAddAutomata(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(ChatColor.RED + "Usage: " + ChatColor.WHITE + "/mauto add <template>");
            return;
        }

        String key = args[0];
        if (!magicController.isAutomataTemplate(key)) {
            player.sendMessage(ChatColor.RED + "Invalid automata template: " + ChatColor.DARK_RED + key);
            return;
        }

        Location location = player.getLocation();
        Automaton existing = magicController.getAutomatonAt(location);
        if (existing != null) {
            player.sendMessage(ChatColor.RED + "Automata already exists: " + ChatColor.LIGHT_PURPLE + existing.getName()
                + ChatColor.RED + " at " + TextUtils.printLocation(existing.getLocation(), 0));
            return;
        }

        ConfigurationSection parameters = null;
        if (args.length > 1) {
            String[] parameterArgs = Arrays.copyOfRange(args, 1, args.length);
            parameters = new MemoryConfiguration();
            ConfigurationUtils.addParameters(parameterArgs, parameters);
        }
        Automaton automaton = new Automaton(magicController, location, key, player.getUniqueId().toString(), player.getName(), parameters);
        magicController.registerAutomaton(automaton);

        playEffects(player, automaton, "blockselect");
        selections.setSelection(player, automaton);

        player.sendMessage(ChatColor.AQUA + "Created automaton: " + ChatColor.LIGHT_PURPLE + automaton.getName()
            + ChatColor.AQUA + " at " + TextUtils.printLocation(automaton.getLocation(), 0));
    }

    private void playEffects(CommandSender sender, Automaton automaton, String effectsKey) {
        selections.playEffects(sender, automaton, effectsKey);
    }

    private void onRemoveAutomata(CommandSender sender, Automaton automaton) {
        if (automaton == null) {
            sender.sendMessage(ChatColor.RED + "No automata selected, use " + ChatColor.WHITE + "/mauto select");
            return;
        }
        if (!magicController.unregisterAutomaton(automaton)) {
            sender.sendMessage(ChatColor.RED + "Could not find automata at given position (something went wrong)");
            return;
        }

        Location location = automaton.getLocation();
        selections.clearSelection(sender);

        playEffects(sender, automaton, "blockremove");
        String rangeMessage = selections.getDistanceMessage(sender, automaton);
        String message = ChatColor.YELLOW + "Removed " + ChatColor.LIGHT_PURPLE + automaton.getName()
            + ChatColor.YELLOW + " at " + TextUtils.printLocation(location, 0);
        if (rangeMessage != null) {
            message += rangeMessage;
        }
        sender.sendMessage(message);
    }

    private void onTPAutomata(CommandSender sender, Automaton automaton) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command may only be used in-game");
            return;
        }
        if (automaton == null) {
            sender.sendMessage(ChatColor.RED + "No automata selected, use " + ChatColor.WHITE + "/mauto select");
            return;
        }

        Player player = (Player)sender;
        Location location = automaton.getLocation();
        player.teleport(automaton.getLocation());
        String message = ChatColor.YELLOW + "Teleported you to " + ChatColor.LIGHT_PURPLE + automaton.getName()
            + ChatColor.YELLOW + " at " + TextUtils.printLocation(location, 0);
        sender.sendMessage(message);
    }

    private void onMoveAutomata(CommandSender sender, Automaton automaton) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command may only be used in-game");
            return;
        }
        if (automaton == null) {
            sender.sendMessage(ChatColor.RED + "No automata selected, use " + ChatColor.WHITE + "/mauto select");
            return;
        }

        Player player = (Player)sender;
        Location location = player.getLocation();
        Automaton existing = magicController.getAutomatonAt(location);
        if (existing != null) {
            player.sendMessage(ChatColor.RED + "Automata already exists: " + ChatColor.LIGHT_PURPLE + existing.getName()
                + ChatColor.RED + " at " + TextUtils.printLocation(existing.getLocation(), 0));
            return;
        }

        magicController.moveAutomaton(automaton, location);
        String message = ChatColor.YELLOW + "Moved " + ChatColor.LIGHT_PURPLE + automaton.getName()
            + ChatColor.YELLOW + " to " + TextUtils.printLocation(location, 0);
        sender.sendMessage(message);
    }

    private void onDescribeAutomata(CommandSender sender, Automaton automaton) {
        if (automaton == null) {
            sender.sendMessage(ChatColor.RED + "No automata selected, use " + ChatColor.WHITE + "/mauto select");
            return;
        }

        Location location = automaton.getLocation();

        playEffects(sender, automaton, "blockselect");
        String message = ChatColor.LIGHT_PURPLE + automaton.getName()
            + ChatColor.GREEN + " at " + TextUtils.printLocation(location, 0)
            + selections.getDistanceMessage(sender, automaton);
        sender.sendMessage(message);
        String description = automaton.getDescription();
        if (description != null) {
            sender.sendMessage(ChatColor.DARK_PURPLE + "" + ChatColor.ITALIC + description);
        }
        if (automaton.hasSpawner()) {
            Nearby nearby = automaton.getNearby();
            if (nearby != null) {
                int limit = automaton.getSpawnLimit();
                sender.sendMessage(ChatColor.GOLD + "Has " + ChatColor.GREEN + nearby.mobs
                    + ChatColor.GRAY + "/" + ChatColor.DARK_GREEN + limit
                    + ChatColor.GOLD + " active mobs");
                int minPlayers = automaton.getSpawnMinPlayers();
                sender.sendMessage(ChatColor.GOLD + "Has " + ChatColor.GREEN + nearby.players
                    + ChatColor.GRAY + "/" + ChatColor.DARK_GREEN + minPlayers
                    + ChatColor.GOLD + " nearby players");
            }

            long timeToNextSpawn = automaton.getTimeToNextSpawn();
            sender.sendMessage(ChatColor.GOLD + "Time to next spawn: " + controller.getMessages().getTimeDescription(timeToNextSpawn, "description", "cooldown"));
        }

        String creatorName = automaton.getCreatorName();
        creatorName = (creatorName == null || creatorName.isEmpty()) ? ChatColor.YELLOW + "(Unknown)" : ChatColor.GREEN + creatorName;
        sender.sendMessage(ChatColor.DARK_GREEN + "  Created by: " + creatorName);
        ConfigurationSection parameters = automaton.getParameters();
        Set<String> parameterKeys = null;
        if (parameters == null) {
            sender.sendMessage(ChatColor.YELLOW + "(No Parameters)");
        } else {
            parameterKeys = parameters.getKeys(true);
            sender.sendMessage(ChatColor.DARK_AQUA + "Has " + ChatColor.AQUA + parameterKeys.size()
                + ChatColor.DARK_AQUA + " Parameters");
            for (String key : parameterKeys) {
                if (IGNORE_PROPERTIES.contains(key)) continue;
                Object property = parameters.get(key);
                if (!(property instanceof ConfigurationSection)) {
                    sender.sendMessage(ChatColor.AQUA + key + ChatColor.GRAY + ": "
                        + ChatColor.DARK_AQUA + InventoryUtils.describeProperty(property));
                }
            }
        }
        AutomatonTemplate template = automaton.getTemplate();
        if (template != null) {
            ConfigurationSection templateParameters = template.getConfiguration();
            Set<String> keys = templateParameters.getKeys(true);
            for (String key : keys) {
                if (IGNORE_PROPERTIES.contains(key)) continue;
                if (parameterKeys != null && parameterKeys.contains(key)) continue;
                Object property = templateParameters.get(key);
                if (!(property instanceof ConfigurationSection)) {
                    sender.sendMessage(ChatColor.GRAY + key + ChatColor.DARK_GRAY + ": "
                        + ChatColor.DARK_AQUA + InventoryUtils.describeProperty(property));
                }
            }
        }
    }

    private void onNameAutomata(CommandSender sender, Automaton automaton, String[] args) {
        if (automaton == null) {
            sender.sendMessage(ChatColor.RED + "No automata selected, use " + ChatColor.WHITE + "/mauto select");
            return;
        }

        if (args.length == 0) {
            automaton.setName(null);
            sender.sendMessage(ChatColor.GREEN + "Cleared custom name for " + ChatColor.WHITE + automaton.getName());
            return;
        }

        String currentName = automaton.getName();
        automaton.setName(StringUtils.join(args, " "));
        sender.sendMessage(ChatColor.GREEN + "Renamed " + ChatColor.WHITE + currentName + ChatColor.GRAY + " to " + ChatColor.AQUA + automaton.getName());
    }

    private void onConfigureAutomata(CommandSender sender, Automaton automaton, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "Usage: " + ChatColor.WHITE + "/mauto configure <property> [value]");
            return;
        }

        if (automaton == null) {
            sender.sendMessage(ChatColor.RED + "No automata selected, use " + ChatColor.WHITE + "/mauto select");
            return;
        }

        String key = args[0];
        ConfigurationSection parameters = automaton.getParameters();
        if (args.length == 1 && (parameters == null || !parameters.contains(key))) {
            sender.sendMessage(ChatColor.RED + "Automata does not have a " + ChatColor.WHITE + key + ChatColor.RED + "parameter");
            return;
        }

        Location location = automaton.getLocation();

        selections.playEffects(sender, automaton, "blockselect");
        String message = ChatColor.GREEN + "Configured " + ChatColor.LIGHT_PURPLE + automaton.getName()
            + ChatColor.GREEN + " at " + TextUtils.printLocation(location, 0)
            + selections.getDistanceMessage(sender, automaton);
        sender.sendMessage(message);

        boolean isActive = magicController.isActive(automaton);
        if (isActive) {
            automaton.pause();
        }
        if (args.length == 1) {
            parameters.set(key, null);
            sender.sendMessage(ChatColor.YELLOW + "Removed property: " + ChatColor.AQUA + key);
        } else {
            if (parameters == null) {
                parameters = new MemoryConfiguration();
            }
            ConfigurationUtils.set(parameters, key, args[1]);
            Object value = parameters.get(key);
            automaton.setParameters(parameters);
            sender.sendMessage(ChatColor.DARK_AQUA + "Set property: " + ChatColor.AQUA + key
                + ChatColor.DARK_AQUA + " to " + ChatColor.WHITE + InventoryUtils.describeProperty(value));
        }

        automaton.reload();
        if (isActive) {
            automaton.resume();
        }
    }

    private void onSelectAutomata(CommandSender sender, String[] args) {
        List<Automaton> list = selections.getList(sender);
        if (list == null || args.length == 0) {
            if (sender instanceof Player) {
                List<Automaton> selection = selections.updateList(sender).getList();
                if (selection != null && !selection.isEmpty()) {
                    Automaton automaton = selection.get(0);
                    selections.playEffects(sender, automaton, "blockselect");
                    String message = ChatColor.GREEN + "Selected nearby " + ChatColor.LIGHT_PURPLE + automaton.getName()
                        + ChatColor.GREEN + " at " + TextUtils.printLocation(automaton.getLocation(), 0)
                        + selections.getDistanceMessage(sender, automaton);
                    sender.sendMessage(message);
                    return;
                }
            }

            sender.sendMessage(ChatColor.RED + "Nothing to select, Use " + ChatColor.WHITE + "/mauto list");
            return;
        }

        int index = 1;
        if (args.length > 0) {
            try {
                index = Integer.parseInt(args[0]);
            } catch (Exception ex) {
                sender.sendMessage(ChatColor.RED + "Invalid index: " + ChatColor.WHITE + args[0]);
                return;
            }
        }

        if (index <= 0 || index > list.size()) {
            sender.sendMessage(ChatColor.RED + "Index out of range: " + ChatColor.WHITE + args[0]
                + ChatColor.GRAY + "/" + ChatColor.WHITE + list.size());
            return;
        }

        Automaton automaton = list.get(index - 1);
        selections.setSelection(sender, automaton);
        Location location = automaton.getLocation();

        selections.playEffects(sender, automaton, "blockselect");
        String message = ChatColor.GREEN + "Selected " + ChatColor.LIGHT_PURPLE + automaton.getName()
            + ChatColor.GREEN + " at " + TextUtils.printLocation(location, 0)
            + selections.getDistanceMessage(sender, automaton);
        sender.sendMessage(message);
    }

    @Override
    public Collection<String> onTabComplete(CommandSender sender, String commandName, String[] args) {
        List<String> options = new ArrayList<>();
        String subCommand = args[0];
        String property = "";
        boolean isConfigure = args.length >= 3 && subCommand.equalsIgnoreCase("add");
        if (subCommand.equalsIgnoreCase("configure") && args.length >= 2) {
            isConfigure = true;
        }
        if (isConfigure) {
            property = args[args.length - 2];
        }

        if (!sender.hasPermission("Magic.commands.mauto")) return options;
        if (args.length == 1) {
            options.add("add");
            options.add("list");
            options.add("remove");
            options.add("select");
            options.add("configure");
            options.add("describe");
            options.add("name");
            options.add("tp");
            options.add("move");
        } else if (args.length == 2 && subCommand.equalsIgnoreCase("add")) {
            options.addAll(magicController.getAutomatonTemplateKeys());
        } else if (isConfigure) {
            switch (property) {
                case "spawn.mobs":
                    options.addAll(api.getController().getMobKeys());
                    for (EntityType entityType : EntityType.values()) {
                        if (entityType.isAlive() && entityType.isSpawnable()) {
                            options.add(entityType.name().toLowerCase());
                        }
                    }
                    break;
                case "cast.spells":
                    Collection<SpellTemplate> spells = api.getController().getSpellTemplates();
                    for (SpellTemplate spell : spells) {
                        options.add(spell.getKey());
                    }
                    break;
                case "cast.undo_all":
                case "cast.leash":
                case "cast.recast":
                    options.addAll(Arrays.asList(BaseSpell.EXAMPLE_BOOLEANS));
                    break;
                case "spawn.interval":
                case "interval":
                    options.addAll(Arrays.asList(BaseSpell.EXAMPLE_DURATIONS));
                    break;
                case "effects":
                    options.addAll(magicController.getEffectKeys());
                    break;
                case "max_moon_phase":
                case "min_moon_phase":
                case "moon_phase":
                    options.add("full");
                    options.add("new");
                    for (int i = 0; i < 8; i++) {
                        options.add(Integer.toString(i));
                    }
                    break;
                case "max_time":
                case "min_time":
                    options.add("dawn");
                    options.add("day");
                    options.add("noon");
                    options.add("dusk");
                    options.add("night");
                    options.add("midnight");
                    options.add("0");
                    options.add("6000");
                    options.add("12000");
                    options.add("18000");
                    break;
                case "spawn.radius":
                case "spawn.vertical_radius":
                case "spawn.vertical_range":
                case "spawn.limit":
                case "spawn.count":
                case "spawn.limit_range":
                case "spawn.player_range":
                case "spawn.retries":
                case "spawn.min_players":
                case "player_range":
                case "min_players":
                    options.addAll(Arrays.asList(BaseSpell.EXAMPLE_SIZES));
                    break;
                case "spawn.probability":
                    options.addAll(Arrays.asList(BaseSpell.EXAMPLE_PERCENTAGES));
                    break;
                default:
                    if (!PROPERTY_KEYS.contains(property)) {
                        options.addAll(PROPERTY_KEYS);
                    }
            }
        }
        return options;
    }
}
