package com.immersivedialogue;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws the avatar backdrop panel behind the relocated chat-head.
 *
 * <p>Kept apart from {@link ImmersiveDialogueOverlay} purely because of layering: the chat-head is a real
 * MODEL widget (created by {@link DialogueWidgetController}), so its panel must be drawn on the
 * UNDER_WIDGETS layer to sit behind it, while the dialogue box and text are drawn ABOVE_WIDGETS so they
 * cover full-screen cutscene widgets. Both fade together via the controller's eased visibility.</p>
 */
class ImmersiveDialogueBackdropOverlay extends Overlay
{
	private final DialogueWidgetController controller;
	private final ImmersiveDialogueConfig config;

	@Inject
	ImmersiveDialogueBackdropOverlay(DialogueWidgetController controller, ImmersiveDialogueConfig config)
	{
		this.controller = controller;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		// UNDER_WIDGETS so the panel stays behind the live MODEL chat-head instead of painting over it.
		setLayer(OverlayLayer.UNDER_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.avatarBackground())
		{
			return null;
		}

		// Only drawn when a head is actually relocated this frame (message boxes without a model have none).
		final Rectangle head = controller.getHeadBounds();
		if (head == null)
		{
			return null;
		}

		// Fade with the box: same eased visibility the dialogue overlay multiplies into its alpha.
		final float alpha = clamp01(controller.getDisplayAlpha());
		if (alpha <= 0f)
		{
			return null;
		}

		final Composite oldComposite = g.getComposite();
		if (alpha < 1f)
		{
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		}
		try
		{
			// Same padding, rounding and frame as the dialogue box so the two panels read as one surface.
			final int pad = config.backdropPadding();
			drawPanel(g, head.x - pad, head.y - pad, head.width + (pad * 2), head.height + (pad * 2),
				config.cornerRadius(), config.avatarBackgroundColor());
		}
		finally
		{
			g.setComposite(oldComposite);
		}

		return null;
	}

	/** Fills a backdrop panel (square when {@code radius == 0}, else rounded) and frames it if enabled. */
	private void drawPanel(Graphics2D g, int x, int y, int w, int h, int radius, Color fill)
	{
		g.setColor(fill);
		if (radius > 0)
		{
			g.fillRoundRect(x, y, w, h, radius, radius);
		}
		else
		{
			g.fillRect(x, y, w, h);
		}

		if (config.showBorder())
		{
			final int bw = config.borderWidth();
			final int half = bw / 2;
			final Stroke oldStroke = g.getStroke();
			g.setStroke(new BasicStroke(bw));
			g.setColor(config.borderColor());
			// Inset the stroke by half its width so the frame stays within the filled panel.
			if (radius > 0)
			{
				g.drawRoundRect(x + half, y + half, w - bw, h - bw, radius, radius);
			}
			else
			{
				g.drawRect(x + half, y + half, w - bw, h - bw);
			}
			g.setStroke(oldStroke);
		}
	}

	private static float clamp01(float v)
	{
		return v < 0f ? 0f : (v > 1f ? 1f : v);
	}
}
