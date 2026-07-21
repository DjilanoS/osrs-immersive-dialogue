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
}
