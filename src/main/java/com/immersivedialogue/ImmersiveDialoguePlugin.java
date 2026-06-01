package com.immersivedialogue;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.MouseManager;
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

	@Inject
	private DialogueMouseListener mouseListener;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private ImmersiveDialogueConfig config;

	@Inject
	private Client client;

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		overlayManager.add(debugOverlay);
		mouseManager.registerMouseListener(mouseListener);
		log.debug("Immersive Dialogue started");
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(debugOverlay);
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
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		// Diagnostic (debug overlay only): logs the exact params of dialogue continue/option clicks so
		// the menuAction parameters used by DialogueMouseListener can be confirmed against a real click.
		if (config.debugOverlay() && event.getMenuAction() == MenuAction.WIDGET_CONTINUE)
		{
			final Widget w = event.getWidget();
			log.debug("WIDGET_CONTINUE param0={} param1={} id={} option='{}' target='{}' widget(id={} index={})",
				event.getParam0(), event.getParam1(), event.getId(), event.getMenuOption(),
				event.getMenuTarget(), w != null ? w.getId() : -1, w != null ? w.getIndex() : -1);
		}
	}

	@Subscribe
	public void onPostMenuSort(PostMenuSort event)
	{
		// Suppress the world hover menu/tooltip while the cursor is over the relocated box, so nothing in
		// the 3D world (e.g. "Pickup Sweetcorn") shows through it. Consuming the click blocks the action;
		// this blocks the hover text too. Skip when a right-click menu is already open.
		if (!config.relocate() || client.isMenuOpen())
		{
			return;
		}
		final Point mouse = client.getMouseCanvasPosition();
		if (mouse != null && controller.blocks(mouse.getX(), mouse.getY()))
		{
			client.getMenu().setMenuEntries(new MenuEntry[0]);
		}
	}

	@Provides
	ImmersiveDialogueConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ImmersiveDialogueConfig.class);
	}
}
