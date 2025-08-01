package com.maks.playerdataplugin;

import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;

/**
 * Saves the first join kit when the editor inventory is closed.
 */
public class FirstJoinKitListener implements Listener {

    private final FirstJoinKitManager kitManager;

    public FirstJoinKitListener(FirstJoinKitManager kitManager) {
        this.kitManager = kitManager;
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getView().getTitle().equals(FirstJoinKitManager.INVENTORY_TITLE)) {
            kitManager.saveKit(event.getInventory().getContents());
            event.getPlayer().sendMessage(ChatColor.GREEN + "First join kit saved.");
        }
    }
}
