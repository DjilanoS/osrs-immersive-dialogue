package com.immersivedialogue;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws the translucent dialogue box plus the speaker name, body text and options. The animated
 * head is rendered separately by {@link DialogueWidgetController} (a MODEL widget), just outside
 * this box on the speaker's natural side.
 */
class ImmersiveDialogueOverlay extends Overlay
{
	// Package-private: the controller reads these to size the adaptive options box (see optionsBoxHeight).
	static final int INSET = 16;
	static final int LINE_GAP = 3;
	static final int OPTION_PAD = 5;
	static final int OPTION_GAP = 6;
	private static final Color HOVER_COLOR = new Color(255, 255, 255, 45);
	private static final String CONTINUE_TEXT = "Click here to continue";
	private static final Color CONTINUE_COLOR = new Color(255, 255, 255, 165);
	private static final Color CONTINUE_HOVER_COLOR = Color.WHITE;

	private final Client client;
	private final DialogueWidgetController controller;
	private final ImmersiveDialogueConfig config;

	@Inject
	ImmersiveDialogueOverlay(Client client, DialogueWidgetController controller, ImmersiveDialogueConfig config)
	{
		this.client = client;
		this.controller = controller;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.UNDER_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		final Rectangle b = controller.getBounds();
		if (b == null)
		{
			return null;
		}

		// Fade: scale every alpha the overlay draws by the controller's eased visibility. Done with a
		// composite so the backdrop, border, avatar panel and text all fade together as one surface.
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
			final int radius = config.cornerRadius();

			// Avatar backdrop, behind the chat-head. Drawn first so the main box sits over its inner edge.
			// The head itself is a real MODEL widget rendered above this UNDER_WIDGETS overlay, so this
			// panel lands behind it.
			if (config.avatarBackground())
			{
				final Rectangle hb = controller.getHeadBounds();
				if (hb != null)
				{
					final int hp = config.backdropPadding();
					drawPanel(g, hb.x - hp, hb.y - hp, hb.width + (hp * 2), hb.height + (hp * 2),
						radius, config.avatarBackgroundColor());
				}
			}

			// Main backdrop.
			final int pad = config.backdropPadding();
			drawPanel(g, b.x - pad, b.y - pad, b.width + (pad * 2), b.height + (pad * 2),
				radius, config.backgroundColor());

			final Font nameFont = FontManager.getRunescapeBoldFont().deriveFont((float) config.titleFontSize());
			final Font bodyFont = FontManager.getRunescapeFont().deriveFont((float) config.textSize());
			final Color nameColor = config.nameColor();
			final Color textColor = config.textColor();

			switch (controller.getKind())
			{
				case NPC:
				case PLAYER:
					drawConversation(g, b, nameFont, bodyFont, nameColor, textColor);
					break;
				case OPTIONS:
					drawOptions(g, b, nameFont, bodyFont, nameColor, textColor);
					break;
				default:
					break;
			}
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

	private void drawConversation(Graphics2D g, Rectangle b, Font nameFont, Font bodyFont,
		Color nameColor, Color textColor)
	{
		final int maxWidth = b.width - (INSET * 2);
		final List<Line> lines = new ArrayList<>();

		final String name = controller.getSpeakerName();
		if (name != null && !name.isEmpty())
		{
			lines.add(new Line(name, nameFont, nameColor, -1));
		}

		final String body = controller.getBodyText();
		if (body != null && !body.isEmpty())
		{
			final FontMetrics bfm = g.getFontMetrics(bodyFont);
			for (final String wrapped : wrap(body, bfm, maxWidth))
			{
				lines.add(new Line(wrapped, bodyFont, textColor, -1));
			}
		}

		drawTextBlock(g, b, lines);
		// Plain NPC/player dialogue advances on a click anywhere in the box; hint at that affordance.
		drawContinuePrompt(g, b, bodyFont);
	}

	/** A bottom-centered "click to continue" hint that brightens while the cursor is over the box. */
	private void drawContinuePrompt(Graphics2D g, Rectangle b, Font font)
	{
		final FontMetrics fm = g.getFontMetrics(font);
		final int x = b.x + ((b.width - fm.stringWidth(CONTINUE_TEXT)) / 2);
		final int y = b.y + b.height - fm.getDescent() - (INSET / 2);

		final Point mouse = client.getMouseCanvasPosition();
		final boolean hover = mouse != null && b.contains(mouse.getX(), mouse.getY());

		g.setFont(font);
		g.setColor(Color.BLACK);
		g.drawString(CONTINUE_TEXT, x + 1, y + 1);
		g.setColor(hover ? CONTINUE_HOVER_COLOR : CONTINUE_COLOR);
		g.drawString(CONTINUE_TEXT, x, y);
	}

	private void drawOptions(Graphics2D g, Rectangle b, Font nameFont, Font bodyFont,
		Color nameColor, Color textColor)
	{
		final List<Line> lines = new ArrayList<>();
		for (final DialogueWidgetController.Option option : controller.getOptions())
		{
			// Child subid 0 is the "Select an Option" header: render it as the non-clickable title.
			final boolean header = option.subid == 0;
			lines.add(new Line(option.text, header ? nameFont : bodyFont,
				header ? nameColor : textColor, header ? -1 : option.subid));
		}
		controller.setOptionHits(drawTextBlock(g, b, lines));
	}

	/**
	 * Draws a block of lines top-aligned within the box, each horizontally centered. A line carrying a
	 * non-negative subid is a clickable option: a full-width hit rectangle is recorded for it and the
	 * line under the cursor gets a hover highlight. Returns the option hit targets (empty for plain text).
	 */
	private List<DialogueWidgetController.OptionHit> drawTextBlock(Graphics2D g, Rectangle b, List<Line> lines)
	{
		final List<DialogueWidgetController.OptionHit> hits = new ArrayList<>();
		if (lines.isEmpty())
		{
			return hits;
		}

		final Point mouse = client.getMouseCanvasPosition();
		int y = b.y + INSET;
		for (final Line line : lines)
		{
			final FontMetrics fm = g.getFontMetrics(line.font);
			final int x = b.x + ((b.width - fm.stringWidth(line.text)) / 2);

			if (line.subid >= 0)
			{
				// Clickable option: a padded, comfortably-spaced hover/hit row.
				final int rowH = fm.getAscent() + fm.getDescent() + (OPTION_PAD * 2);
				final Rectangle hit = new Rectangle(b.x, y, b.width, rowH);
				if (mouse != null && hit.contains(mouse.getX(), mouse.getY()))
				{
					g.setColor(HOVER_COLOR);
					g.fillRect(hit.x, hit.y, hit.width, hit.height);
				}
				hits.add(new DialogueWidgetController.OptionHit(line.subid, hit));

				final int baseline = y + OPTION_PAD + fm.getAscent();
				g.setFont(line.font);
				g.setColor(Color.BLACK);
				g.drawString(line.text, x + 1, baseline + 1);
				g.setColor(line.color);
				g.drawString(line.text, x, baseline);
				y += rowH + OPTION_GAP;
			}
			else
			{
				// Plain line (title / body / "Select an Option" header): original tight spacing.
				y += fm.getAscent();
				g.setFont(line.font);
				// shadow for legibility
				g.setColor(Color.BLACK);
				g.drawString(line.text, x + 1, y + 1);
				g.setColor(line.color);
				g.drawString(line.text, x, y);
				y += fm.getDescent() + LINE_GAP;
			}
		}
		return hits;
	}

	private static List<String> wrap(String text, FontMetrics fm, int maxWidth)
	{
		final List<String> out = new ArrayList<>();
		for (final String paragraph : text.split("\n", -1))
		{
			final String[] words = paragraph.split(" ");
			StringBuilder cur = new StringBuilder();
			for (final String word : words)
			{
				final String probe = cur.length() == 0 ? word : cur + " " + word;
				if (fm.stringWidth(probe) <= maxWidth)
				{
					cur.setLength(0);
					cur.append(probe);
				}
				else
				{
					if (cur.length() > 0)
					{
						out.add(cur.toString());
					}
					cur.setLength(0);
					cur.append(word);
				}
			}
			out.add(cur.toString());
		}
		return out;
	}

	private static final class Line
	{
		private final String text;
		private final Font font;
		private final Color color;
		/** Native option child index for a clickable option line, or {@code -1} for plain text. */
		private final int subid;

		private Line(String text, Font font, Color color, int subid)
		{
			this.text = text;
			this.font = font;
			this.color = color;
			this.subid = subid;
		}
	}
}
