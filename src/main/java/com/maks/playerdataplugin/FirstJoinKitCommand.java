package com.maks.playerdataplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Command to open the editor for the first join kit.
 */
public class FirstJoinKitCommand implements CommandExecutor {

    private final FirstJoinKitManager kitManager;

    public FirstJoinKitCommand(FirstJoinKitManager kitManager) {
        this.kitManager = kitManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;
        if (!player.hasPermission("playerdataplugin.firstjoinkit")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to edit the first join kit.");
            return true;
        }

        Inventory inv = Bukkit.createInventory(null, 54, FirstJoinKitManager.INVENTORY_TITLE);
        ItemStack[] kit = kitManager.loadKit();
        if (kit != null && kit.length > 0) {
            inv.setContents(kit);
        }
        player.openInventory(inv);
        player.sendMessage(ChatColor.YELLOW + "Edit the kit and close the inventory to save.");
        return true;
    }
}
