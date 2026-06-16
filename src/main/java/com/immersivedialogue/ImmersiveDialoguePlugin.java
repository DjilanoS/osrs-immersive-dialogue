package com.immersivedialogue;

import com.google.inject.Provides;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
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
import net.runelite.client.input.KeyManager;
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

	@Inject
	private KeyManager keyManager;

	@Inject
	private DialogueKeyListener keyListener;

	@Inject
	private VoiceBlipPlayer voicePlayer;

	@Inject
	private ImmersiveDialogueConfig config;

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		mouseManager.registerMouseListener(mouseListener);
		keyManager.registerKeyListener(keyListener);
		// Load the blips off the client thread, and only when the feature is actually enabled.
		if (config.voiceBlips())
		{
			preloadVoices();
		}
		log.debug("Immersive Dialogue started");
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		mouseManager.unregisterMouseListener(mouseListener);
		keyManager.unregisterKeyListener(keyListener);
		voicePlayer.dispose();
		controller.cleanup();
		log.debug("Immersive Dialogue stopped");
	}

	/** Build the full set of blip resource keys (every voice type) and preload them off the client thread. */
	private void preloadVoices()
	{
		final Set<String> keys = new HashSet<>();
		for (final VoiceType type : VoiceType.values())
		{
			Collections.addAll(keys, type.resources);
		}
		voicePlayer.preloadAsync(keys);
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
		if (group == InterfaceID.CHAT_LEFT || group == InterfaceID.CHAT_RIGHT || group == InterfaceID.CHATMENU
			|| group == InterfaceID.MESSAGEBOX || group == InterfaceID.OBJECTBOX
			|| group == InterfaceID.LEVELUP_DISPLAY)
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
		if (!ImmersiveDialogueConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		// "Reset position" is a momentary toggle: when switched on, recentre the box and switch it back off.
		if ("resetPosition".equals(event.getKey()) && "true".equals(event.getNewValue()))
		{
			controller.resetPosition();
		}
		// Voice blips toggled: load the audio the first time it is enabled, else snap the current line to full text.
		else if ("voiceBlips".equals(event.getKey()))
		{
			if ("true".equals(event.getNewValue()))
			{
				preloadVoices();
			}
			else
			{
				controller.endReveal();
			}
		}
	}

	@Provides
	ImmersiveDialogueConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ImmersiveDialogueConfig.class);
	}
}
