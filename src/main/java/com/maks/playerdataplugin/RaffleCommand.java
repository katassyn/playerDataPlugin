package com.maks.playerdataplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RaffleCommand implements CommandExecutor {

    private final Main plugin;

    public RaffleCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players!");
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("playerdataplugin.raffle")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use this command!");
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(ChatColor.RED + "Usage: /raffle <number_of_winners>");
            return true;
        }

        int numberOfWinners;
        try {
            numberOfWinners = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "Please enter a valid number!");
            return true;
        }

        if (numberOfWinners <= 0) {
            player.sendMessage(ChatColor.RED + "Number of winners must be greater than 0!");
            return true;
        }

        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (itemInHand == null || itemInHand.getType() == Material.AIR) {
            player.sendMessage(ChatColor.RED + "You must hold an item in your hand to raffle!");
            return true;
        }

        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        onlinePlayers.remove(player);

        if (onlinePlayers.isEmpty()) {
            player.sendMessage(ChatColor.RED + "No other players online to participate in the raffle!");
            return true;
        }

        if (numberOfWinners > onlinePlayers.size()) {
            player.sendMessage(ChatColor.RED + "Number of winners (" + numberOfWinners + 
                ") cannot be greater than number of online players (" + onlinePlayers.size() + ")!");
            return true;
        }

        Collections.shuffle(onlinePlayers);
        List<Player> winners = onlinePlayers.subList(0, numberOfWinners);

        String itemName = getItemDisplayName(itemInHand);
        int itemAmount = itemInHand.getAmount();

        for (Player winner : winners) {
            ItemStack prize = itemInHand.clone();
            winner.getInventory().addItem(prize);
        }

        String announcement = ChatColor.GOLD + "🎉 RAFFLE RESULTS 🎉\n" +
            ChatColor.YELLOW + "Host: " + ChatColor.WHITE + player.getName() + "\n" +
            ChatColor.YELLOW + "Prize: " + ChatColor.WHITE + itemAmount + "x " + itemName + "\n" +
            ChatColor.YELLOW + "Winners: " + ChatColor.GREEN;

        List<String> winnerNames = new ArrayList<>();
        for (Player winner : winners) {
            winnerNames.add(winner.getName());
        }
        announcement += String.join(", ", winnerNames);

        Bukkit.broadcastMessage(announcement);

        for (Player winner : winners) {
            winner.sendMessage(ChatColor.GREEN + "🎉 Congratulations! You won " + itemAmount + "x " + 
                itemName + " from " + player.getName() + "'s raffle!");
        }

        player.sendMessage(ChatColor.GREEN + "Successfully raffled " + itemAmount + "x " + itemName + 
            " to " + numberOfWinners + " players!");

        return true;
    }

    private String getItemDisplayName(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().getDisplayName();
        }
        
        String materialName = item.getType().name().toLowerCase().replace('_', ' ');
        return capitalizeWords(materialName);
    }

    private String capitalizeWords(String str) {
        String[] words = str.split(" ");
        StringBuilder result = new StringBuilder();
        
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                result.append(" ");
            }
            if (!words[i].isEmpty()) {
                result.append(Character.toUpperCase(words[i].charAt(0)))
                      .append(words[i].substring(1));
            }
        }
        
        return result.toString();
    }
}