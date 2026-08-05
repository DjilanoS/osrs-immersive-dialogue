package com.immersivedialogue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import org.junit.Test;

public class VoiceClassifierTest
{
	@Test
	public void mapsCreatureKeywords()
	{
		assertEquals(VoiceType.IMP, VoiceClassifier.classify("Imp"));
		assertEquals(VoiceType.GOBLIN, VoiceClassifier.classify("Hobgoblin"));
		assertEquals(VoiceType.DEMON, VoiceClassifier.classify("Greater demon"));
		assertEquals(VoiceType.DRAGONKIN, VoiceClassifier.classify("Green dragon"));
		assertEquals(VoiceType.TROLL, VoiceClassifier.classify("Mountain Troll"));
		assertEquals(VoiceType.WRAITH, VoiceClassifier.classify("Ghost"));
		assertEquals(VoiceType.FAE, VoiceClassifier.classify("Fairy Godmother"));
		assertEquals(VoiceType.ORC, VoiceClassifier.classify("Orc"));
	}

	@Test
	public void fallsBackToMaleForUnknowns()
	{
		assertEquals(VoiceType.HUMAN_MALE, VoiceClassifier.classify("Hans"));
		assertEquals(VoiceType.HUMAN_MALE, VoiceClassifier.classify("Bob"));
		assertEquals(VoiceType.HUMAN_MALE, VoiceClassifier.classify(null));
		assertEquals(VoiceType.HUMAN_MALE, VoiceClassifier.classify(""));
	}

	@Test
	public void shortKeywordsRequireWordStart()
	{
		// "orc" inside "Sorceress" must NOT read as an orc, nor "imp" inside "simple".
		assertNotEquals(VoiceType.ORC, VoiceClassifier.classify("Sorceress"));
		assertEquals(VoiceType.HUMAN_MALE, VoiceClassifier.classify("Simple Simon"));
		// A word-start short keyword still matches (Implings are imp-like Hunter creatures).
		assertEquals(VoiceType.IMP, VoiceClassifier.classify("Impling"));
	}

	@Test
	public void detectsFemaleTitlesAndRoles()
	{
		assertEquals(VoiceType.HUMAN_FEMALE, VoiceClassifier.classify("Woman"));
		assertEquals(VoiceType.HUMAN_FEMALE, VoiceClassifier.classify("Queen Ellamaria"));
		assertEquals(VoiceType.HUMAN_FEMALE, VoiceClassifier.classify("Witch"));
		assertEquals(VoiceType.HUMAN_FEMALE, VoiceClassifier.classify("Sorceress"));
		assertEquals(VoiceType.HUMAN_FEMALE, VoiceClassifier.classify("Lady Keli"));
		assertEquals(VoiceType.HUMAN_FEMALE, VoiceClassifier.classify("Bartender's wife"));
	}

	@Test
	public void detectsCuratedFemaleNames()
	{
		assertEquals(VoiceType.HUMAN_FEMALE, VoiceClassifier.classify("Gertrude"));
		assertEquals(VoiceType.HUMAN_FEMALE, VoiceClassifier.classify("Aggie"));
		assertEquals(VoiceType.HUMAN_FEMALE, VoiceClassifier.classify("Juliet"));
		assertEquals(VoiceType.HUMAN_FEMALE, VoiceClassifier.classify("Ana"));
	}

	@Test
	public void detectsMaleAndUnknownNames()
	{
		assertEquals(VoiceType.HUMAN_MALE, VoiceClassifier.classify("Man"));
		assertEquals(VoiceType.HUMAN_MALE, VoiceClassifier.classify("Duke Horacio"));
		assertEquals(VoiceType.HUMAN_MALE, VoiceClassifier.classify("Romeo"));
	}

	@Test
	public void speciesWinsOverGender()
	{
		// "Godmother" contains "mother", but the species pass runs first.
		assertEquals(VoiceType.FAE, VoiceClassifier.classify("Fairy Godmother"));
		assertEquals(VoiceType.TROLL, VoiceClassifier.classify("Troll woman"));
	}

	@Test
	public void femaleKeywordsDoNotMatchMidWord()
	{
		// "aunt" in "Gauntlet"/"Haunted", "hag" in "Shaggy", "nun" in "Nunchaku", "maid" in "Maidstone".
		assertEquals(VoiceType.HUMAN_MALE, VoiceClassifier.classify("Gauntlet Attendant"));
		assertEquals(VoiceType.HUMAN_MALE, VoiceClassifier.classify("Haunted Mine Guard"));
		assertEquals(VoiceType.HUMAN_MALE, VoiceClassifier.classify("Shaggy"));
	}

	@Test
	public void curatedNamesMatchWholeWordsOnly()
	{
		// "ana" is a curated name, but must not fire inside "Banana" or "Anachronia".
		assertEquals(VoiceType.HUMAN_MALE, VoiceClassifier.classify("Banana seller"));
		assertEquals(VoiceType.HUMAN_MALE, VoiceClassifier.classify("Sarahs"));
	}

	@Test
	public void mapsPlayerVoiceToVoiceSets()
	{
		assertEquals(VoiceType.HUMAN_FEMALE, VoiceType.forPlayerVoice(PlayerVoice.VOICE_A));
		assertEquals(VoiceType.HUMAN_MALE, VoiceType.forPlayerVoice(PlayerVoice.VOICE_B));
		assertEquals(VoiceType.HUMAN_DEEP, VoiceType.forPlayerVoice(PlayerVoice.VOICE_C));
	}
}
