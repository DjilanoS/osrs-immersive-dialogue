package com.immersivedialogue;

/**
 * A voice "set" for dialogue blips and the bundled WAV resource keys backing it. {@link #HUMAN} cycles
 * through several male blips for variety; each creature type plays its single characteristic blip (repeated
 * as the line types). Resource keys are absolute classpath paths loaded by {@link VoiceBlipPlayer}; the files
 * live under {@code src/main/resources/com/immersivedialogue/voice}.
 */
enum VoiceType
{
	HUMAN(new String[]{
		"/com/immersivedialogue/voice/human/male_blip_1.wav",
		"/com/immersivedialogue/voice/human/male_blip_2.wav",
		"/com/immersivedialogue/voice/human/male_blip_3.wav",
		"/com/immersivedialogue/voice/human/male_blip_4.wav",
		"/com/immersivedialogue/voice/human/subtle_male_speech_blip.wav",
	}),
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

	private static String[] creature(String name)
	{
		return new String[]{"/com/immersivedialogue/voice/creatures/" + name + "_speech_blip.wav"};
	}
}
