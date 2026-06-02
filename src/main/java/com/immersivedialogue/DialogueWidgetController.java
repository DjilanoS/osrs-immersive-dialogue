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
import net.runelite.api.MenuAction;
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
 * <p><b>Animation:</b> animating a relocated head is opt-in ({@code animateHead}). A reused MODEL
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
		NONE, NPC, PLAYER, OPTIONS
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

	/** A published click target for an option: its native child index and on-screen rectangle. */
	static final class OptionHit
	{
		final int subid;
		final Rectangle rect;

		OptionHit(int subid, Rectangle rect)
		{
			this.subid = subid;
			this.rect = rect;
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
	private static final int NO_ANIM = -1;
	/** Below this the fade-out is treated as complete and the dialogue is fully cleared. */
	private static final float ALPHA_EPSILON = 0.01f;

	// Adaptive OPTIONS box sizing. The per-row text-height allowance is the configured text size plus this
	// buffer, over-estimating the runescape body line height so the box always covers the option rows the
	// overlay draws (their click rects must stay inside it).
	private static final int OPTION_TEXT_BUFFER = 4;
	private static final int OPTIONS_MAX_H = 280;

	private final Client client;
	private final ImmersiveDialogueConfig config;
	private final ConfigManager configManager;

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
	/** Whether the current speaker is the local player (selects which CONTINUE widget to use). */
	@Getter
	private volatile boolean playerSpeaker;
	/** Per-option click targets published by the overlay each frame (empty unless options are shown). */
	@Getter
	private volatile List<OptionHit> optionHits = Collections.emptyList();
	/** Native dialogue components we hid this frame, restored at the top of the next apply(). */
	private final List<Integer> hiddenComponents = new ArrayList<>();

	// Our head widgets / diagnostics (read by the debug overlay).
	@Getter
	private Widget headSource;
	@Getter
	private Widget host;
	@Getter
	private Widget headContainer;
	@Getter
	private Widget createdHead;

	private int builtModelType = Integer.MIN_VALUE;
	private int builtModelId = Integer.MIN_VALUE;
	private int builtAnimation = Integer.MIN_VALUE;

	/** Eased 0..1 visibility the overlay multiplies into its alpha, and the head mirrors as opacity. */
	@Getter
	private volatile float displayAlpha = 0f;
	/** Wall-clock of the previous frame, used to advance the fade independent of frame rate. */
	private long lastFrameMs = 0L;

	@Inject
	DialogueWidgetController(Client client, ImmersiveDialogueConfig config, ConfigManager configManager)
	{
		this.client = client;
		this.config = config;
		this.configManager = configManager;
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
		Kind detected = Kind.NONE;
		boolean isPlayer = false;
		if (visible(client.getWidget(InterfaceID.ChatLeft.UNIVERSE)))
		{
			detected = Kind.NPC;
		}
		else if (visible(client.getWidget(InterfaceID.ChatRight.UNIVERSE)))
		{
			detected = Kind.PLAYER;
			isPlayer = true;
		}
		else if (visible(client.getWidget(InterfaceID.Chatmenu.UNIVERSE)))
		{
			detected = Kind.OPTIONS;
		}
		final boolean open = detected != Kind.NONE;

		updateAlpha(open, dt);

		if (!open)
		{
			// Still fading out: retain last frame's content + head (only nudge the head's opacity).
			if (fadeActive() && displayAlpha > ALPHA_EPSILON)
			{
				applyHeadOpacity();
				return;
			}
			// Fully closed (or fade disabled): clear everything and hide the head.
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
		playerSpeaker = isPlayer;

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
			default:
				break;
		}

		final int cw = client.getCanvasWidth();
		final int ch = client.getCanvasHeight();
		// Options grow the box to fit their count; plain dialogue keeps the fixed height.
		final int boxH = (kind == Kind.OPTIONS) ? optionsBoxHeight() : BOX_H;
		final int x = ((cw - BOX_W) / 2) + config.horizontalOffset();
		final int y = ch - boxH - config.bottomMargin();
		bounds = new Rectangle(x, y, BOX_W, boxH);

		// Options publish their own hit-rects from the overlay's render pass; plain dialogue has none,
		// so clear any stale targets here.
		if (kind != Kind.OPTIONS)
		{
			optionHits = Collections.emptyList();
		}

		if (headSource != null && headSource.getModelType() > 0)
		{
			final int hx = isPlayer ? (bounds.x + bounds.width + HEAD_GAP) : (bounds.x - HEAD_W - HEAD_GAP);
			final int hy = bounds.y + ((bounds.height - HEAD_H) / 2);
			headBounds = new Rectangle(hx, hy, HEAD_W, HEAD_H);
			renderHead(headSource, isPlayer, bounds);
		}
		else
		{
			hideHead();
		}

		applyHeadOpacity();

		// Finally, hide the native chatbox dialogue we replace. Done last so every read above sees the
		// widgets visible; re-applied each frame because the client's clientscripts re-show them on
		// rebuild.
		hideNative(kind, isPlayer);
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
		playerSpeaker = false;
		options = Collections.emptyList();
		optionHits = Collections.emptyList();
		headSource = null;
		hideHead();
	}

	/** Published by the overlay each frame: the clickable rectangle for each option line. */
	void setOptionHits(List<OptionHit> hits)
	{
		optionHits = hits == null ? Collections.emptyList() : hits;
	}

	/**
	 * Advances the current relocated "click here to continue" NPC/player dialogue via its CONTINUE widget,
	 * mirroring a {@code WIDGET_CONTINUE} the player would otherwise trigger (a sanctioned API call, not a
	 * synthesized input event). Shared by the mouse (click) and keyboard (spacebar) listeners so both
	 * advance through the identical, already-verified path. MUST be invoked on the client thread.
	 */
	void continueDialogue()
	{
		if (kind != Kind.NPC && kind != Kind.PLAYER)
		{
			return;
		}
		final int continueId = playerSpeaker ? InterfaceID.ChatRight.CONTINUE : InterfaceID.ChatLeft.CONTINUE;
		final Widget cont = client.getWidget(continueId);
		if (cont == null)
		{
			// Final line / no continue button present (or already closed mid-fade): nothing to advance.
			return;
		}
		client.menuAction(-1, continueId, MenuAction.WIDGET_CONTINUE, 1, -1, "Continue", "");
	}

	/**
	 * Adaptive height for the OPTIONS box so it snugly fits its option count instead of being a fixed,
	 * cavernous box. Deliberately over-estimates line heights so the box always covers the rows the
	 * overlay draws; the spacing constants are shared with {@link ImmersiveDialogueOverlay} so the two
	 * never drift.
	 */
	private int optionsBoxHeight()
	{
		int h = ImmersiveDialogueOverlay.INSET * 2; // top + bottom padding
		for (final Option o : options)
		{
			if (o.subid == 0)
			{
				// "Select an Option" header, drawn in the larger title font as a plain line.
				h += (config.titleFontSize() + 6) + ImmersiveDialogueOverlay.LINE_GAP;
			}
			else
			{
				h += (config.textSize() + OPTION_TEXT_BUFFER) + (ImmersiveDialogueOverlay.OPTION_PAD * 2) + ImmersiveDialogueOverlay.OPTION_GAP;
			}
		}
		return Math.min(h, OPTIONS_MAX_H);
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
	 * {@code subid}. That subid is what {@link Widget#getChild(int)} and the menu use to resolve the
	 * option, so handing it back as {@code param0} of a {@code WIDGET_CONTINUE} action selects exactly
	 * the clicked option. Child {@code subid 0} is the "Select an Option" header.
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
			if (highlighted)
			{
				// Quest Helper prepends "[N] " numbering to the highlighted option only; drop it.
				t = t.replaceFirst("^\\s*\\[\\d+\\]\\s*", "");
			}
			out.add(new Option(subid, t, highlighted));
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

	/** Hide the native chatbox dialogue we replace (re-applied every frame; see {@link #apply()}). */
	private void hideNative(Kind dialogueKind, boolean isPlayer)
	{
		// Keep RuneScape's beige dialogue background (Chatbox.CHAT_BACKGROUND, which the game shows when a
		// dialogue opens) and force the native chat display back on OVER it — the game hides the chat
		// whenever a dialogue opens — so the chatbox shows the player's normal chat on the beige (the native
		// opaque-chatbox look) instead of an empty box. The beige is the chatbox's background layer and the
		// chat lines draw after it, so the chat naturally sits in front; no z-reordering is needed. Re-asserted
		// every frame; the game's own message-layer-close script re-hides the chat once the dialogue ends, so
		// no restore is needed.
		show(InterfaceID.Chatbox.CHATDISPLAY);
		show(InterfaceID.Chatbox.SCROLLAREA);
		switch (dialogueKind)
		{
			case NPC:
				hide(InterfaceID.ChatLeft.CONTENT);
				hide(InterfaceID.ChatLeft.NAME);
				hide(InterfaceID.ChatLeft.TEXT);
				hide(InterfaceID.ChatLeft.CONTINUE);
				hide(InterfaceID.ChatLeft.HEAD);
				break;
			case PLAYER:
				hide(InterfaceID.ChatRight.CONTENT);
				hide(InterfaceID.ChatRight.NAME);
				hide(InterfaceID.ChatRight.TEXT);
				hide(InterfaceID.ChatRight.CONTINUE);
				hide(InterfaceID.ChatRight.HEAD);
				break;
			case OPTIONS:
				hide(InterfaceID.Chatmenu.OPTIONS);
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
	 * Force a native component visible (the inverse of {@link #hide}). Used to re-show the chat display the
	 * game hides during dialogue. Deliberately NOT tracked for restore: its natural post-dialogue state is
	 * already visible and the game re-shows it on dialogue close, so re-asserting it each open frame and
	 * leaving it untouched otherwise is self-correcting.
	 */
	private void show(int componentId)
	{
		final Widget w = client.getWidget(componentId);
		if (w != null)
		{
			w.setHidden(false);
		}
	}

	/** Restore everything {@link #hideNative} hid, so the chatbox is never left blank. */
	private void restoreNative()
	{
		if (hiddenComponents.isEmpty())
		{
			return;
		}
		for (final int id : hiddenComponents)
		{
			final Widget w = client.getWidget(id);
			if (w != null)
			{
				w.setHidden(false);
			}
		}
		hiddenComponents.clear();
	}

	/**
	 * Re-asserts our native-dialogue hiding (and keeps the chat shown) for the dialogue currently open.
	 * Subscribed to {@code WidgetLoaded} for the dialogue interfaces: when the game rebuilds the dialogue on
	 * a NEW LINE it briefly re-shows the native dialogue (and re-hides the chat we force-show), which would
	 * flash for one frame until the next {@code BeforeRender} — re-hiding it the instant the interface
	 * reloads removes that flash. Only acts while a relocated dialogue is ALREADY active, so we never hide the
	 * native dialogue before our replacement box exists on the first open. Cheap and idempotent; it does not
	 * touch the fade, the head, or the published content (the following {@code apply()} refreshes those).
	 */
	void reassertNativeVisibility()
	{
		if (kind == Kind.NONE)
		{
			return;
		}
		Kind detected = Kind.NONE;
		boolean isPlayer = false;
		if (visible(client.getWidget(InterfaceID.ChatLeft.UNIVERSE)))
		{
			detected = Kind.NPC;
		}
		else if (visible(client.getWidget(InterfaceID.ChatRight.UNIVERSE)))
		{
			detected = Kind.PLAYER;
			isPlayer = true;
		}
		else if (visible(client.getWidget(InterfaceID.Chatmenu.UNIVERSE)))
		{
			detected = Kind.OPTIONS;
		}
		if (detected != Kind.NONE)
		{
			hideNative(detected, isPlayer);
		}
	}

	private void renderHead(Widget src, boolean isPlayer, Rectangle box)
	{
		final Widget parent = topAncestor(src);
		host = parent;
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
				// Genuine root change (e.g. fixed/resizable display switch): retire the stale
				// container first so its head can never linger.
				retireContainer();
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
			final int hx = isPlayer ? (box.x + box.width + HEAD_GAP) : (box.x - HEAD_W - HEAD_GAP);
			final int hy = box.y + ((box.height - HEAD_H) / 2);

			final int modelType = src.getModelType();
			final int modelId = src.getModelId();
			final int animation = config.animateHead() ? src.getAnimationId() : NO_ANIM;

			if (createdHead == null
				|| modelType != builtModelType
				|| modelId != builtModelId
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
				createdHead.setOriginalWidth(HEAD_W);
				createdHead.setOriginalHeight(HEAD_H);
				createdHead.setOriginalX(hx - ox);
				createdHead.setOriginalY(hy - oy);
				createdHead.setModelZoom(src.getModelZoom() > 0 ? src.getModelZoom() : 512);
				createdHead.setRotationX(src.getRotationX());
				createdHead.setRotationY(src.getRotationY());
				createdHead.setRotationZ(src.getRotationZ());
				createdHead.setModelType(modelType);
				createdHead.setModelId(modelId);
				createdHead.setAnimationId(animation);
				createdHead.revalidate();

				builtModelType = modelType;
				builtModelId = modelId;
				builtAnimation = animation;
			}
			else
			{
				// Same head and animation: only refresh geometry (the box can move via config
				// offsets / canvas resize). None of these touch the frame counter.
				createdHead.setHidden(false);
				createdHead.setOriginalX(hx - ox);
				createdHead.setOriginalY(hy - oy);
				createdHead.setModelZoom(src.getModelZoom() > 0 ? src.getModelZoom() : 512);
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

	/** Drop the container we're about to abandon (real root change), hiding+clearing it so no head lingers. */
	private void retireContainer()
	{
		try
		{
			if (createdHead != null)
			{
				createdHead.setHidden(true);
			}
			headContainer.deleteAllChildren();
			headContainer.setHidden(true);
		}
		catch (Exception e)
		{
			log.debug("Failed to retire head container", e);
		}
		headContainer = null;
		createdHead = null;
		builtModelType = Integer.MIN_VALUE;
		builtModelId = Integer.MIN_VALUE;
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
		try
		{
			if (headContainer != null)
			{
				headContainer.deleteAllChildren();
				headContainer.setHidden(true);
			}
		}
		catch (Exception e)
		{
			log.debug("Failed to clean up head widgets", e);
		}
		headContainer = null;
		createdHead = null;
		builtModelType = Integer.MIN_VALUE;
		builtModelId = Integer.MIN_VALUE;
		builtAnimation = Integer.MIN_VALUE;
		displayAlpha = 0f;
		lastFrameMs = 0L;
	}
}
