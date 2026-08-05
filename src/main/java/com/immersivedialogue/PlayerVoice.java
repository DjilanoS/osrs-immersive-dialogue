package com.immersivedialogue;

/**
 * Selectable voice-blip set for the local player's own dialogue lines. Config enums are surfaced in the
 * RuneLite settings panel, so this is public and renders via {@link #toString()}.
 *
 * <p>Deliberately named A / B / C rather than by gender: the sets differ only in pitch, and players pick
 * whichever suits their character. {@link #VOICE_B} is the default and is the set the plugin has always
 * used, so existing users hear no change.</p>
 */
public enum PlayerVoice
{
	VOICE_A("Voice A (lighter)"),
	VOICE_B("Voice B (default)"),
	VOICE_C("Voice C (heavier)");

	private final String label;

	PlayerVoice(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
