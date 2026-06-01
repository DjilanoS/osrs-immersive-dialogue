package com.immersivedialogue;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.BeforeRender;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "Immersive Dialogue",
	description = "Relocates NPC/player dialogue into a translucent box at the bottom-center of the screen, keeping the live animated chat-head.",
	tags = {"dialogue", "npc", "immersion", "chat", "cutscene", "chatbox"}
)
public class ImmersiveDialoguePlugin extends Plugin
{
	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ImmersiveDialogueOverlay overlay;

	@Inject
	private DialogueDebugOverlay debugOverlay;

	@Inject
	private DialogueWidgetController controller;

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		overlayManager.add(debugOverlay);
		log.debug("Immersive Dialogue started");
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(debugOverlay);
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

	@Provides
	ImmersiveDialogueConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ImmersiveDialogueConfig.class);
	}
}
