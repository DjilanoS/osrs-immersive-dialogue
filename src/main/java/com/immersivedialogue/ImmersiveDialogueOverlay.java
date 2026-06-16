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
	static final int OPTION_PAD = 7;
	static final int OPTION_GAP = 8;
	/** Lower bound for scaled font sizes so text stays legible at small scales (shared with optionsBoxHeight). */
	static final int MIN_FONT_PX = 8;
	private static final String CONTINUE_TEXT = "Press Space to continue";
	private static final String SKIP_TEXT = "Press Space to skip";
	private static final Color HINT_COLOR = new Color(255, 255, 255, 165);
	private static final Color HINT_HOVER_COLOR = Color.WHITE;

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

			// Scale the text with the box: the configured sizes are the 100% reference, multiplied by the
			// dialogue scale and floored so they stay legible. Spacing constants are scaled at each use site.
			final float s = scale();
			final int titlePx = Math.max(MIN_FONT_PX, scaled(config.titleFontSize(), s));
			final int bodyPx = Math.max(MIN_FONT_PX, scaled(config.textSize(), s));
			final Font nameFont = FontManager.getRunescapeBoldFont().deriveFont((float) titlePx);
			final Font bodyFont = FontManager.getRunescapeFont().deriveFont((float) bodyPx);
			final Color nameColor = config.nameColor();
			final Color textColor = config.textColor();

			switch (controller.getKind())
			{
				case NPC:
				case PLAYER:
				case MESSAGE:
				case OBJECT:
				case LEVELUP:
					// MESSAGE/OBJECT/LEVELUP have no speaker name (null); they draw as body text + the continue
					// hint. OBJECT shows its item model beside the box and LEVELUP the levelled skill's model,
					// both rendered through the head pipeline.
					drawConversation(g, b, nameFont, bodyFont, nameColor, textColor, s);
					break;
				case OPTIONS:
					drawOptions(g, b, nameFont, bodyFont, nameColor, textColor, s);
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

	/** Current dialogue scale factor (1.0 = 100%). */
	private float scale()
	{
		return config.scalePercent() / 100f;
	}

	/** A base constant scaled by {@code s}. Mirrors DialogueWidgetController.scaled so the two never drift. */
	private static int scaled(int v, float s)
	{
		return Math.round(v * s);
	}

	private void drawConversation(Graphics2D g, Rectangle b, Font nameFont, Font bodyFont,
		Color nameColor, Color textColor, float s)
	{
		final int maxWidth = b.width - (scaled(INSET, s) * 2);
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
			// Typewriter: wrap the FULL body (stable line breaks that never reflow as characters appear), then
			// clip the drawn glyphs to the revealed character budget. getRevealedChars() is MAX_VALUE when not
			// revealing, so the whole body draws exactly as before.
			int remaining = controller.getRevealedChars();
			for (final String wrapped : wrap(body, bfm, maxWidth))
			{
				if (remaining <= 0)
				{
					break;
				}
				final String shown = wrapped.length() <= remaining ? wrapped : wrapped.substring(0, remaining);
				remaining -= shown.length();
				lines.add(new Line(shown, bodyFont, textColor, -1));
			}
		}

		drawTextBlock(g, b, lines, s);
		drawBottomHint(g, b, bodyFont, controller.isRevealing() ? SKIP_TEXT : CONTINUE_TEXT, s);
	}

	/**
	 * A bottom-centered hint line (e.g. "Press Space to continue" / "Use keys [1] - [N] …"). Muted by default,
	 * it brightens to full white while the cursor is over the dialogue box, drawing attention to the keys that
	 * drive it.
	 */
	private void drawBottomHint(Graphics2D g, Rectangle b, Font font, String text, float s)
	{
		final FontMetrics fm = g.getFontMetrics(font);
		final int x = b.x + ((b.width - fm.stringWidth(text)) / 2);
		final int y = b.y + b.height - fm.getDescent() - (scaled(INSET, s) / 2);

		final Point mouse = client.getMouseCanvasPosition();
		final boolean hover = mouse != null && b.contains(mouse.getX(), mouse.getY());

		g.setFont(font);
		g.setColor(Color.BLACK);
		g.drawString(text, x + 1, y + 1);
		g.setColor(hover ? HINT_HOVER_COLOR : HINT_COLOR);
		g.drawString(text, x, y);
	}

	private void drawOptions(Graphics2D g, Rectangle b, Font nameFont, Font bodyFont,
		Color nameColor, Color textColor, float s)
	{
		final List<Line> lines = new ArrayList<>();
		int maxKey = 0;
		for (final DialogueWidgetController.Option option : controller.getOptions())
		{
			// Child subid 0 is the "Select an Option" header: render it as the title.
			final boolean header = option.subid == 0;
			// Mirror Quest Helper's highlight in our own legible color (detection used QH's real color).
			final Color color = option.highlighted ? config.questHelperHighlightColor()
				: (header ? nameColor : textColor);
			// Prefix each selectable option with the number key that picks it (native 1-5 handling).
			final String text = header ? option.text : ("[" + option.subid + "] " + option.text);
			if (!header)
			{
				maxKey = Math.max(maxKey, option.subid);
			}
			lines.add(new Line(text, header ? nameFont : bodyFont, color, header ? -1 : option.subid));
		}
		drawTextBlock(g, b, lines, s);
		if (maxKey > 0)
		{
			// Tell the player which number keys drive the options (optionsBoxHeight reserves this line).
			final String hint = maxKey == 1 ? "Use key [1]" : ("Use keys [1] - [" + maxKey + "]");
			drawBottomHint(g, b, bodyFont, hint, s);
		}
	}

	/**
	 * Draws the lines top-aligned and horizontally centered. Option rows (non-negative subid) get extra
	 * vertical padding so the numbered choices are comfortably spaced.
	 */
	private void drawTextBlock(Graphics2D g, Rectangle b, List<Line> lines, float s)
	{
		if (lines.isEmpty())
		{
			return;
		}

		int y = b.y + scaled(INSET, s);
		for (final Line line : lines)
		{
			final FontMetrics fm = g.getFontMetrics(line.font);
			final int x = b.x + ((b.width - fm.stringWidth(line.text)) / 2);

			if (line.subid >= 0)
			{
				// Option row: padded and comfortably spaced.
				final int optPad = scaled(OPTION_PAD, s);
				final int rowH = fm.getAscent() + fm.getDescent() + (optPad * 2);
				final int baseline = y + optPad + fm.getAscent();
				g.setFont(line.font);
				g.setColor(Color.BLACK);
				g.drawString(line.text, x + 1, baseline + 1);
				g.setColor(line.color);
				g.drawString(line.text, x, baseline);
				y += rowH + scaled(OPTION_GAP, s);
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
				y += fm.getDescent() + scaled(LINE_GAP, s);
			}
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
