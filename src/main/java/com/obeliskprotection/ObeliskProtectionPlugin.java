package com.obeliskprotection;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    private ClientThread clientThread;

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

    // POH Wilderness Obelisk object ID (RuneLite: ObjectID.POH_WILDERNESS_OBELISK).
    // This object only exists inside a player-owned house, so its presence is the
    // POH check. Do not test the region id: the house template region moves when
    // Jagex reworks the map, which silently disabled this plugin before.
    public static final int POH_OBELISK_ID = 31554;

    // The teleport options live on object ops 1-4. Matching the action instead of
    // the English option text keeps working if Jagex renames an option.
    // GAME_OBJECT_FIFTH_OPTION ("Remove", build mode) and EXAMINE_OBJECT stay.
    private static final int FIRST_OPTION = MenuAction.GAME_OBJECT_FIRST_OPTION.getId();
    private static final int FOURTH_OPTION = MenuAction.GAME_OBJECT_FOURTH_OPTION.getId();

    @Getter
    private GameObject pohObelisk = null;

    @Override
    protected void startUp()
    {
        log.debug("Obelisk Protection started");
        overlayManager.add(groundOverlay);
        // The plugin can be enabled while the player already stands in the house.
        // No spawn events are replayed then, so scan the loaded scene once.
        clientThread.invokeLater(this::scanSceneForObelisk);
    }

    @Override
    protected void shutDown()
    {
        log.debug("Obelisk Protection stopped");
        overlayManager.remove(groundOverlay);
        clearObelisk();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        GameState state = event.getGameState();
        if (state == GameState.LOADING || state == GameState.HOPPING || state == GameState.LOGIN_SCREEN)
        {
            clearObelisk();
        }
        else if (state == GameState.LOGGED_IN)
        {
            scanSceneForObelisk();
        }
    }

    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event)
    {
        GameObject obj = event.getGameObject();
        if (obj.getId() == POH_OBELISK_ID)
        {
            log.debug("POH obelisk spawned at {}", obj.getLocalLocation());
            pohObelisk = obj;
            obeliskLocation = obj.getLocalLocation();
            updateProtection();
        }
    }

    @Subscribe
    public void onGameObjectDespawned(GameObjectDespawned event)
    {
        if (event.getGameObject().getId() == POH_OBELISK_ID)
        {
            log.debug("POH obelisk despawned");
            clearObelisk();
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        int id = event.getContainerId();
        if (id == InventoryID.INVENTORY.getId() || id == InventoryID.EQUIPMENT.getId())
        {
            updateProtection();
        }
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event)
    {
        if (event.getIdentifier() != POH_OBELISK_ID)
        {
            return;
        }

        int type = event.getType();
        if (type < FIRST_OPTION || type > FOURTH_OPTION)
        {
            // "Remove", "Examine" and anything that is not an object op stay.
            return;
        }

        // The obelisk may not be in our field yet, for example when the plugin was
        // enabled this tick. The menu entry itself proves it is on screen.
        if (obeliskLocation == null)
        {
            obeliskLocation = LocalPoint.fromScene(event.getMenuEntry().getParam0(), event.getMenuEntry().getParam1());
        }

        long riskValue = calculateRiskValue();
        protectionActive = riskValue > config.wealthThreshold();

        if (!protectionActive)
        {
            return;
        }

        log.debug("Blocking obelisk option {} (type {}) - risk {} over threshold {}",
            event.getOption(), type, riskValue, config.wealthThreshold());
        client.getMenu().removeMenuEntry(event.getMenuEntry());
    }

    private void clearObelisk()
    {
        pohObelisk = null;
        obeliskLocation = null;
        protectionActive = false;
    }

    private void scanSceneForObelisk()
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        Scene scene = client.getScene();
        if (scene == null)
        {
            return;
        }

        for (Tile[][] plane : scene.getTiles())
        {
            for (Tile[] column : plane)
            {
                for (Tile tile : column)
                {
                    if (tile == null)
                    {
                        continue;
                    }

                    for (GameObject obj : tile.getGameObjects())
                    {
                        if (obj != null && obj.getId() == POH_OBELISK_ID)
                        {
                            log.debug("POH obelisk found by scene scan at {}", obj.getLocalLocation());
                            pohObelisk = obj;
                            obeliskLocation = obj.getLocalLocation();
                            updateProtection();
                            return;
                        }
                    }
                }
            }
        }
    }

    private void updateProtection()
    {
        protectionActive = obeliskLocation != null && calculateRiskValue() > config.wealthThreshold();
    }

    /**
     * Value at risk: everything carried, minus the three most valuable single items,
     * which are kept on death. Returns a long, because a maxed account can carry
     * more than Integer.MAX_VALUE gp and an int would overflow to a negative value.
     */
    private long calculateRiskValue()
    {
        List<Long> protectedValues = new ArrayList<>();
        long totalRiskValue = 0;

        totalRiskValue += collectValues(client.getItemContainer(InventoryID.INVENTORY), protectedValues);
        totalRiskValue += collectValues(client.getItemContainer(InventoryID.EQUIPMENT), protectedValues);

        protectedValues.sort(Collections.reverseOrder());
        for (int i = 3; i < protectedValues.size(); i++)
        {
            totalRiskValue += protectedValues.get(i);
        }

        return totalRiskValue;
    }

    private long collectValues(ItemContainer container, List<Long> protectedValues)
    {
        if (container == null)
        {
            return 0;
        }

        long risk = 0;
        for (Item item : container.getItems())
        {
            if (item.getId() == -1)
            {
                continue;
            }

            long gePrice = itemManager.getItemPrice(item.getId());
            ItemComposition itemComp = itemManager.getItemComposition(item.getId());

            if (itemComp.isStackable() && item.getQuantity() > 3)
            {
                // A stack can only ever protect one of its items.
                risk += gePrice * (item.getQuantity() - 3);
                protectedValues.add(gePrice);
            }
            else
            {
                protectedValues.add(gePrice * item.getQuantity());
            }
        }
        return risk;
    }

    @Provides
    ObeliskProtectionConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(ObeliskProtectionConfig.class);
    }
}
