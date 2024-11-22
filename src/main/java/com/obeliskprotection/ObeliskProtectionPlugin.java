package com.obeliskprotection;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayUtil;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Arrays;
import lombok.Getter;
import com.google.common.collect.ImmutableSet;
import java.util.Set;

@Slf4j
@PluginDescriptor(
    name = "Obelisk Protection",
    description = "Protects players from accidentally using their POH obelisk when carrying valuable items",
    tags = {"wilderness", "obelisk", "protection", "poh"}
)
public class ObeliskProtectionPlugin extends Plugin
{
    @Inject
    private Client client;

    @Inject
    private ItemManager itemManager;

    @Inject
    private ObeliskProtectionConfig config;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private ObeliskProtectionGroundOverlay groundOverlay;

    @Getter
    private boolean protectionActive = false;

    @Getter
    private LocalPoint obeliskLocation = null;

    // POH Wilderness Obelisk object ID
    public static final int POH_OBELISK_ID = 31554;

    private static final int[] WILDERNESS_OBELISK_IDS = {
        14826, 14827, 14828, 14829, 14830, 14831  // Wilderness obelisks to ignore
    };

    private static final Set<Integer> POH_REGIONS = ImmutableSet.of(7257, 7513, 7514, 7769, 7770, 8025, 8026);

    @Override
    protected void startUp()
    {
        log.info("Obelisk Protection started!");
        overlayManager.add(groundOverlay);
    }

    @Override
    protected void shutDown()
    {
        log.info("Obelisk Protection stopped!");
        overlayManager.remove(groundOverlay);
        protectionActive = false;
        obeliskLocation = null;
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event)
    {
        // Debug all menu entries and current state
        log.debug("Menu Entry - Option: '{}', Target: '{}', ID: {}, Type: {}, Protection Active: {}, Location: {}", 
            event.getOption(), 
            event.getTarget(),
            event.getIdentifier(),
            event.getType(),
            protectionActive, 
            obeliskLocation);

        if (!isInPOH())
        {
            log.debug("Not in POH");
            protectionActive = false;
            obeliskLocation = null;
            return;
        }

        // Get all current menu entries to check context
        MenuEntry[] currentEntries = client.getMenuEntries();
        log.debug("All menu entries: {}", Arrays.toString(currentEntries));

        // Check if this is an obelisk interaction - more lenient check
        String target = event.getTarget().toLowerCase();
        if (!target.contains("obelisk") && !target.contains("wilderness portal"))
        {
            // Only clear protection if we're not processing an obelisk-related entry
            if (currentEntries.length == 1) {
                log.debug("Not an obelisk target and no other entries: '{}'", target);
                protectionActive = false;
                obeliskLocation = null;
            }
            return;
        }

        // Check if this is a POH obelisk by checking the object ID
        GameObject obelisk = findObelisk();
        if (obelisk == null)
        {
            log.debug("Could not find obelisk object");
            protectionActive = false;
            obeliskLocation = null;
            return;
        }
        
        if (obelisk.getId() != POH_OBELISK_ID)
        {
            log.debug("Found obelisk but wrong ID: {}", obelisk.getId());
            protectionActive = false;
            obeliskLocation = null;
            return;
        }

        // Calculate risk value regardless of menu option
        int riskValue = calculateRiskValue();
        log.debug("Risk check - Value: {}, Threshold: {}", riskValue, config.wealthThreshold());
        
        if (riskValue > config.wealthThreshold())
        {
            log.debug("Setting protection active - Risk value {} exceeds threshold {}", 
                riskValue, config.wealthThreshold());
            protectionActive = true;
            obeliskLocation = obelisk.getLocalLocation();
            log.debug("Obelisk location set to: {}", obeliskLocation);
            
            // Only remove menu entries for teleport-related options
            String option = event.getOption().toLowerCase();
            if (shouldBlockOption(option))
            {
                // Remove the current entry
                MenuEntry[] updatedEntries = client.getMenuEntries();
                if (updatedEntries.length > 0)
                {
                    client.setMenuEntries(Arrays.copyOf(updatedEntries, updatedEntries.length - 1));
                    log.debug("Removed menu option: '{}'", option);
                }
            }
            else
            {
                log.debug("Option '{}' not blocked", option);
            }
        }
        else
        {
            log.debug("Protection not active - Risk value {} below threshold {}", 
                riskValue, config.wealthThreshold());
            protectionActive = false;
            obeliskLocation = null;
        }
    }

    private boolean shouldBlockOption(String option)
    {
        if (option == null)
        {
            return false;
        }

        option = option.toLowerCase();
        return option.equals("teleport to destination") ||
               option.equals("activate") ||
               option.equals("set destination");
    }

    private int calculateRiskValue()
    {
        ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
        ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
        
        List<Integer> protectedValues = new ArrayList<>();
        int totalRiskValue = 0;
        
        if (inventory != null)
        {
            for (Item item : inventory.getItems())
            {
                if (item.getId() != -1)
                {
                    int gePrice = itemManager.getItemPrice(item.getId());
                    ItemComposition itemComp = itemManager.getItemComposition(item.getId());
                    
                    if (itemComp.isStackable() && item.getQuantity() > 3)
                    {
                        // For stackable items, treat the whole stack minus 3 as risk
                        totalRiskValue += gePrice * (item.getQuantity() - 3);
                        // Add single item value to protected values list
                        protectedValues.add(gePrice);
                    }
                    else
                    {
                        // For non-stackable items, add full value to protected values list
                        protectedValues.add(gePrice * item.getQuantity());
                    }
                    
                    log.debug("Inventory item: {} x{} = {}", 
                        itemComp.getName(),
                        item.getQuantity(), 
                        gePrice * item.getQuantity());
                }
            }
        }
        
        if (equipment != null)
        {
            for (Item item : equipment.getItems())
            {
                if (item.getId() != -1)
                {
                    int gePrice = itemManager.getItemPrice(item.getId());
                    ItemComposition itemComp = itemManager.getItemComposition(item.getId());
                    
                    if (itemComp.isStackable() && item.getQuantity() > 3)
                    {
                        // For stackable items, treat the whole stack minus 3 as risk
                        totalRiskValue += gePrice * (item.getQuantity() - 3);
                        // Add single item value to protected values list
                        protectedValues.add(gePrice);
                    }
                    else
                    {
                        // For non-stackable items, add full value to protected values list
                        protectedValues.add(gePrice * item.getQuantity());
                    }
                    
                    log.debug("Equipment item: {} x{} = {}", 
                        itemComp.getName(),
                        item.getQuantity(), 
                        gePrice * item.getQuantity());
                }
            }
        }
        
        // Sort protected values to find the 3 most valuable items
        Collections.sort(protectedValues, Collections.reverseOrder());
        
        // Add any remaining non-protected items to risk value
        for (int i = 3; i < protectedValues.size(); i++)
        {
            totalRiskValue += protectedValues.get(i);
        }
        
        log.debug("Final risk value: {}, Threshold: {}", totalRiskValue, config.wealthThreshold());
        return totalRiskValue;
    }

    private boolean isInPOH()
    {
        if (!client.isInInstancedRegion())
        {
            log.debug("Not in instanced region");
            return false;
        }

        WorldPoint worldPoint = client.getLocalPlayer().getWorldLocation();
        WorldPoint instancePoint = WorldPoint.fromLocalInstance(client, client.getLocalPlayer().getLocalLocation());
        
        int regionId = worldPoint.getRegionID();
        int instanceRegionId = instancePoint != null ? instancePoint.getRegionID() : -1;
        
        log.debug("Region check - World Region: {}, Instance Region: {}, POH Regions: {}", 
            regionId, instanceRegionId, POH_REGIONS);
        
        return instancePoint != null && POH_REGIONS.contains(instanceRegionId);
    }

    private GameObject findObelisk()
    {
        if (!isInPOH())
        {
            return null;
        }

        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        int plane = client.getPlane();

        // Search the entire scene
        for (int x = 0; x < 104; x++)
        {
            for (int y = 0; y < 104; y++)
            {
                Tile tile = tiles[plane][x][y];
                if (tile == null)
                {
                    continue;
                }

                GameObject[] gameObjects = tile.getGameObjects();
                if (gameObjects == null)
                {
                    continue;
                }

                for (GameObject obj : gameObjects)
                {
                    if (obj != null && obj.getId() == POH_OBELISK_ID)
                    {
                        log.debug("Found obelisk at scene coordinates: {}, {}", x, y);
                        return obj;
                    }
                }
            }
        }
        return null;
    }

    @Provides
    ObeliskProtectionConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(ObeliskProtectionConfig.class);
    }
} 