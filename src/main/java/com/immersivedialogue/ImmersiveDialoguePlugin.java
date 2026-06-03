package com.immersivedialogue;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "Immersive Dialogue",
	description = "Relocates NPC/player dialogue into an immersive panel on your screen, keeping the live animated chat-head.",
	tags = {"dialogue", "npc", "immersion", "chat", "cutscene", "chatbox"}
)
public class ImmersiveDialoguePlugin extends Plugin
{
	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ImmersiveDialogueOverlay overlay;

	@Inject
	private DialogueWidgetController controller;

	@Inject
	private DialogueMouseListener mouseListener;

	@Inject
	private MouseManager mouseManager;

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		mouseManager.registerMouseListener(mouseListener);
		log.debug("Immersive Dialogue started");
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		mouseManager.unregisterMouseListener(mouseListener);
		controller.cleanup();
		log.debug("Immersive Dialogue stopped");
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		// Re-apply every frame: the client rebuilds the dialogue widgets via clientscripts, so a
		// one-shot reposition would be undone. BeforeRender fires after those scripts have run.
		controller.apply();
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		// When the game rebuilds a dialogue interface (e.g. advancing to a new line) it briefly re-shows the
		// native display widgets we hide; re-apply our hiding the instant the interface reloads so the
		// relocated box does not flash for one frame before the next BeforeRender catches it.
		final int group = event.getGroupId();
		if (group == InterfaceID.CHAT_LEFT || group == InterfaceID.CHAT_RIGHT || group == InterfaceID.CHATMENU)
		{
			controller.reassertNativeVisibility();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// Leaving the world (logout, world-hop, disconnect) tears down the interface widget tree, including the
		// head container we created under the interface root. Our reference survives but is now detached, and
		// renderHead()'s parent-id guard can't tell (the root's packed id is identical across logins), so it
		// would reuse the orphaned container and the head would never render. Drop it so the next renderHead()
		// rebuilds on the fresh post-login root.
		final GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING || state == GameState.CONNECTION_LOST)
		{
			controller.resetHead();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		// "Reset position" is a momentary toggle: when switched on, recentre the box and switch it back off.
		if (ImmersiveDialogueConfig.GROUP.equals(event.getGroup())
			&& "resetPosition".equals(event.getKey())
			&& "true".equals(event.getNewValue()))
		{
			controller.resetPosition();
		}
	}

	@Provides
	ImmersiveDialogueConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ImmersiveDialogueConfig.class);
	}
}
