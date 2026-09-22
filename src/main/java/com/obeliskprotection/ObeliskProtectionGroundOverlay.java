package com.obeliskprotection;

import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import javax.inject.Inject;
import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.Point;
import net.runelite.api.Perspective;
import net.runelite.api.GameState;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ObeliskProtectionGroundOverlay extends Overlay
{
    private static final String TEXT = "Protection Active";

    private final Client client;
    private final ObeliskProtectionPlugin plugin;
    private final ObeliskProtectionConfig config;

    @Inject
    private ObeliskProtectionGroundOverlay(Client client, ObeliskProtectionPlugin plugin, ObeliskProtectionConfig config)
    {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!config.showGroundMarker() || !plugin.isProtectionActive())
        {
            return null;
        }

        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return null;
        }

        // The obelisk location is only set while the POH obelisk is in the scene,
        // so it doubles as the "player is in the house" check.
        LocalPoint loc = plugin.getObeliskLocation();
        if (loc == null)
        {
            return null;
        }

        Point canvasPoint = Perspective.getCanvasTextLocation(client, graphics, loc, TEXT, 0);
        if (canvasPoint != null)
        {
            OverlayUtil.renderTextLocation(graphics, canvasPoint, TEXT, config.markerColor());
        }

        return null;
    }
}
