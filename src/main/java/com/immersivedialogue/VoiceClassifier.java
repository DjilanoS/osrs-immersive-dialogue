package com.immersivedialogue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Maps an NPC's display name to a {@link VoiceType}. The client exposes no NPC "category" and no gender field
 * ({@code NPCComposition} has neither), so everything here is inferred from the speaker name available at
 * dialogue time. Matching is case-insensitive and runs in two passes:
 *
 * <ol>
 *     <li><b>Species</b> — creature keywords ("goblin", "demon", …) win outright, so "Fairy Godmother" is fae
 *     rather than female.</li>
 *     <li><b>Gender</b> — female titles/roles ("woman", "queen", "witch") and a curated set of female NPC
 *     first names select {@link VoiceType#HUMAN_FEMALE}.</li>
 * </ol>
 *
 * <p>Anything unmatched falls back to {@link VoiceType#HUMAN_MALE}. The species pass only fires for NPCs named
 * after their species — named creatures ("Elvarg", "Grubfoot") read as human, which is a known limitation of
 * name-only classification.</p>
 *
 * <p>Each keyword declares how it may match. {@link Match#WORD_START} keywords are ones that appear inside
 * unrelated names — "orc" in "Sorceress", "aunt" in "Gauntlet", "hag" in "Shaggy" — while
 * {@link Match#ANYWHERE} keywords are long enough to be safe as substrings ("goblin" in "Hobgoblin"). The
 * rule lists below are the single place to extend or correct coverage.</p>
 */
final class VoiceClassifier
{
	/** How a keyword is allowed to match within a name. */
	private enum Match
	{
		/** Matches as a plain substring. Only for keywords long/distinctive enough not to collide. */
		ANYWHERE,
		/** Matches only at the start of a word (string start, or after a non-letter). */
		WORD_START
	}

	private static final class Rule
	{
		private final String keyword;
		private final Match match;
		private final VoiceType voice;

		private Rule(String keyword, Match match, VoiceType voice)
		{
			this.keyword = keyword;
			this.match = match;
			this.voice = voice;
		}

		private boolean matches(String name)
		{
			return match == Match.ANYWHERE ? name.contains(keyword) : startsWord(name, keyword);
		}
	}

	/** Species keywords, most-specific first; the first match wins. */
	private static final List<Rule> SPECIES = new ArrayList<>();

	/** Female titles and roles. Checked only after {@link #SPECIES}. */
	private static final List<Rule> FEMALE_TITLES = new ArrayList<>();

	/**
	 * Female NPC first names, matched against the name's individual words. Names carry no gender signal of
	 * their own, so this is a curated list. Deliberately conservative: a wrong entry misgenders an NPC on
	 * every line, whereas a missing one merely falls back to male — the behaviour every NPC has today. Only
	 * add names you have confirmed in game.
	 */
	private static final Set<String> FEMALE_NAMES = new HashSet<>(Arrays.asList(
		"aggie", "alice", "ana", "betty", "caroline", "cassie", "catherine", "chaeldar",
		"doris", "elena", "ellamaria", "emily", "frenita", "gertrude", "gudrun", "hannah",
		"hetty", "juliet", "katrine", "keli", "lucy", "maria", "martha", "nieve",
		"sarah", "senliten", "sigrid", "sophie", "thessalia", "trudi", "valaine", "vanessa",
		"veronica", "xenia", "zanik", "zenesha"
	));

	static
	{
		// Order matters within each pass: the first matching rule wins.
		species("imp", Match.WORD_START, VoiceType.IMP);
		species("demon", Match.ANYWHERE, VoiceType.DEMON);
		species("devil", Match.ANYWHERE, VoiceType.DEMON);
		species("abyssal", Match.ANYWHERE, VoiceType.DEMON);
		species("dragonkin", Match.ANYWHERE, VoiceType.DRAGONKIN);
		species("dragon", Match.ANYWHERE, VoiceType.DRAGONKIN);
		species("wyrm", Match.ANYWHERE, VoiceType.DRAGONKIN);
		species("wyvern", Match.ANYWHERE, VoiceType.DRAGONKIN);
		species("troll", Match.ANYWHERE, VoiceType.TROLL);
		species("goblin", Match.ANYWHERE, VoiceType.GOBLIN);
		species("orc", Match.WORD_START, VoiceType.ORC);
		species("fairy", Match.ANYWHERE, VoiceType.FAE);
		species("faerie", Match.ANYWHERE, VoiceType.FAE);
		species("fae", Match.WORD_START, VoiceType.FAE);
		species("nymph", Match.ANYWHERE, VoiceType.FAE);
		species("pixie", Match.ANYWHERE, VoiceType.FAE);
		species("wraith", Match.ANYWHERE, VoiceType.WRAITH);
		species("ghost", Match.ANYWHERE, VoiceType.WRAITH);
		species("shade", Match.ANYWHERE, VoiceType.WRAITH);
		species("spectre", Match.ANYWHERE, VoiceType.WRAITH);
		species("revenant", Match.ANYWHERE, VoiceType.WRAITH);
		species("skeleton", Match.ANYWHERE, VoiceType.WRAITH);
		species("zombie", Match.ANYWHERE, VoiceType.WRAITH);

		// Female titles and roles. Short or embeddable keywords must start a word: "hag" hides in "Shaggy",
		// "aunt" in "Gauntlet"/"Haunted", "nun" in "Nunchaku", "maid" in "Maidstone", "dame" in "Adamant".
		female("woman", Match.ANYWHERE);
		female("girl", Match.ANYWHERE);
		female("lady", Match.ANYWHERE);
		female("queen", Match.ANYWHERE);
		female("princess", Match.ANYWHERE);
		female("duchess", Match.ANYWHERE);
		female("countess", Match.ANYWHERE);
		female("empress", Match.ANYWHERE);
		female("witch", Match.ANYWHERE);
		female("sorceress", Match.ANYWHERE);
		female("enchantress", Match.ANYWHERE);
		female("priestess", Match.ANYWHERE);
		female("abbess", Match.ANYWHERE);
		female("banshee", Match.ANYWHERE);
		female("mermaid", Match.ANYWHERE);
		female("siren", Match.ANYWHERE);
		female("matron", Match.ANYWHERE);
		female("nurse", Match.ANYWHERE);
		female("barmaid", Match.ANYWHERE);
		female("waitress", Match.ANYWHERE);
		female("seamstress", Match.ANYWHERE);
		female("huntress", Match.ANYWHERE);
		female("mistress", Match.ANYWHERE);
		female("grandmother", Match.ANYWHERE);
		female("mother", Match.ANYWHERE);
		female("sister", Match.ANYWHERE);
		female("widow", Match.ANYWHERE);
		female("maiden", Match.ANYWHERE);
		female("wife", Match.WORD_START);
		female("maid", Match.WORD_START);
		female("dame", Match.WORD_START);
		female("aunt", Match.WORD_START);
		female("nun", Match.WORD_START);
		female("hag", Match.WORD_START);
	}

	private VoiceClassifier()
	{
	}

	/**
	 * The voice for an NPC speaker name: its species voice if the name names a species, otherwise
	 * {@link VoiceType#HUMAN_FEMALE} when the name reads as female, otherwise {@link VoiceType#HUMAN_MALE}
	 * (also the result for a null or empty name).
	 */
	static VoiceType classify(String npcName)
	{
		if (npcName == null || npcName.isEmpty())
		{
			return VoiceType.HUMAN_MALE;
		}
		final String name = npcName.toLowerCase(Locale.ROOT);

		for (final Rule rule : SPECIES)
		{
			if (rule.matches(name))
			{
				return rule.voice;
			}
		}
		for (final Rule rule : FEMALE_TITLES)
		{
			if (rule.matches(name))
			{
				return rule.voice;
			}
		}
		return hasFemaleName(name) ? VoiceType.HUMAN_FEMALE : VoiceType.HUMAN_MALE;
	}

	/** True if any whole word of the name is a known female first name (so "Aggie" hits, "Aggression" does not). */
	private static boolean hasFemaleName(String name)
	{
		for (final String word : name.split("[^a-z]+"))
		{
			if (!word.isEmpty() && FEMALE_NAMES.contains(word))
			{
				return true;
			}
		}
		return false;
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

	private static void species(String keyword, Match match, VoiceType voice)
	{
		SPECIES.add(new Rule(keyword, match, voice));
	}

	private static void female(String keyword, Match match)
	{
		FEMALE_TITLES.add(new Rule(keyword, match, VoiceType.HUMAN_FEMALE));
	}
}
