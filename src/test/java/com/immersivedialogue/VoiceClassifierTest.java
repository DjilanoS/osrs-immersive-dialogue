package com.immersivedialogue;

import static org.junit.Assert.assertEquals;
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
	public void fallsBackToHumanForPeopleAndUnknowns()
	{
		assertEquals(VoiceType.HUMAN, VoiceClassifier.classify("Hans"));
		assertEquals(VoiceType.HUMAN, VoiceClassifier.classify("Bob"));
		assertEquals(VoiceType.HUMAN, VoiceClassifier.classify(null));
		assertEquals(VoiceType.HUMAN, VoiceClassifier.classify(""));
	}

	@Test
	public void shortKeywordsRequireWordStart()
	{
		// "orc" inside "Sorceress" must NOT read as an orc, nor "imp" inside "simple".
		assertEquals(VoiceType.HUMAN, VoiceClassifier.classify("Sorceress"));
		assertEquals(VoiceType.HUMAN, VoiceClassifier.classify("Simple Simon"));
		// A word-start short keyword still matches (Implings are imp-like Hunter creatures).
		assertEquals(VoiceType.IMP, VoiceClassifier.classify("Impling"));
	}
}
