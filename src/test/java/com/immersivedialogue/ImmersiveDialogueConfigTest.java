package com.immersivedialogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import org.junit.Test;

public class ImmersiveDialogueConfigTest
{
	@Test
	public void hintSettingsPreserveExistingDisplayByDefault()
	{
		final ImmersiveDialogueConfig config = new ImmersiveDialogueConfig() { };

		assertTrue(config.showHints());
		assertEquals(new Color(255, 255, 255, 165), config.hintColor());
	}

	@Test
	public void playerVoiceDefaultsToTheOriginalSet()
	{
		// Voice B is the set the plugin has always used, so existing users hear no change on upgrade.
		final ImmersiveDialogueConfig config = new ImmersiveDialogueConfig() { };

		assertEquals(PlayerVoice.VOICE_B, config.playerVoice());
	}
}
