package com.immersivedialogue;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class VoiceTypeTest
{
	/**
	 * Every declared blip must actually be on the classpath. {@link VoiceBlipPlayer} logs a missing resource at
	 * debug level and drops the blip, so a typo'd key would otherwise only show up as silence in game.
	 */
	@Test
	public void everyDeclaredResourceExists()
	{
		for (final VoiceType voice : VoiceType.values())
		{
			assertTrue(voice + " has no blips", voice.resources.length > 0);
			for (final String resource : voice.resources)
			{
				assertNotNull(
					voice + " is missing " + resource,
					VoiceType.class.getResource(resource));
			}
		}
	}
}
