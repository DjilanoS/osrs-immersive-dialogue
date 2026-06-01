package com.immersivedialogue;

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

	// Approximate native dialogue size; used for the bottom-center backdrop box.
	private static final int BOX_W = 506;
	private static final int BOX_H = 129;
	private static final int HEAD_W = 110;
	private static final int HEAD_H = 140;
	private static final int NO_ANIM = -1;

	private final Client client;
	private final ImmersiveDialogueConfig config;

	/** Canvas bounds of the bottom-center box this frame, or {@code null} when no dialogue is open. */
	@Getter
	private Rectangle bounds;

	// Extracted text content for the overlay to draw.
	@Getter
	private Kind kind = Kind.NONE;
	@Getter
	private String speakerName;
	@Getter
	private String bodyText;
	@Getter
	private List<String> options = Collections.emptyList();

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

	@Inject
	DialogueWidgetController(Client client, ImmersiveDialogueConfig config)
	{
		this.client = client;
		this.config = config;
	}

	/** Re-applied every frame from {@code BeforeRender}. */
	void apply()
	{
		bounds = null;
		kind = Kind.NONE;
		speakerName = null;
		bodyText = null;
		options = Collections.emptyList();
		headSource = null;

		if (!config.relocate())
		{
			hideHead();
			return;
		}

		final Widget npc = client.getWidget(InterfaceID.ChatLeft.UNIVERSE);
		final Widget player = client.getWidget(InterfaceID.ChatRight.UNIVERSE);
		final Widget menu = client.getWidget(InterfaceID.Chatmenu.UNIVERSE);

		final boolean isPlayer;
		if (visible(npc))
		{
			isPlayer = false;
			kind = Kind.NPC;
			headSource = client.getWidget(InterfaceID.ChatLeft.HEAD);
			speakerName = text(InterfaceID.ChatLeft.NAME);
			bodyText = text(InterfaceID.ChatLeft.TEXT);
		}
		else if (visible(player))
		{
			isPlayer = true;
			kind = Kind.PLAYER;
			headSource = client.getWidget(InterfaceID.ChatRight.HEAD);
			speakerName = text(InterfaceID.ChatRight.NAME);
			bodyText = text(InterfaceID.ChatRight.TEXT);
		}
		else if (visible(menu))
		{
			isPlayer = false;
			kind = Kind.OPTIONS;
			options = readOptions(client.getWidget(InterfaceID.Chatmenu.OPTIONS));
		}
		else
		{
			hideHead();
			return;
		}

		final int cw = client.getCanvasWidth();
		final int ch = client.getCanvasHeight();
		final int x = ((cw - BOX_W) / 2) + config.horizontalOffset();
		final int y = ch - BOX_H - config.bottomMargin();
		bounds = new Rectangle(x, y, BOX_W, BOX_H);

		if (headSource != null && headSource.getModelType() > 0)
		{
			renderHead(headSource, isPlayer, bounds);
		}
		else
		{
			hideHead();
		}
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

	private List<String> readOptions(Widget optionsWidget)
	{
		final List<String> out = new ArrayList<>();
		if (optionsWidget == null)
		{
			return out;
		}
		collectOptionText(optionsWidget.getStaticChildren(), out);
		collectOptionText(optionsWidget.getDynamicChildren(), out);
		collectOptionText(optionsWidget.getChildren(), out);
		return out;
	}

	private static void collectOptionText(Widget[] children, List<String> out)
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
			final String t = clean(c.getText());
			if (t != null && !t.isEmpty() && !out.contains(t))
			{
				out.add(t);
			}
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
			final int hx = isPlayer ? (box.x + box.width) : (box.x - HEAD_W);
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
	}
}
