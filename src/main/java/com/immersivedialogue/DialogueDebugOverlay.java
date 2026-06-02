package com.immersivedialogue;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Temporary diagnostic overlay: prints the native head source, the chosen full-screen host, and
 * the widget we created for the relocated animated head, so we can see whether the created MODEL
 * widget is rendering.
 */
class DialogueDebugOverlay extends Overlay
{
	private final Client client;
	private final ImmersiveDialogueConfig config;
	private final DialogueWidgetController controller;

	@Inject
	DialogueDebugOverlay(Client client, ImmersiveDialogueConfig config, DialogueWidgetController controller)
	{
		this.client = client;
		this.config = config;
		this.controller = controller;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.debugOverlay())
		{
			return null;
		}

		final List<String> lines = new ArrayList<>();
		lines.add("ImmersiveDialogue | canvas=" + client.getCanvasWidth() + "x" + client.getCanvasHeight());

		describe(lines, "headSrc", controller.getHeadSource());
		final Widget host = controller.getHost();
		describe(lines, "host   ", host);
		if (host != null)
		{
			lines.add("host children: dynamic=" + count(host.getDynamicChildren())
				+ " models=" + countModels(host.getDynamicChildren()));
		}
		final Widget container = controller.getHeadContainer();
		describe(lines, "contnr ", container);
		if (container != null)
		{
			lines.add("container children: dynamic=" + count(container.getDynamicChildren())
				+ " models=" + countModels(container.getDynamicChildren()));
		}
		describe(lines, "created", controller.getCreatedHead());

		dumpChat(lines);

		g.setFont(new Font("Monospaced", Font.PLAIN, 12));
		final int x = 8;
		int y = 64;
		final int lh = 15;
		g.setColor(new Color(0, 0, 0, 170));
		g.fillRect(x - 4, y - 13, 720, (lines.size() * lh) + 8);
		g.setColor(Color.YELLOW);
		for (final String s : lines)
		{
			g.drawString(s, x, y);
			y += lh;
		}
		return null;
	}

	private static int count(Widget[] children)
	{
		return children == null ? 0 : children.length;
	}

	private static int countModels(Widget[] children)
	{
		if (children == null)
		{
			return 0;
		}
		int n = 0;
		for (final Widget c : children)
		{
			if (c != null && c.getType() == net.runelite.api.widgets.WidgetType.MODEL)
			{
				n++;
			}
		}
		return n;
	}

	/**
	 * Chatbox-state diagnostics for the "hide native dialogue panel" feature: which chat widgets are
	 * hidden while a dialogue is open, whether chat text is still present behind it, and the chatbox
	 * transparency mode (read-only — we never write that player-controlled varbit).
	 */
	private void dumpChat(List<String> lines)
	{
		chatState(lines, "CHAT_BACKGROUND", InterfaceID.Chatbox.CHAT_BACKGROUND, false);
		chatState(lines, "CHATDISPLAY    ", InterfaceID.Chatbox.CHATDISPLAY, false);
		chatState(lines, "SCROLLAREA     ", InterfaceID.Chatbox.SCROLLAREA, false);
		chatState(lines, "MES_LAYER_HIDE ", InterfaceID.Chatbox.MES_LAYER_HIDE, false);
		chatState(lines, "LINE0          ", InterfaceID.Chatbox.LINE0, true);
		chatState(lines, "LINE3          ", InterfaceID.Chatbox.LINE3, true);
		lines.add("varbit CHATBOX_TRANSPARENCY=" + client.getVarbitValue(VarbitID.CHATBOX_TRANSPARENCY));
	}

	private void chatState(List<String> lines, String label, int componentId, boolean withText)
	{
		final Widget w = client.getWidget(componentId);
		if (w == null)
		{
			lines.add("chat " + label + " = null");
			return;
		}
		final StringBuilder sb = new StringBuilder("chat ").append(label)
			.append(" hidden=").append(w.isHidden())
			.append(" self=").append(w.isSelfHidden());
		if (withText)
		{
			String t = w.getText();
			if (t == null)
			{
				t = "";
			}
			if (t.length() > 40)
			{
				t = t.substring(0, 40);
			}
			sb.append(" text='").append(t).append('\'');
		}
		lines.add(sb.toString());
	}

	private static void describe(List<String> lines, String label, Widget w)
	{
		if (w == null)
		{
			lines.add(label + " = null");
			return;
		}
		final Point c = w.getCanvasLocation();
		final String cs = c == null ? "null" : (c.getX() + "," + c.getY());
		lines.add(String.format(
			"%s id=%d type=%d modelType=%d modelId=%d anim=%d zoom=%d canvas=(%s) size=%dx%d hidden=%b",
			label, w.getId(), w.getType(), w.getModelType(), w.getModelId(), w.getAnimationId(),
			w.getModelZoom(), cs, w.getWidth(), w.getHeight(), w.isHidden()));
	}
}
