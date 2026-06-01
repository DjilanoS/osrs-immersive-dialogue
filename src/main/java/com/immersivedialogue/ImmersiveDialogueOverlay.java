package com.immersivedialogue;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
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
	private static final int INSET = 16;
	private static final int LINE_GAP = 3;

	private final DialogueWidgetController controller;
	private final ImmersiveDialogueConfig config;

	@Inject
	ImmersiveDialogueOverlay(DialogueWidgetController controller, ImmersiveDialogueConfig config)
	{
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

		// Backdrop.
		final int pad = config.backdropPadding();
		g.setColor(config.backgroundColor());
		g.fillRoundRect(b.x - pad, b.y - pad, b.width + (pad * 2), b.height + (pad * 2), 18, 18);

		final Font nameFont = FontManager.getRunescapeBoldFont();
		final Font bodyFont = FontManager.getRunescapeFont();
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

		return null;
	}

	private void drawConversation(Graphics2D g, Rectangle b, Font nameFont, Font bodyFont,
		Color nameColor, Color textColor)
	{
		final int maxWidth = b.width - (INSET * 2);
		final List<Line> lines = new ArrayList<>();

		final String name = controller.getSpeakerName();
		if (name != null && !name.isEmpty())
		{
			lines.add(new Line(name, nameFont, nameColor));
		}

		final String body = controller.getBodyText();
		if (body != null && !body.isEmpty())
		{
			final FontMetrics bfm = g.getFontMetrics(bodyFont);
			for (final String wrapped : wrap(body, bfm, maxWidth))
			{
				lines.add(new Line(wrapped, bodyFont, textColor));
			}
		}

		drawCenteredBlock(g, b, lines);
	}

	private void drawOptions(Graphics2D g, Rectangle b, Font nameFont, Font bodyFont,
		Color nameColor, Color textColor)
	{
		final List<String> options = controller.getOptions();
		final List<Line> lines = new ArrayList<>();
		for (int i = 0; i < options.size(); i++)
		{
			// The first entry is the "Select an Option" header.
			final boolean header = i == 0;
			lines.add(new Line(options.get(i), header ? nameFont : bodyFont, header ? nameColor : textColor));
		}
		drawCenteredBlock(g, b, lines);
	}

	/** Vertically center a block of lines within the box and draw each horizontally centered. */
	private void drawCenteredBlock(Graphics2D g, Rectangle b, List<Line> lines)
	{
		if (lines.isEmpty())
		{
			return;
		}

		int total = 0;
		for (final Line line : lines)
		{
			total += g.getFontMetrics(line.font).getHeight() + LINE_GAP;
		}
		total -= LINE_GAP;

		int y = b.y + ((b.height - total) / 2);
		for (final Line line : lines)
		{
			final FontMetrics fm = g.getFontMetrics(line.font);
			y += fm.getAscent();
			final int x = b.x + ((b.width - fm.stringWidth(line.text)) / 2);
			g.setFont(line.font);
			// shadow for legibility
			g.setColor(Color.BLACK);
			g.drawString(line.text, x + 1, y + 1);
			g.setColor(line.color);
			g.drawString(line.text, x, y);
			y += fm.getDescent() + LINE_GAP;
		}
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

		private Line(String text, Font font, Color color)
		{
			this.text = text;
			this.font = font;
			this.color = color;
		}
	}
}
