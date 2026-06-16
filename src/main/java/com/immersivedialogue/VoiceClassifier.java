package com.immersivedialogue;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps an NPC's display name to a {@link VoiceType} with a keyword heuristic. The client (1.12.27) exposes no
 * NPC "category" field, so the type is inferred from the speaker name available at dialogue time. Matching is
 * case-insensitive against an ordered keyword map (most-specific first); anything unmatched falls back to
 * {@link VoiceType#HUMAN}.
 *
 * <p>Short, ambiguous keywords ({@code imp}, {@code orc}, {@code fae}) match only at a word start so that, for
 * example, "Sorceress" is not read as an orc while "Hobgoblin" still reads as a goblin. The map is the single
 * place to extend or correct coverage.</p>
 */
final class VoiceClassifier
{
	private static final Map<String, VoiceType> KEYWORDS = new LinkedHashMap<>();

	static
	{
		// Order matters: the first contained keyword wins.
		KEYWORDS.put("imp", VoiceType.IMP);
		KEYWORDS.put("demon", VoiceType.DEMON);
		KEYWORDS.put("devil", VoiceType.DEMON);
		KEYWORDS.put("abyssal", VoiceType.DEMON);
		KEYWORDS.put("dragonkin", VoiceType.DRAGONKIN);
		KEYWORDS.put("dragon", VoiceType.DRAGONKIN);
		KEYWORDS.put("wyrm", VoiceType.DRAGONKIN);
		KEYWORDS.put("wyvern", VoiceType.DRAGONKIN);
		KEYWORDS.put("troll", VoiceType.TROLL);
		KEYWORDS.put("goblin", VoiceType.GOBLIN);
		KEYWORDS.put("orc", VoiceType.ORC);
		KEYWORDS.put("fairy", VoiceType.FAE);
		KEYWORDS.put("faerie", VoiceType.FAE);
		KEYWORDS.put("fae", VoiceType.FAE);
		KEYWORDS.put("nymph", VoiceType.FAE);
		KEYWORDS.put("pixie", VoiceType.FAE);
		KEYWORDS.put("wraith", VoiceType.WRAITH);
		KEYWORDS.put("ghost", VoiceType.WRAITH);
		KEYWORDS.put("shade", VoiceType.WRAITH);
		KEYWORDS.put("spectre", VoiceType.WRAITH);
		KEYWORDS.put("revenant", VoiceType.WRAITH);
		KEYWORDS.put("skeleton", VoiceType.WRAITH);
		KEYWORDS.put("zombie", VoiceType.WRAITH);
	}

	private VoiceClassifier()
	{
	}

	/** The voice for an NPC speaker name, or {@link VoiceType#HUMAN} when nothing matches / the name is null. */
	static VoiceType classify(String npcName)
	{
		if (npcName == null || npcName.isEmpty())
		{
			return VoiceType.HUMAN;
		}
		final String name = npcName.toLowerCase(Locale.ROOT);
		for (final Map.Entry<String, VoiceType> entry : KEYWORDS.entrySet())
		{
			final String keyword = entry.getKey();
			// Short keywords (<= 3 chars) are too easily embedded in unrelated names (e.g. "orc" in
			// "sorceress"), so require them to start a word; longer ones match anywhere ("goblin" in "hobgoblin").
			if (keyword.length() <= 3 ? startsWord(name, keyword) : name.contains(keyword))
			{
				return entry.getValue();
			}
		}
		return VoiceType.HUMAN;
	}

	/** True if {@code keyword} appears in {@code name} at the start of a word (string start or after a non-letter). */
	private static boolean startsWord(String name, String keyword)
	{
		int from = 0;
		int idx;
		while ((idx = name.indexOf(keyword, from)) >= 0)
		{
			if (idx == 0 || !Character.isLetter(name.charAt(idx - 1)))
			{
				return true;
			}
			from = idx + 1;
		}
		return false;
	}
}
