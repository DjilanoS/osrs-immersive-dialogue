package com.immersivedialogue;

/**
 * A voice "set" for dialogue blips and the bundled WAV resource keys backing it. The three human sets each
 * cycle through several blips for variety; each creature type plays its single characteristic blip (repeated
 * as the line types). Resource keys are absolute classpath paths loaded by {@link VoiceBlipPlayer}; the files
 * live under {@code src/main/resources/com/immersivedialogue/voice}.
 *
 * <p>The human sets serve two axes at once: the player picks one directly via {@link PlayerVoice}, while NPCs
 * are assigned {@link #HUMAN_FEMALE} or {@link #HUMAN_MALE} from their name by {@link VoiceClassifier}.
 * {@link #HUMAN_DEEP} is player-only — nothing classifies an NPC into it.</p>
 */
enum VoiceType
{
	/** Lighter/higher set. Voice A for the player, and the voice for NPCs read as female. */
	HUMAN_FEMALE(human("female_blip_1", "female_blip_2", "female_blip_3", "female_blip_4", "subtle_female_speech_blip")),
	/** The original set. Voice B for the player, the voice for NPCs read as male, and the overall fallback. */
	HUMAN_MALE(human("male_blip_1", "male_blip_2", "male_blip_3", "male_blip_4", "subtle_male_speech_blip")),
	/** Heavier/lower set. Voice C — player-only. */
	HUMAN_DEEP(human("deep_blip_1", "deep_blip_2", "deep_blip_3", "deep_blip_4", "subtle_deep_speech_blip")),
	DEMON(creature("demon")),
	DRAGONKIN(creature("dragonkin")),
	FAE(creature("fae")),
	GOBLIN(creature("goblin")),
	IMP(creature("imp")),
	ORC(creature("orc")),
	TROLL(creature("troll")),
	WRAITH(creature("wraith"));

	/** Absolute classpath keys for this voice's blip(s); never empty. */
	final String[] resources;

	VoiceType(String[] resources)
	{
		this.resources = resources;
	}

	/**
	 * The voice set behind a player's configured {@link PlayerVoice}. Lives here rather than on the enum so
	 * that the public config enum does not expose this package-private type.
	 */
	static VoiceType forPlayerVoice(PlayerVoice voice)
	{
		switch (voice)
		{
			case VOICE_A:
				return HUMAN_FEMALE;
			case VOICE_C:
				return HUMAN_DEEP;
			case VOICE_B:
			default:
				return HUMAN_MALE;
		}
	}

	private static String[] human(String... names)
	{
		final String[] keys = new String[names.length];
		for (int i = 0; i < names.length; i++)
		{
			keys[i] = "/com/immersivedialogue/voice/human/" + names[i] + ".wav";
		}
		return keys;
	}

	private static String[] creature(String name)
	{
		return new String[]{"/com/immersivedialogue/voice/creatures/" + name + "_speech_blip.wav"};
	}
}
