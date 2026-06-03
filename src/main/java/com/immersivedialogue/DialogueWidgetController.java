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

	/** A clickable option's native child index ({@code subid}) and the on-screen rectangle the overlay drew it in. */
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
	// Widget opacity: 0 = fully opaque, 255 = fully transparent (RuneLite's setOpacity convention).
	private static final int OPACITY_OPAQUE = 0;
	private static final int OPACITY_TRANSPARENT = 255;
	/** Below this the fade-out is treated as complete and the dialogue is fully cleared. */
	private static final float ALPHA_EPSILON = 0.01f;
	/** Animation id meaning "no animation" — a static head. */
	private static final int NO_ANIM = -1;

	// Adaptive OPTIONS box sizing. The per-row text-height allowance is the configured text size plus this
	// buffer, over-estimating the runescape body line height so the box always covers the option rows the
	// overlay draws.
	private static final int OPTION_TEXT_BUFFER = 4;
	private static final int OPTIONS_MAX_H = 300;

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
	/** Per-option click target rectangles published for the mouse listener; empty unless OPTIONS is open. */
	@Getter
	private volatile List<OptionHit> optionHits = Collections.emptyList();
	/** True while the open dialogue is a player (right) line, so {@link #continueDialogue()} targets the right CONTINUE. */
	private volatile boolean playerSpeaker;

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
		// The overlay maintains optionHits during OPTIONS (refreshed each render); clearing it per-frame here
		// would race the click thread. Only clear it when there are no options.
		if (detected != Kind.OPTIONS)
		{
			optionHits = Collections.emptyList();
		}
		headSource = null;
		playerSpeaker = isPlayer;
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
			default:
				break;
		}

		final int cw = client.getCanvasWidth();
		final int ch = client.getCanvasHeight();
		// Options grow the box to fit their count; plain dialogue keeps the fixed height.
		final int boxH = (kind == Kind.OPTIONS) ? optionsBoxHeight() : BOX_H;
		final int x = ((cw - BOX_W) / 2) + effectiveHorizontalOffset();
		final int y = ch - boxH - effectiveBottomMargin();
		bounds = new Rectangle(x, y, BOX_W, boxH);

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

		// Finally, suppress the native dialogue (hide the display widgets, dim the interactive ones). Done last
		// so every read above sees them normal; re-applied each frame because the client rebuilds them per line.
		hideNative(kind);
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
		options = Collections.emptyList();
		optionHits = Collections.emptyList();
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
		// Reserve a line at the bottom for the "Use keys [1] - [N]" hint (drawn in the body font).
		h += config.textSize() + OPTION_TEXT_BUFFER + ImmersiveDialogueOverlay.LINE_GAP;
		return Math.min(h, OPTIONS_MAX_H);
	}

	/** Publishes the per-option click targets the overlay computed this frame (read by the mouse listener). */
	void setOptionHits(List<OptionHit> hits)
	{
		optionHits = hits == null ? Collections.emptyList() : hits;
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
		final int max = Math.max(0, (client.getCanvasWidth() - BOX_W) / 2);
		final int offset = dragActive ? (dragBaseHorizontalOffset + dragDx) : config.horizontalOffset();
		return clamp(offset, -max, max);
	}

	/** Live drag value while dragging (clamped to keep the box on-screen), else the configured margin. */
	private int effectiveBottomMargin()
	{
		final int max = Math.max(0, client.getCanvasHeight() - BOX_H);
		final int margin = dragActive ? (dragBaseBottomMargin - dragDy) : config.bottomMargin();
		return clamp(margin, 0, max);
	}

	private static int clamp(int v, int min, int max)
	{
		return v < min ? min : (v > max ? max : v);
	}

	/** Advances the relocated NPC/player dialogue via its CONTINUE widget; must be invoked on the client thread. */
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
		Kind detected = Kind.NONE;
		if (visible(client.getWidget(InterfaceID.ChatLeft.UNIVERSE)))
		{
			detected = Kind.NPC;
		}
		else if (visible(client.getWidget(InterfaceID.ChatRight.UNIVERSE)))
		{
			detected = Kind.PLAYER;
		}
		else if (visible(client.getWidget(InterfaceID.Chatmenu.UNIVERSE)))
		{
			detected = Kind.OPTIONS;
		}
		if (detected != Kind.NONE)
		{
			hideNative(detected);
		}
	}

	private void renderHead(Widget src, boolean isPlayer, Rectangle box)
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
		resetHead();
		displayAlpha = 0f;
		lastFrameMs = 0L;
	}
}
