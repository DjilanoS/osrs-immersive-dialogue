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
 * <p><b>Animation:</b> the head is <b>static by default</b>. Animating a relocated head is opt-in
 * ({@code animateHead}) because a created MODEL widget does not always loop a sequence safely — the
 * client renderer can index one past the last frame and crash ({@code ArrayIndexOutOfBounds}) on its
 * own render thread, where we cannot catch it. A static head never advances a frame, so it never
 * crashes. When animation is enabled we reset the frame (set {@code animationId = -1} for a frame,
 * then apply the real animation) on every model <i>and</i> animation change as a best effort.</p>
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

	// Our single head widget / diagnostics (read by the debug overlay).
	@Getter
	private Widget headSource;
	@Getter
	private Widget host;
	@Getter
	private Widget createdHead;

	private int builtModelType = Integer.MIN_VALUE;
	private int builtModelId = Integer.MIN_VALUE;
	private int appliedAnimation = Integer.MIN_VALUE;

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
			// Create the single head widget exactly once. Never create a second one.
			if (createdHead == null)
			{
				createdHead = parent.createChild(WidgetType.MODEL);
				createdHead.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
				createdHead.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
				builtModelType = Integer.MIN_VALUE;
				builtModelId = Integer.MIN_VALUE;
				appliedAnimation = Integer.MIN_VALUE;
			}

			final Point hostLoc = parent.getCanvasLocation();
			final int ox = hostLoc != null ? hostLoc.getX() : 0;
			final int oy = hostLoc != null ? hostLoc.getY() : 0;
			final int hx = isPlayer ? (box.x + box.width) : (box.x - HEAD_W);
			final int hy = box.y + ((box.height - HEAD_H) / 2);

			createdHead.setHidden(false);
			createdHead.setOriginalWidth(HEAD_W);
			createdHead.setOriginalHeight(HEAD_H);
			createdHead.setOriginalX(hx - ox);
			createdHead.setOriginalY(hy - oy);
			createdHead.setModelZoom(src.getModelZoom() > 0 ? src.getModelZoom() : 512);
			createdHead.setRotationX(src.getRotationX());
			createdHead.setRotationY(src.getRotationY());
			createdHead.setRotationZ(src.getRotationZ());

			final int modelType = src.getModelType();
			final int modelId = src.getModelId();
			final int animation = config.animateHead() ? src.getAnimationId() : NO_ANIM;

			if (modelType != builtModelType || modelId != builtModelId)
			{
				// New head: swap model, force animation off this frame (resets the frame counter).
				createdHead.setModelType(modelType);
				createdHead.setModelId(modelId);
				createdHead.setAnimationId(NO_ANIM);
				builtModelType = modelType;
				builtModelId = modelId;
				appliedAnimation = NO_ANIM;
			}
			else if (appliedAnimation != animation)
			{
				if (animation != NO_ANIM && appliedAnimation != NO_ANIM)
				{
					// Animation changed without a model change: drop to -1 this frame first so the
					// next frame's -1 -> anim transition restarts the frame counter at 0.
					createdHead.setAnimationId(NO_ANIM);
					appliedAnimation = NO_ANIM;
				}
				else
				{
					createdHead.setAnimationId(animation);
					appliedAnimation = animation;
				}
			}

			createdHead.revalidate();
		}
		catch (Exception e)
		{
			log.debug("Head render skipped this frame", e);
		}
	}

	/** Hide the single head and arm a fresh animation reset for the next line/conversation. */
	private void hideHead()
	{
		if (createdHead == null)
		{
			return;
		}
		try
		{
			createdHead.setHidden(true);
			createdHead.setAnimationId(NO_ANIM);
		}
		catch (Exception e)
		{
			log.debug("Failed to hide head widget", e);
		}
		builtModelType = Integer.MIN_VALUE;
		builtModelId = Integer.MIN_VALUE;
		appliedAnimation = Integer.MIN_VALUE;
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
		hideHead();
		createdHead = null;
	}
}
