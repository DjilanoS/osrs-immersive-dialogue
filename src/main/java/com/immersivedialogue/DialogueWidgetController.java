package com.immersivedialogue;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.config.ConfigManager;

/**
 * Detects the open dialogue, computes the bottom-center box geometry, extracts the text to draw,
 * and renders the chat-head by mirroring it into a single {@code MODEL} widget we create on a
 * non-clipping full-screen parent (the top-level interface).
 *
 * <p>The native dialogue lives inside the chatbox (interface 162), which structurally clips its
 * descendants (there is no clip flag and no reparenting), so the native head cannot be moved out;
 * and a Graphics2D overlay cannot draw a live 3D model. Hence we create one MODEL widget on the
 * top-level root and reuse it forever (creating per line stacks duplicate heads).</p>
 *
 * <p><b>Animation:</b> the relocated head plays its live talking animation. A reused MODEL
 * widget keeps an internal {@code modelFrame} counter that the client's render thread advances; if
 * a new head's shorter sequence is applied while that counter still holds a stale index from a
 * previous longer animation, the renderer indexes past the frame array and crashes
 * ({@code ArrayIndexOutOfBounds}) on its own thread, where we cannot catch it. Setting
 * {@code animationId = -1} only skips a frame — it does not reset {@code modelFrame}. So instead we
 * <b>recreate the MODEL widget whenever the head model or animation changes</b>: a freshly created
 * widget starts at frame 0, so a stale index can never be applied to a new sequence. The model
 * lives inside a plugin-owned container so {@code deleteAllChildren()} resets only our own child
 * and never touches game-owned widgets on the interface root.</p>
 */
@Slf4j
@Singleton
class DialogueWidgetController
{
	enum Kind
	{
		NONE, NPC, PLAYER, OPTIONS, MESSAGE, OBJECT, LEVELUP,
		// Additional quest / cutscene dialogue interfaces (see detectOpenKind).
		CHAT_BOTH, MESSAGE_TITLED, NOTIFICATION, MESSAGE_URL, OBJECT_DOUBLE
	}

	/**
	 * A dialogue option line: its native child index ({@code subid}), display text, and whether Quest
	 * Helper marked it as the correct choice (mirrored as a highlight in our box).
	 */
	static final class Option
	{
		final int subid;
		final String text;
		final boolean highlighted;

		Option(int subid, String text, boolean highlighted)
		{
			this.subid = subid;
			this.text = text;
			this.highlighted = highlighted;
		}
	}

	// Approximate native dialogue size; used for the bottom-center backdrop box.
	private static final int BOX_W = 506;
	private static final int BOX_H = 155;
	private static final int HEAD_W = 110;
	private static final int HEAD_H = 140;
	// Gap between the avatar and the box: the NPC avatar sits this far left of the box, the player
	// avatar this far right of it, so neither touches the dialogue box edge.
	private static final int HEAD_GAP = 6;
	// Widget opacity: 0 = fully opaque, 255 = fully transparent (RuneLite's setOpacity convention).
	private static final int OPACITY_OPAQUE = 0;
	private static final int OPACITY_TRANSPARENT = 255;
	/** Below this the fade-out is treated as complete and the dialogue is fully cleared. */
	private static final float ALPHA_EPSILON = 0.01f;
	/** Visible (non-whitespace) characters between voice blips while a line types out. */
	private static final int CHARS_PER_BLIP = 3;

	// Adaptive OPTIONS box sizing. The per-row text-height allowance is the configured text size plus this
	// buffer, over-estimating the runescape body line height so the box always covers the option rows the
	// overlay draws.
	private static final int OPTION_TEXT_BUFFER = 4;
	private static final int OPTIONS_MAX_H = 300;

	/**
	 * The level-up interface ({@link InterfaceID.LevelupDisplay}) holds one container per skill, each wrapping a
	 * celebratory 3D model; the client shows only the skill that just levelled and hides the rest. Each entry is
	 * {@code {container, model}}: the container's visibility identifies the levelled skill (parent-aware
	 * {@code isHidden}), and its model is relocated beside our box like a chat-head.
	 */
	private static final int[][] LEVELUP_SKILLS = {
		{InterfaceID.LevelupDisplay.ATTACK, InterfaceID.LevelupDisplay.ATTACK_MODEL0},
		{InterfaceID.LevelupDisplay.STRENGTH, InterfaceID.LevelupDisplay.STRENGTH_MODEL0},
		{InterfaceID.LevelupDisplay.DEFENCE, InterfaceID.LevelupDisplay.DEFENCE_MODEL0},
		{InterfaceID.LevelupDisplay.RANGED, InterfaceID.LevelupDisplay.RANGED_MODEL0},
		{InterfaceID.LevelupDisplay.PRAYER, InterfaceID.LevelupDisplay.PRAYER_MODEL0},
		{InterfaceID.LevelupDisplay.MAGIC, InterfaceID.LevelupDisplay.MAGIC_MODEL0},
		{InterfaceID.LevelupDisplay.HITPOINTS, InterfaceID.LevelupDisplay.HITPOINTS_MODEL0},
		{InterfaceID.LevelupDisplay.AGILITY, InterfaceID.LevelupDisplay.AGILITY_MODEL0},
		{InterfaceID.LevelupDisplay.HERBLORE, InterfaceID.LevelupDisplay.HERBLORE_MODEL0},
		{InterfaceID.LevelupDisplay.THIEVING, InterfaceID.LevelupDisplay.THIEVING_MODEL0},
		{InterfaceID.LevelupDisplay.CRAFTING, InterfaceID.LevelupDisplay.CRAFTING_MODEL0},
		{InterfaceID.LevelupDisplay.FLETCHING, InterfaceID.LevelupDisplay.FLETCHING_MODEL0},
		{InterfaceID.LevelupDisplay.SLAYER, InterfaceID.LevelupDisplay.SLAYER_MODEL0},
		{InterfaceID.LevelupDisplay.HUNTER, InterfaceID.LevelupDisplay.HUNTER_MODEL0},
		{InterfaceID.LevelupDisplay.MINING, InterfaceID.LevelupDisplay.MINING_MODEL0},
		{InterfaceID.LevelupDisplay.SMITHING, InterfaceID.LevelupDisplay.SMITHING_MODEL0},
		{InterfaceID.LevelupDisplay.FISHING, InterfaceID.LevelupDisplay.FISHING_MODEL0},
		{InterfaceID.LevelupDisplay.COOKING, InterfaceID.LevelupDisplay.COOKING_MODEL0},
		{InterfaceID.LevelupDisplay.FIREMAKING, InterfaceID.LevelupDisplay.FIREMAKING_MODEL0},
		{InterfaceID.LevelupDisplay.WOODCUTTING, InterfaceID.LevelupDisplay.WOODCUTTING_MODEL0},
		{InterfaceID.LevelupDisplay.FARMING, InterfaceID.LevelupDisplay.FARMING_MODEL0},
		{InterfaceID.LevelupDisplay.RUNECRAFT, InterfaceID.LevelupDisplay.RUNECRAFT_MODEL0},
		{InterfaceID.LevelupDisplay.CONSTRUCTION, InterfaceID.LevelupDisplay.CONSTRUCTION_MODEL0},
		{InterfaceID.LevelupDisplay.COMBAT, InterfaceID.LevelupDisplay.COMBAT_MODEL0},
		{InterfaceID.LevelupDisplay.SAILING, InterfaceID.LevelupDisplay.SAILING_MODEL0},
	};

	private final Client client;
	private final ImmersiveDialogueConfig config;
	private final ConfigManager configManager;
	private final VoiceBlipPlayer voicePlayer;

	/** Canvas bounds of the bottom-center box this frame, or {@code null} when no dialogue is open. */
	@Getter
	private volatile Rectangle bounds;

	// Extracted text content for the overlay to draw.
	@Getter
	private volatile Kind kind = Kind.NONE;
	@Getter
	private String speakerName;
	@Getter
	private String bodyText;
	@Getter
	private List<Option> options = Collections.emptyList();

	/** Canvas rectangle of the relocated head this frame (for click-blocking), or {@code null}. */
	@Getter
	private volatile Rectangle headBounds;
	/** Native display components we hid this frame (setHidden), restored at the top of the next apply(). */
	private final List<Integer> hiddenComponents = new ArrayList<>();
	/** Native interactive components we made transparent this frame (setOpacity, kept usable), restored next apply(). */
	private final List<Integer> dimmedComponents = new ArrayList<>();

	// Our head widgets, reused across frames (see renderHead).
	private Widget headSource;
	private Widget headContainer;
	private Widget createdHead;

	private int builtModelType = Integer.MIN_VALUE;
	private int builtModelId = Integer.MIN_VALUE;
	private int builtItemId = Integer.MIN_VALUE;
	private int builtAnimation = Integer.MIN_VALUE;

	/** Eased 0..1 visibility the overlay multiplies into its alpha, and the head mirrors as opacity. */
	@Getter
	private volatile float displayAlpha = 0f;
	/** Wall-clock of the previous frame, used to advance the fade independent of frame rate. */
	private long lastFrameMs = 0L;

	/** ALT-drag state: the active flag, the offsets snapshotted at press, and the cumulative pixel delta. */
	private volatile boolean dragActive;
	private volatile int dragBaseHorizontalOffset;
	private volatile int dragBaseBottomMargin;
	private volatile int dragDx;
	private volatile int dragDy;

	// --- Voice-blip typewriter reveal -----------------------------------------
	/** True while a line is still typing out. Read on the AWT thread by the key / mouse listeners. */
	private volatile boolean revealing;
	/** Set on the AWT thread (Space / left-click) to finish the current line; consumed on the client thread. */
	private volatile boolean skipRequested;
	/** Characters of {@link #bodyText} revealed so far (client thread only). */
	private int revealedChars;
	/** Fractional carry so the reveal advances smoothly at the configured chars/second (client thread only). */
	private float revealAccumulator;
	/** The body text whose reveal is in progress, used to detect when a new line appears (client thread only). */
	private String lastRevealBody;
	/** The voice chosen for the speaker of the current line (client thread only). */
	private VoiceType voiceType = VoiceType.HUMAN_MALE;
	/** Round-robin index into {@link VoiceType#resources} (client thread only). */
	private int voiceCursor;
	/** Non-whitespace characters revealed since the last blip (client thread only). */
	private int charsSinceBlip;
	/** Set once the line is fully revealed or skipped, suppressing any further blips for it (client thread only). */
	private boolean blipsDoneForLine;

	@Inject
	DialogueWidgetController(Client client, ImmersiveDialogueConfig config, ConfigManager configManager,
		VoiceBlipPlayer voicePlayer)
	{
		this.client = client;
		this.config = config;
		this.configManager = configManager;
		this.voicePlayer = voicePlayer;
	}

	/** Re-applied every frame from {@code BeforeRender}. */
	void apply()
	{
		// Un-hide whatever we hid last frame BEFORE detection, so our own hiding never confuses the
		// open/closed check below (detection keys on the UNIVERSE container, which we never hide).
		restoreNative();

		final long now = System.currentTimeMillis();
		final float dt = lastFrameMs == 0L ? 0f : Math.max(0L, now - lastFrameMs) / 1000f;
		lastFrameMs = now;

		// Detect the currently-open dialogue WITHOUT touching the published fields yet: when the
		// dialogue closes we keep drawing the previous content while the fade-out runs, so the box
		// has something to fade rather than vanishing instantly.
		final Kind detected = detectOpenKind();
		// PLAYER puts the head on the right; CHAT_BOTH decides its side during extraction (see below).
		boolean isPlayer = detected == Kind.PLAYER;
		// Stay out of an unskippable, bodyless cutscene pause entirely: treat it as "no dialogue open" so we
		// draw nothing and leave the native UI untouched, rather than covering the scene with an empty box
		// and a misleading "Press Space to continue" hint (see bodylessUnskippablePause).
		final boolean open = detected != Kind.NONE && !bodylessUnskippablePause(detected);

		updateAlpha(open, dt);

		if (!open)
		{
			// A closing dialogue has nothing left to type out: stop revealing so the fade-out shows the full
			// last line, the skip keys disengage, and the next open is treated as a fresh line.
			revealing = false;
			skipRequested = false;
			lastRevealBody = null;
			// Hide the relocated head immediately. If !open is because an UNRECOGNISED dialogue interface is
			// actually on screen (a quest dialogue type we don't handle yet), retaining the head would leave it
			// floating over the scene with no box. The box below still fades out harmlessly with nothing behind
			// it, so only the head — the part that looks broken — is dropped early.
			hideHead();
			// Still fading out: retain last frame's box content so it fades rather than vanishing instantly.
			if (fadeActive() && displayAlpha > ALPHA_EPSILON)
			{
				return;
			}
			// Fully closed (or fade disabled): clear everything.
			clearDialogue();
			return;
		}

		// A dialogue is open: (re)populate everything fresh this frame.
		bounds = null;
		headBounds = null;
		speakerName = null;
		bodyText = null;
		options = Collections.emptyList();
		headSource = null;
		kind = detected;

		switch (detected)
		{
			case NPC:
				headSource = client.getWidget(InterfaceID.ChatLeft.HEAD);
				speakerName = text(InterfaceID.ChatLeft.NAME);
				bodyText = text(InterfaceID.ChatLeft.TEXT);
				break;
			case PLAYER:
				headSource = client.getWidget(InterfaceID.ChatRight.HEAD);
				speakerName = text(InterfaceID.ChatRight.NAME);
				bodyText = text(InterfaceID.ChatRight.TEXT);
				break;
			case OPTIONS:
				// Read option text while the widgets are still in their natural state (before hideNative).
				options = readOptions(client.getWidget(InterfaceID.Chatmenu.OPTIONS));
				break;
			case MESSAGE:
				// Plain narration box: just the body text, no speaker name and no head.
				bodyText = text(InterfaceID.Messagebox.TEXT);
				break;
			case OBJECT:
				// Item message box: relocate the item model (rendered like a head) beside the text. Its TEXT
				// widget bakes in the "Click here to continue" line, so strip that before redrawing.
				headSource = client.getWidget(InterfaceID.Objectbox.ITEM);
				bodyText = stripContinuePrompt(text(InterfaceID.Objectbox.TEXT));
				break;
			case LEVELUP:
				// Level-up: combine the two message lines and relocate the levelled skill's model like a head.
				bodyText = joinLines(text(InterfaceID.LevelupDisplay.TEXT1), text(InterfaceID.LevelupDisplay.TEXT2));
				headSource = levelupModel();
				break;
			case CHAT_BOTH:
			{
				// Two-person conversation: one shared name + text line, with an NPC head (LEFT) and a player
				// head (RIGHT). We draw a single head, so pick the current speaker (best-effort) and place it on
				// the matching side (NPC left, player right).
				speakerName = text(InterfaceID.ChatBoth.NAMES);
				bodyText = text(InterfaceID.ChatBoth.TEXT);
				final Widget left = client.getWidget(InterfaceID.ChatBoth.LEFT);
				final Widget right = client.getWidget(InterfaceID.ChatBoth.RIGHT);
				final boolean useRight = chatBothSpeakerIsRight(left, right);
				headSource = useRight ? right : left;
				isPlayer = useRight;
				break;
			}
			case MESSAGE_TITLED:
				// Titled narration box: show the title as the header and the body below it.
				speakerName = text(InterfaceID.MessageboxTitled.TITLE);
				bodyText = text(InterfaceID.MessageboxTitled.TEXT);
				break;
			case NOTIFICATION:
				// Notification narration (quest cutscene text): title as header, main text as body.
				speakerName = text(InterfaceID.NotificationDisplay.TITLE_TEXT);
				bodyText = text(InterfaceID.NotificationDisplay.MAIN_TEXT);
				break;
			case MESSAGE_URL:
				// URL message box: just the body text (the clickable URL stays native, see hideNative).
				bodyText = text(InterfaceID.MessageboxUrl.TEXT);
				break;
			case OBJECT_DOUBLE:
				// Two-item message box: relocate the first item model beside the text (rendered like a head).
				// Its TEXT widget bakes in the "Click here to continue" line, so strip that before redrawing.
				headSource = client.getWidget(InterfaceID.ObjectboxDouble.MODEL1);
				bodyText = stripContinuePrompt(text(InterfaceID.ObjectboxDouble.TEXT));
				break;
			default:
				break;
		}

		// Voice-blip typewriter: only NPC / player conversation lines reveal + blip, and only when enabled.
		// Everything else (and the feature being off) shows the full text instantly — see getRevealedChars().
		if (config.voiceBlips() && (kind == Kind.NPC || kind == Kind.PLAYER) && bodyText != null)
		{
			advanceReveal(dt);
		}
		else
		{
			revealing = false;
			lastRevealBody = null;
		}

		final int cw = client.getCanvasWidth();
		final int ch = client.getCanvasHeight();
		final float s = scale();
		final int boxW = scaled(BOX_W, s);
		// Options grow the box to fit their count (already scaled inside optionsBoxHeight); a hint-only box
		// (no speaker, body or head — e.g. a "Press Space to continue" cutscene pause) shrinks to a single
		// line so it doesn't hog the screen; plain dialogue keeps the fixed, scaled height.
		final int boxH;
		if (kind == Kind.OPTIONS)
		{
			boxH = optionsBoxHeight();
		}
		else if (hintOnly())
		{
			boxH = hintOnlyBoxHeight();
		}
		else
		{
			boxH = scaled(BOX_H, s);
		}
		final int x = ((cw - boxW) / 2) + effectiveHorizontalOffset();
		final int y = ch - boxH - effectiveBottomMargin();
		bounds = new Rectangle(x, y, boxW, boxH);

		if (headSource != null && headSource.getModelType() > 0)
		{
			final int headW = scaled(HEAD_W, s);
			final int headH = scaled(HEAD_H, s);
			final int gap = scaled(HEAD_GAP, s);
			final int hx = isPlayer ? (bounds.x + bounds.width + gap) : (bounds.x - headW - gap);
			final int hy = bounds.y + ((bounds.height - headH) / 2);
			headBounds = new Rectangle(hx, hy, headW, headH);
			renderHead(headSource, headBounds);
		}
		else
		{
			hideHead();
		}

		applyHeadOpacity();

		// Finally, suppress the native dialogue (hide the display widgets, dim the interactive ones). Done last
		// so every read above sees them normal; re-applied each frame because the client rebuilds them per line.
		hideNative(kind);
	}

	/**
	 * Detect which dialogue interface is currently open by testing each interface's {@code UNIVERSE} root for
	 * visibility, in priority order; returns {@link Kind#NONE} when no known dialogue is open. Shared by
	 * {@link #apply()} and {@link #reassertNativeVisibility()} so the interface list lives in ONE place —
	 * adding support for a new quest dialogue type is a single edit here (plus its extraction / hideNative
	 * / overlay cases). The interfaces are mutually exclusive, so the order is priority only, not correctness.
	 */
	private Kind detectOpenKind()
	{
		if (visible(client.getWidget(InterfaceID.ChatLeft.UNIVERSE)))
		{
			return Kind.NPC;
		}
		if (visible(client.getWidget(InterfaceID.ChatRight.UNIVERSE)))
		{
			return Kind.PLAYER;
		}
		if (visible(client.getWidget(InterfaceID.ChatBoth.UNIVERSE)))
		{
			// Two-person conversation (NPC + player heads share one name / text line).
			return Kind.CHAT_BOTH;
		}
		if (visible(client.getWidget(InterfaceID.Chatmenu.UNIVERSE)))
		{
			return Kind.OPTIONS;
		}
		if (visible(client.getWidget(InterfaceID.Messagebox.UNIVERSE)))
		{
			// Plain "click to continue" message box (quest / system narration): no speaker, no head.
			return Kind.MESSAGE;
		}
		if (visible(client.getWidget(InterfaceID.MessageboxTitled.UNIVERSE)))
		{
			// Titled narration box: a title line + body, no speaker head.
			return Kind.MESSAGE_TITLED;
		}
		if (visible(client.getWidget(InterfaceID.NotificationDisplay.UNIVERSE)))
		{
			// Notification narration box (quest cutscene text): title + main text, no head.
			return Kind.NOTIFICATION;
		}
		if (visible(client.getWidget(InterfaceID.MessageboxUrl.UNIVERSE)))
		{
			// Message box with a clickable URL: body text, no head.
			return Kind.MESSAGE_URL;
		}
		if (visible(client.getWidget(InterfaceID.Objectbox.UNIVERSE)))
		{
			// Item message box ("You show the X to Y."): an item model + text, no speaker.
			return Kind.OBJECT;
		}
		if (visible(client.getWidget(InterfaceID.ObjectboxDouble.UNIVERSE)))
		{
			// Two-item message box: two item models + text, no speaker.
			return Kind.OBJECT_DOUBLE;
		}
		if (visible(client.getWidget(InterfaceID.LevelupDisplay.UNIVERSE)))
		{
			// Skill level-up interface ("Congratulations, you just advanced..."): two text lines + a skill model.
			return Kind.LEVELUP;
		}
		return Kind.NONE;
	}

	/**
	 * Best-effort choice of which {@link InterfaceID.ChatBoth} head is the current speaker: prefer the only
	 * head that carries a model; if both (or neither) are modelled we cannot tell the speaker from the widgets
	 * alone, so default to the NPC (LEFT). Returns {@code true} when the RIGHT (player) head should be drawn.
	 */
	private static boolean chatBothSpeakerIsRight(Widget left, Widget right)
	{
		final boolean leftModel = left != null && left.getModelType() > 0;
		final boolean rightModel = right != null && right.getModelType() > 0;
		// Only the player head has a model: show the player. Otherwise default to the NPC head on the left.
		return rightModel && !leftModel;
	}

	/**
	 * A cutscene "pause" the plugin stays out of entirely: a narration interface with no title and no body
	 * text (only a bare continue prompt would show) that the player ALSO cannot advance — i.e. there is no
	 * visible native continue component. For these we render nothing and leave the native UI untouched, so
	 * the plugin never covers an unskippable cutscene with an empty box or a misleading "Press Space to
	 * continue" hint.
	 *
	 * A bodyless pause the player CAN advance (a visible continue) is deliberately NOT suppressed here — it
	 * still shows the compact single-line hint box (see {@link #hintOnly()} / {@link #hintOnlyBoxHeight()}).
	 * Only the text/narration kinds can be a bare pause; NPC / PLAYER / CHAT_BOTH / OBJECT(_DOUBLE) / LEVELUP
	 * always carry a head, model or options, and OPTIONS is a menu — so none of those ever qualifies.
	 */
	private boolean bodylessUnskippablePause(Kind k)
	{
		final int titleId;
		final int bodyId;
		final int continueId;
		switch (k)
		{
			case MESSAGE:
				titleId = -1;
				bodyId = InterfaceID.Messagebox.TEXT;
				continueId = InterfaceID.Messagebox.CONTINUE;
				break;
			case MESSAGE_TITLED:
				titleId = InterfaceID.MessageboxTitled.TITLE;
				bodyId = InterfaceID.MessageboxTitled.TEXT;
				continueId = InterfaceID.MessageboxTitled.CONTINUE;
				break;
			case NOTIFICATION:
				titleId = InterfaceID.NotificationDisplay.TITLE_TEXT;
				bodyId = InterfaceID.NotificationDisplay.MAIN_TEXT;
				continueId = -1; // NotificationDisplay has no continue component.
				break;
			case MESSAGE_URL:
				titleId = -1;
				bodyId = InterfaceID.MessageboxUrl.TEXT;
				continueId = InterfaceID.MessageboxUrl.CONTINUE;
				break;
			default:
				return false;
		}
		if (!isBlank(titleId >= 0 ? text(titleId) : null) || !isBlank(text(bodyId)))
		{
			return false; // has narration to show
		}
		// Bodyless: suppress only when the player cannot advance it (no visible native continue prompt).
		return continueId < 0 || !visible(client.getWidget(continueId));
	}

	private static boolean isBlank(String s)
	{
		return s == null || s.isEmpty();
	}

	/** True when the fade feature is on and has a non-zero duration (otherwise transitions are instant). */
	private boolean fadeActive()
	{
		return config.fade() && config.fadeDuration() > 0;
	}

	/** Eases {@link #displayAlpha} toward 1 (dialogue open) or 0 (closed) at the configured rate. */
	private void updateAlpha(boolean open, float dt)
	{
		final float target = open ? 1f : 0f;
		if (!fadeActive())
		{
			displayAlpha = target;
			return;
		}
		final float step = dt / (config.fadeDuration() / 1000f);
		if (displayAlpha < target)
		{
			displayAlpha = Math.min(target, displayAlpha + step);
		}
		else if (displayAlpha > target)
		{
			displayAlpha = Math.max(target, displayAlpha - step);
		}
	}

	/**
	 * Advances the typewriter reveal for the current NPC / player line by the elapsed time, firing voice blips as
	 * new characters appear. A new line is detected by comparing against {@link #lastRevealBody}; a pending
	 * {@link #requestSkip()} jumps to the end and silences the rest of the line. Client thread only.
	 */
	private void advanceReveal(float dt)
	{
		final String body = bodyText;
		if (!body.equals(lastRevealBody))
		{
			// New line: reset progress and pick the speaker's voice. The player uses their configured set;
			// NPCs are classified from their name (species first, then gender).
			lastRevealBody = body;
			revealedChars = 0;
			revealAccumulator = 0f;
			charsSinceBlip = 0;
			voiceCursor = 0;
			blipsDoneForLine = false;
			voiceType = (kind == Kind.PLAYER)
				? VoiceType.forPlayerVoice(config.playerVoice())
				: VoiceClassifier.classify(speakerName);
			revealing = true;
		}

		if (skipRequested)
		{
			skipRequested = false;
			revealedChars = body.length();
			blipsDoneForLine = true;
			revealing = false;
			return;
		}

		if (revealedChars >= body.length())
		{
			revealing = false;
			return;
		}

		revealing = true;
		revealAccumulator += config.textSpeed() * dt;
		final int step = (int) revealAccumulator;
		if (step <= 0)
		{
			return;
		}
		revealAccumulator -= step;
		final int target = Math.min(body.length(), revealedChars + step);
		for (int i = revealedChars; i < target; i++)
		{
			if (!Character.isWhitespace(body.charAt(i)) && ++charsSinceBlip >= CHARS_PER_BLIP)
			{
				charsSinceBlip = 0;
				playBlip();
			}
		}
		revealedChars = target;
		if (revealedChars >= body.length())
		{
			revealing = false;
		}
	}

	/** Plays the next blip in the current voice set, unless the line is done or the volume is zero. Client thread. */
	private void playBlip()
	{
		if (blipsDoneForLine)
		{
			return;
		}
		final int volume = config.voiceVolume();
		if (volume <= 0)
		{
			return;
		}
		final String[] set = voiceType.resources;
		voicePlayer.play(set[voiceCursor % set.length], volume);
		voiceCursor++;
	}

	/**
	 * Number of body characters to draw this frame: the revealed count while typing, or {@link Integer#MAX_VALUE}
	 * when not revealing (feature off, non-conversation dialogue, or line complete) so the overlay draws it all.
	 */
	int getRevealedChars()
	{
		return revealing ? revealedChars : Integer.MAX_VALUE;
	}

	/** True while a line is still typing out (used by the key / mouse listeners to decide whether to skip). */
	boolean isRevealing()
	{
		return revealing;
	}

	/** Request that the current line finish revealing immediately (Space / left-click). AWT thread. */
	void requestSkip()
	{
		skipRequested = true;
	}

	/** Snap the reveal off so the full line shows at once (used when the feature is toggled off mid-conversation). */
	void endReveal()
	{
		revealing = false;
		skipRequested = false;
		lastRevealBody = null;
	}

	/**
	 * Mirrors {@link #displayAlpha} onto the relocated head as widget opacity so it fades with the box.
	 * Best-effort: the client may ignore opacity for {@code MODEL} widgets, in which case the head simply
	 * pops while the rest of the box fades. Never allowed to break the frame.
	 */
	private void applyHeadOpacity()
	{
		final Widget head = createdHead;
		if (head == null)
		{
			return;
		}
		final int opacity = fadeActive()
			? Math.max(0, Math.min(255, Math.round((1f - displayAlpha) * 255f)))
			: 0;
		try
		{
			head.setOpacity(opacity);
		}
		catch (Exception ignored)
		{
			// opacity is a cosmetic best-effort; a failure here must not stop the dialogue from drawing
		}
	}

	/** Clears all published dialogue state and hides the head (the dialogue is gone / fully faded out). */
	private void clearDialogue()
	{
		bounds = null;
		headBounds = null;
		kind = Kind.NONE;
		speakerName = null;
		bodyText = null;
		options = Collections.emptyList();
		headSource = null;
		hideHead();
	}

	/**
	 * Adaptive height for the OPTIONS box so it snugly fits its option count instead of being a fixed,
	 * cavernous box. Deliberately over-estimates line heights so the box always covers the rows the
	 * overlay draws; the spacing constants are shared with {@link ImmersiveDialogueOverlay} so the two
	 * never drift.
	 */
	private int optionsBoxHeight()
	{
		// Scale every term by the same factor the overlay uses to draw, with the same font floor, so the
		// computed box height always matches the rows actually rendered (see ImmersiveDialogueOverlay).
		final float s = scale();
		final int titlePx = Math.max(ImmersiveDialogueOverlay.MIN_FONT_PX, scaled(config.titleFontSize(), s));
		final int bodyPx = Math.max(ImmersiveDialogueOverlay.MIN_FONT_PX, scaled(config.textSize(), s));
		final int inset = scaled(ImmersiveDialogueOverlay.INSET, s);
		final int lineGap = scaled(ImmersiveDialogueOverlay.LINE_GAP, s);
		final int optPad = scaled(ImmersiveDialogueOverlay.OPTION_PAD, s);
		final int optGap = scaled(ImmersiveDialogueOverlay.OPTION_GAP, s);
		final int textBuf = scaled(OPTION_TEXT_BUFFER, s);

		int h = inset * 2; // top + bottom padding
		for (final Option o : options)
		{
			if (o.subid == 0)
			{
				// "Select an Option" header, drawn in the larger title font as a plain line.
				h += (titlePx + scaled(6, s)) + lineGap;
			}
			else
			{
				h += (bodyPx + textBuf) + (optPad * 2) + optGap;
			}
		}
		// Reserve a line at the bottom only when the overlay will draw the options hint.
		if (config.showHints())
		{
			h += bodyPx + textBuf + lineGap;
		}
		return Math.min(h, scaled(OPTIONS_MAX_H, s));
	}

	/**
	 * True when the box would draw only the "Press Space to continue" hint — no speaker name, no body text
	 * and no head/model. Common during cutscene pauses; used to shrink the box so it doesn't cover the scene.
	 */
	private boolean hintOnly()
	{
		final boolean noName = speakerName == null || speakerName.isEmpty();
		final boolean noBody = bodyText == null || bodyText.isEmpty();
		final boolean noHead = headSource == null || headSource.getModelType() <= 0;
		return noName && noBody && noHead;
	}

	/**
	 * Compact height for a {@link #hintOnly()} box: one bottom hint line plus inset padding. The overlay
	 * anchors the hint near the box bottom, and {@code bodyPx + textBuf} over-estimates one line's height,
	 * so this yields a snug single-line box (scaled with the same terms the overlay draws with).
	 */
	private int hintOnlyBoxHeight()
	{
		final float s = scale();
		final int bodyPx = Math.max(ImmersiveDialogueOverlay.MIN_FONT_PX, scaled(config.textSize(), s));
		final int inset = scaled(ImmersiveDialogueOverlay.INSET, s);
		final int textBuf = scaled(OPTION_TEXT_BUFFER, s);
		return bodyPx + textBuf + inset;
	}

	/** Begins an ALT-drag: snapshot the current Position offsets so {@link #dragBy} deltas move from here. */
	void beginDrag()
	{
		dragBaseHorizontalOffset = config.horizontalOffset();
		dragBaseBottomMargin = config.bottomMargin();
		dragDx = 0;
		dragDy = 0;
		dragActive = true;
	}

	/** Updates the in-progress drag with the cumulative pixel delta from the press point. */
	void dragBy(int dx, int dy)
	{
		dragDx = dx;
		dragDy = dy;
	}

	/** Ends an ALT-drag, persisting the moved position to the Position config so it survives the next frame. */
	void endDrag()
	{
		final int h = effectiveHorizontalOffset();
		final int b = effectiveBottomMargin();
		configManager.setConfiguration(ImmersiveDialogueConfig.GROUP, "horizontalOffset", h);
		configManager.setConfiguration(ImmersiveDialogueConfig.GROUP, "bottomMargin", b);
		dragActive = false;
	}

	/** Resets the box to its default position by clearing the saved Position overrides (defaults then reapply). */
	void resetPosition()
	{
		configManager.unsetConfiguration(ImmersiveDialogueConfig.GROUP, "horizontalOffset");
		configManager.unsetConfiguration(ImmersiveDialogueConfig.GROUP, "bottomMargin");
		configManager.setConfiguration(ImmersiveDialogueConfig.GROUP, "resetPosition", false);
	}

	/** Live drag value while dragging (clamped to keep the box on-screen), else the configured offset. */
	private int effectiveHorizontalOffset()
	{
		final int max = Math.max(0, (client.getCanvasWidth() - scaled(BOX_W, scale())) / 2);
		final int offset = dragActive ? (dragBaseHorizontalOffset + dragDx) : config.horizontalOffset();
		return clamp(offset, -max, max);
	}

	/** Live drag value while dragging (clamped to keep the box on-screen), else the configured margin. */
	private int effectiveBottomMargin()
	{
		final int max = Math.max(0, client.getCanvasHeight() - scaled(BOX_H, scale()));
		final int margin = dragActive ? (dragBaseBottomMargin - dragDy) : config.bottomMargin();
		return clamp(margin, 0, max);
	}

	private static int clamp(int v, int min, int max)
	{
		return v < min ? min : (v > max ? max : v);
	}

	/** Current dialogue scale factor (1.0 = 100%). */
	private float scale()
	{
		return config.scalePercent() / 100f;
	}

	/** A base constant scaled by {@code s}. Mirrors ImmersiveDialogueOverlay.scaled so the two never drift. */
	private static int scaled(int v, float s)
	{
		return Math.round(v * s);
	}

	/** True if the canvas point is over the dialogue box (incl. backdrop padding) or the head beside it. */
	boolean blocks(int px, int py)
	{
		final Rectangle box = bounds;
		if (box != null)
		{
			final int pad = config.backdropPadding();
			if (px >= box.x - pad && px < box.x + box.width + pad
				&& py >= box.y - pad && py < box.y + box.height + pad)
			{
				return true;
			}
		}
		final Rectangle h = headBounds;
		return h != null && h.contains(px, py);
	}

	private static boolean visible(Widget w)
	{
		return w != null && !w.isHidden();
	}

	private String text(int componentId)
	{
		final Widget w = client.getWidget(componentId);
		return w == null ? null : clean(w.getText());
	}

	/**
	 * The color Quest Helper would use to highlight the correct option, or {@code null} when there is
	 * nothing to mirror (QH highlighting off, or QH absent). Quest Helper stores these in its own config
	 * group ("questhelper"); {@link ConfigManager#getConfiguration} returns {@code null} for any value the
	 * user never changed, so we fall back to QH's own defaults (highlight on, color blue). When QH is not
	 * installed no native option ever carries this color, so detection simply matches none.
	 */
	private Color questHelperColor()
	{
		final Boolean show = configManager.getConfiguration("questhelper", "showTextHighlight", Boolean.class);
		if (Boolean.FALSE.equals(show))
		{
			return null;
		}
		final Color c = configManager.getConfiguration("questhelper", "textHighlightColor", Color.class);
		return c != null ? c : Color.BLUE;
	}

	/**
	 * Reads the option lines, capturing each line's native child index ({@code getIndex()}) as its
	 * {@code subid} plus whether Quest Helper highlighted it. The {@code subid} is used only to identify the
	 * "Select an Option" header (child {@code subid 0}) and to keep the options in native order; selection
	 * itself is handled natively by the 1-5 number keys, so the plugin never acts on it.
	 */
	private List<Option> readOptions(Widget optionsWidget)
	{
		final List<Option> out = new ArrayList<>();
		if (optionsWidget == null)
		{
			return out;
		}
		// Options are dynamic children (the array getChild()/the menu index into); fall back to static
		// children only if there are none, so the subids stay consistent with getChild().
		Widget[] children = optionsWidget.getDynamicChildren();
		if (children == null || children.length == 0)
		{
			children = optionsWidget.getStaticChildren();
		}
		collectOptionText(children, out, questHelperColor());
		return out;
	}

	private static void collectOptionText(Widget[] children, List<Option> out, Color questHelperColor)
	{
		if (children == null)
		{
			return;
		}
		for (final Widget c : children)
		{
			if (c == null || c.getType() != WidgetType.TEXT)
			{
				continue;
			}
			String t = clean(c.getText());
			if (t == null || t.isEmpty())
			{
				continue;
			}
			final int subid = c.getIndex();
			boolean dup = false;
			for (final Option o : out)
			{
				if (o.subid == subid)
				{
					dup = true;
					break;
				}
			}
			if (dup)
			{
				continue;
			}
			// Quest Helper recolors the correct option's native text (subid 0 is the "Select an Option"
			// header, never a choice). The color survives our setHidden, so reading it here mirrors the
			// highlight into our box. Compare masked to 24 bits: getTextColor() carries no alpha byte.
			final boolean highlighted = questHelperColor != null && subid != 0
				&& (c.getTextColor() & 0xFFFFFF) == (questHelperColor.getRGB() & 0xFFFFFF);
			// Strip any leading "[N] " prefix (e.g. Quest Helper numbers the correct option) so the
			// overlay's own uniform 1-5 numbering can't produce a double "[1] [1] …".
			t = t.replaceFirst("^\\s*\\[\\d+\\]\\s*", "");
			out.add(new Option(subid, t, highlighted));
		}
	}

	/**
	 * Remove a trailing "Click here to continue" prompt that the item message box bakes into its body text
	 * (its {@code TEXT} widget holds the message and the prompt together, with no separate continue component).
	 */
	private static String stripContinuePrompt(String text)
	{
		if (text == null)
		{
			return null;
		}
		return text.replaceFirst("(?is)\\s*click here to continue\\.?\\s*$", "").trim();
	}

	/** Join two message lines with a newline, tolerating either being null/empty (returns the other). */
	private static String joinLines(String a, String b)
	{
		final boolean ea = a == null || a.isEmpty();
		final boolean eb = b == null || b.isEmpty();
		if (ea)
		{
			return eb ? null : b;
		}
		return eb ? a : (a + "\n" + b);
	}

	/**
	 * The {@code {container, model}} entry of the skill currently shown on the level-up interface, or
	 * {@code null} when none is visible. The client hides every skill container except the one that just
	 * levelled, and {@link Widget#isHidden()} is parent-aware, so the single visible container identifies it.
	 */
	private int[] levelupSkill()
	{
		for (final int[] skill : LEVELUP_SKILLS)
		{
			if (visible(client.getWidget(skill[0])))
			{
				return skill;
			}
		}
		return null;
	}

	/** The levelled skill's celebratory MODEL widget (relocated like a chat-head), or {@code null}. */
	private Widget levelupModel()
	{
		final int[] skill = levelupSkill();
		return skill == null ? null : client.getWidget(skill[1]);
	}

	/** Hide the levelled skill's container so the native (clipped) model isn't drawn alongside our relocated copy. */
	private void hideLevelupModel()
	{
		final int[] skill = levelupSkill();
		if (skill != null)
		{
			hide(skill[0]);
		}
	}

	/** Strip OSRS markup; convert {@code <br>} to newlines. */
	static String clean(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		String s = raw.replaceAll("(?i)<br\\s*/?>", "\n");
		s = s.replaceAll("<[^>]+>", "");
		s = s.replace(' ', ' ');
		return s.trim();
	}

	/**
	 * Make the native dialogue invisible while keeping it functional. The chatbox's (otherwise empty) beige
	 * dialogue background and the non-interactive display widgets we redraw — speaker NAME, body TEXT, chat
	 * HEAD — are {@code setHidden(true)}. The INTERACTIVE widgets (the CONTINUE prompt and the option list)
	 * are instead made fully transparent via {@link #dim} ({@code setOpacity}, NOT {@code setHidden}): opacity
	 * is render-only, so they vanish visually yet still receive the game's own spacebar / number-key (1-5) /
	 * click handling — the player advances and selects entirely through the native client (no synthesized
	 * input). Re-applied every frame (the client rebuilds these widgets per line); both the hide and the dim
	 * are reverted at the top of the next apply(), so the beige background returns for normal chat once the
	 * dialogue closes.
	 */
	private void hideNative(Kind dialogueKind)
	{
		// The chatbox draws a beige panel behind any open dialogue; hide it so the relocated box stands alone
		// over a clean chatbox. Restored by restoreNative() the instant the dialogue closes (so normal chat
		// keeps its background) — this hides, never un-hides, a game-shown component.
		hide(InterfaceID.Chatbox.CHAT_BACKGROUND);
		switch (dialogueKind)
		{
			case NPC:
				hide(InterfaceID.ChatLeft.NAME);
				hide(InterfaceID.ChatLeft.TEXT);
				hide(InterfaceID.ChatLeft.HEAD);
				dim(InterfaceID.ChatLeft.CONTINUE);
				break;
			case PLAYER:
				hide(InterfaceID.ChatRight.NAME);
				hide(InterfaceID.ChatRight.TEXT);
				hide(InterfaceID.ChatRight.HEAD);
				dim(InterfaceID.ChatRight.CONTINUE);
				break;
			case OPTIONS:
				// Dim (not hide) the option list so native number-key (1-5) / click selection still works.
				dim(InterfaceID.Chatmenu.OPTIONS);
				break;
			case MESSAGE:
				// Hide the narration we redraw; dim (not hide) CONTINUE so native spacebar / click still advance it.
				hide(InterfaceID.Messagebox.TEXT);
				dim(InterfaceID.Messagebox.CONTINUE);
				break;
			case OBJECT:
				// Hide the item model we redraw; DIM (not hide) TEXT — its baked-in "Click here to continue" is
				// the continue target, so it must stay interactive for native spacebar / click to advance.
				hide(InterfaceID.Objectbox.ITEM);
				dim(InterfaceID.Objectbox.TEXT);
				break;
			case LEVELUP:
				// Hide the two message lines and the native (clipped) skill model we redraw; dim (not hide)
				// CONTINUE so the native spacebar / click still advances the level-up.
				hide(InterfaceID.LevelupDisplay.TEXT1);
				hide(InterfaceID.LevelupDisplay.TEXT2);
				hideLevelupModel();
				dim(InterfaceID.LevelupDisplay.CONTINUE);
				break;
			case CHAT_BOTH:
				// Hide both native heads (we relocate one), the shared name and text; dim CONTINUE so native
				// spacebar / click still advances.
				hide(InterfaceID.ChatBoth.LEFT);
				hide(InterfaceID.ChatBoth.RIGHT);
				hide(InterfaceID.ChatBoth.NAMES);
				hide(InterfaceID.ChatBoth.TEXT);
				dim(InterfaceID.ChatBoth.CONTINUE);
				break;
			case MESSAGE_TITLED:
				hide(InterfaceID.MessageboxTitled.TITLE);
				hide(InterfaceID.MessageboxTitled.TEXT);
				dim(InterfaceID.MessageboxTitled.CONTINUE);
				break;
			case NOTIFICATION:
				// NotificationDisplay has no dedicated CONTINUE component; advancing is driven by the client's
				// continue handler (spacebar / click on the box). Hide only the text we redraw and leave the
				// container / background untouched so click-to-continue still lands.
				hide(InterfaceID.NotificationDisplay.TITLE_TEXT);
				hide(InterfaceID.NotificationDisplay.MAIN_TEXT);
				break;
			case MESSAGE_URL:
				// Hide the body we redraw; dim CONTINUE. Leave the URL child alone so the link stays clickable.
				hide(InterfaceID.MessageboxUrl.TEXT);
				dim(InterfaceID.MessageboxUrl.CONTINUE);
				break;
			case OBJECT_DOUBLE:
				// Hide both item models and DIM (not hide) TEXT — its baked-in "Click here to continue" must
				// stay interactive for native spacebar / click to advance (mirrors the single OBJECT case).
				hide(InterfaceID.ObjectboxDouble.MODEL1);
				hide(InterfaceID.ObjectboxDouble.MODEL2);
				dim(InterfaceID.ObjectboxDouble.TEXT);
				break;
			default:
				break;
		}
	}

	private void hide(int componentId)
	{
		final Widget w = client.getWidget(componentId);
		if (w != null)
		{
			w.setHidden(true);
			hiddenComponents.add(componentId);
		}
	}

	/**
	 * Make a native interactive widget (and its option-line children) fully transparent WITHOUT hiding it, so
	 * it disappears visually but still receives native key/click handling (a {@code setHidden} widget does
	 * not). Reverted by {@link #restoreNative()}.
	 */
	private void dim(int componentId)
	{
		final Widget w = client.getWidget(componentId);
		if (w != null)
		{
			setOpacityDeep(w, OPACITY_TRANSPARENT);
			dimmedComponents.add(componentId);
		}
	}

	/** Set {@code opacity} on a widget and each of its children (dialogue option lines are child widgets). */
	private static void setOpacityDeep(Widget w, int opacity)
	{
		try
		{
			w.setOpacity(opacity);
			applyOpacity(w.getDynamicChildren(), opacity);
			applyOpacity(w.getStaticChildren(), opacity);
		}
		catch (Exception ignored)
		{
			// opacity is a cosmetic best-effort; a failure here must never break the frame
		}
	}

	private static void applyOpacity(Widget[] children, int opacity)
	{
		if (children == null)
		{
			return;
		}
		for (final Widget c : children)
		{
			if (c != null)
			{
				c.setOpacity(opacity);
			}
		}
	}

	/** Revert everything {@link #hideNative} changed — un-hide the hidden widgets and un-dim the transparent ones. */
	private void restoreNative()
	{
		for (final int id : hiddenComponents)
		{
			final Widget w = client.getWidget(id);
			if (w != null)
			{
				w.setHidden(false);
			}
		}
		hiddenComponents.clear();

		for (final int id : dimmedComponents)
		{
			final Widget w = client.getWidget(id);
			if (w != null)
			{
				setOpacityDeep(w, OPACITY_OPAQUE);
			}
		}
		dimmedComponents.clear();
	}

	/**
	 * Re-asserts our native display-widget hiding for the dialogue currently open. Subscribed to
	 * {@code WidgetLoaded} for the dialogue interfaces: when the game rebuilds the dialogue on a NEW LINE it
	 * briefly re-shows the native name/text/head we hide, which would flash for one frame until the next
	 * {@code BeforeRender} — re-hiding it the instant the interface reloads removes that flash. Only acts
	 * while a relocated dialogue is ALREADY active, so we never hide the native widgets before our replacement
	 * box exists on the first open. Cheap and idempotent; it does not touch the fade, the head, or the
	 * published content (the following {@code apply()} refreshes those).
	 */
	void reassertNativeVisibility()
	{
		if (kind == Kind.NONE)
		{
			return;
		}
		final Kind detected = detectOpenKind();
		// Mirror apply()'s suppression: never hide the native box for a bodyless, unskippable pause — the
		// plugin draws nothing for those, so the native UI must stay visible.
		if (detected != Kind.NONE && !bodylessUnskippablePause(detected))
		{
			hideNative(detected);
		}
	}

	private void renderHead(Widget src, Rectangle head)
	{
		final Widget parent = topAncestor(src);
		if (parent == null)
		{
			hideHead();
			return;
		}

		try
		{
			// Plugin-owned container that exclusively holds our MODEL widget. Recreating the model
			// child inside it (deleteAllChildren + createChild) resets the renderer's internal frame
			// counter to 0; because the container is ours, that wipe never touches game-owned
			// dynamic children on the interface root. We compare the parent by id, NOT by instance:
			// the ancestor walk hands back a fresh root wrapper on dialogue redraws, so an instance
			// check would treat every redraw as a new root, spawn a new container, and orphan the
			// old one (with its head still rendering) — the cause of heads stacking up.
			if (headContainer != null && headContainer.getParentId() != parent.getId())
			{
				// Genuine root change (e.g. fixed/resizable display switch): drop the stale
				// container first so its head can never linger.
				resetHead();
			}
			if (headContainer == null)
			{
				headContainer = parent.createChild(WidgetType.LAYER);
				headContainer.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
				headContainer.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
				headContainer.setOriginalX(0);
				headContainer.setOriginalY(0);
				createdHead = null;
				builtModelType = Integer.MIN_VALUE;
				builtModelId = Integer.MIN_VALUE;
				builtItemId = Integer.MIN_VALUE;
				builtAnimation = Integer.MIN_VALUE;
			}

			// Keep the container spanning the host so the model is never clipped and its coordinates
			// share the host's canvas origin (this also tracks canvas resizes).
			headContainer.setHidden(false);
			headContainer.setOriginalWidth(parent.getWidth());
			headContainer.setOriginalHeight(parent.getHeight());
			headContainer.revalidate();

			final Point hostLoc = parent.getCanvasLocation();
			final int ox = hostLoc != null ? hostLoc.getX() : 0;
			final int oy = hostLoc != null ? hostLoc.getY() : 0;
			// Position and size come from the scaled headBounds computed in apply() (single source of truth).
			// A MODEL widget's zoom is the camera DISTANCE, so the rendered head size is INVERSELY
			// proportional to it: a larger zoom pushes the head further away (smaller), a smaller zoom
			// pulls it closer (bigger). To shrink the head in step with the box we therefore DIVIDE the
			// native zoom by the scale — multiplying (as a viewport-style scale would suggest) enlarges the
			// head below 100%, which is what left it oversized. At 100% the native zoom is left untouched.
			final int hx = head.x;
			final int hy = head.y;
			final int headW = head.width;
			final int headH = head.height;
			final int baseZoom = src.getModelZoom() > 0 ? src.getModelZoom() : 512;
			final int zoom = Math.round(baseZoom / scale());

			final int modelType = src.getModelType();
			final int modelId = src.getModelId();
			// Item display widgets (the object box) carry their picture on itemId, not modelId.
			final int itemId = src.getItemId();
			final int itemQuantity = src.getItemQuantity() > 0 ? src.getItemQuantity() : 1;
			final int animation = src.getAnimationId();

			if (createdHead == null
				|| modelType != builtModelType
				|| modelId != builtModelId
				|| itemId != builtItemId
				|| animation != builtAnimation)
			{
				// Head or animation changed: recreate the MODEL widget so it starts at modelFrame 0.
				// This is what prevents the render thread from indexing a stale frame past the new
				// sequence's length (the ArrayIndexOutOfBounds that crashed the client).
				//
				// Hide the outgoing head FIRST. There is no API to delete a single dynamic child, and
				// deleteAllChildren() does not reliably clear our dynamically-created container (old
				// heads were observed lingering), so hiding the previous head is what actually keeps a
				// single head on screen. deleteAllChildren() is still called as best-effort cleanup.
				if (createdHead != null)
				{
					try
					{
						createdHead.setHidden(true);
					}
					catch (Exception ignored)
					{
						// outgoing head already gone; nothing to hide
					}
				}
				headContainer.deleteAllChildren();
				createdHead = headContainer.createChild(WidgetType.MODEL);
				createdHead.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
				createdHead.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
				createdHead.setOriginalWidth(headW);
				createdHead.setOriginalHeight(headH);
				createdHead.setOriginalX(hx - ox);
				createdHead.setOriginalY(hy - oy);
				createdHead.setModelZoom(zoom);
				createdHead.setRotationX(src.getRotationX());
				createdHead.setRotationY(src.getRotationY());
				createdHead.setRotationZ(src.getRotationZ());
				createdHead.setModelType(modelType);
				createdHead.setModelId(modelId);
				createdHead.setItemId(itemId);
				createdHead.setItemQuantity(itemQuantity);
				createdHead.setAnimationId(animation);
				createdHead.revalidate();

				builtModelType = modelType;
				builtModelId = modelId;
				builtItemId = itemId;
				builtAnimation = animation;
			}
			else
			{
				// Same head and animation: only refresh geometry (the box can move via config
				// offsets / canvas resize, and the size can change via the scale slider). Resizing in
				// place (vs. recreating) avoids restarting an animated head's frame counter, so a live
				// scale change never makes a talking head stutter. None of these touch the frame counter.
				createdHead.setHidden(false);
				createdHead.setOriginalWidth(headW);
				createdHead.setOriginalHeight(headH);
				createdHead.setOriginalX(hx - ox);
				createdHead.setOriginalY(hy - oy);
				createdHead.setModelZoom(zoom);
				createdHead.setRotationX(src.getRotationX());
				createdHead.setRotationY(src.getRotationY());
				createdHead.setRotationZ(src.getRotationZ());
				createdHead.revalidate();
			}
		}
		catch (Exception e)
		{
			log.debug("Head render skipped this frame", e);
		}
	}

	/**
	 * Drops our created head widgets so the next {@link #renderHead} rebuilds them on the CURRENT
	 * interface root. Hides them first (best-effort) so a still-live head can never linger as a
	 * duplicate; the null is what matters once a logout / world-hop has discarded the old tree.
	 * Used on a real root change, on a session-ending game state, and on shutdown ({@link #cleanup}).
	 */
	void resetHead()
	{
		try
		{
			if (createdHead != null)
			{
				createdHead.setHidden(true);
			}
			if (headContainer != null)
			{
				headContainer.deleteAllChildren();
				headContainer.setHidden(true);
			}
		}
		catch (Exception e)
		{
			log.debug("Failed to reset head widgets", e);
		}
		headContainer = null;
		createdHead = null;
		builtModelType = Integer.MIN_VALUE;
		builtModelId = Integer.MIN_VALUE;
		builtItemId = Integer.MIN_VALUE;
		builtAnimation = Integer.MIN_VALUE;
	}

	/** Hide the head and arm a fresh recreation (clean frame counter) for the next line/conversation. */
	private void hideHead()
	{
		try
		{
			if (createdHead != null)
			{
				createdHead.setHidden(true);
			}
			if (headContainer != null)
			{
				headContainer.setHidden(true);
			}
		}
		catch (Exception e)
		{
			log.debug("Failed to hide head widget", e);
		}
		builtModelType = Integer.MIN_VALUE;
		builtModelId = Integer.MIN_VALUE;
		builtItemId = Integer.MIN_VALUE;
		builtAnimation = Integer.MIN_VALUE;
	}

	/** Walk to the top-level interface root, which spans the canvas and does not clip on-screen children. */
	private static Widget topAncestor(Widget w)
	{
		Widget c = w;
		while (c.getParent() != null)
		{
			c = c.getParent();
		}
		return c;
	}

	/** Called on shutdown. */
	void cleanup()
	{
		restoreNative();
		resetHead();
		displayAlpha = 0f;
		lastFrameMs = 0L;
		revealing = false;
		skipRequested = false;
		revealedChars = 0;
		lastRevealBody = null;
		blipsDoneForLine = false;
	}
}
