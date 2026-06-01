package com.immersivedialogue;

import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.MouseAdapter;

/**
 * Makes the relocated dialogue box behave like a real surface:
 *
 * <ul>
 *     <li><b>Blocks click-through</b> — any click landing on the box (or the head beside it) is
 *     consumed so it never reaches the 3D world underneath.</li>
 *     <li><b>Selects options / advances dialogue</b> — a left click on an option selects it, and a
 *     left click anywhere on a plain NPC/player box advances the "click to continue" dialogue. Both
 *     route through {@link Client#menuAction} (a sanctioned API call, not a synthesized input event)
 *     on the client thread, mirroring a {@code WIDGET_CONTINUE} the player would otherwise trigger.</li>
 * </ul>
 *
 * Callbacks fire on the AWT thread, so this only reads immutable snapshots published by
 * {@link DialogueWidgetController} and hops any game interaction onto the client thread.
 */
@Singleton
class DialogueMouseListener extends MouseAdapter
{
	private final Client client;
	private final ClientThread clientThread;
	private final DialogueWidgetController controller;
	private final ImmersiveDialogueConfig config;

	@Inject
	DialogueMouseListener(Client client, ClientThread clientThread,
		DialogueWidgetController controller, ImmersiveDialogueConfig config)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.controller = controller;
		this.config = config;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent event)
	{
		return handle(event, true);
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent event)
	{
		return handle(event, false);
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent event)
	{
		return handle(event, false);
	}

	/**
	 * @param select whether this callback may trigger a selection (only the press does, so a
	 *               press/release pair never fires twice).
	 */
	private MouseEvent handle(MouseEvent event, boolean select)
	{
		if (!config.relocate())
		{
			return event;
		}
		final Rectangle box = controller.getBounds();
		if (box == null)
		{
			return event;
		}
		// Let the middle button (camera drag) through.
		if (SwingUtilities.isMiddleMouseButton(event))
		{
			return event;
		}

		final int pad = config.backdropPadding();
		final Rectangle region = new Rectangle(box.x - pad, box.y - pad,
			box.width + (pad * 2), box.height + (pad * 2));
		final Rectangle head = controller.getHeadBounds();
		final boolean inside = region.contains(event.getPoint())
			|| (head != null && head.contains(event.getPoint()));
		if (!inside)
		{
			return event;
		}

		if (select && SwingUtilities.isLeftMouseButton(event))
		{
			trySelect(event);
		}
		// Block the world click underneath the box (and suppress its right-click menu).
		event.consume();
		return event;
	}

	private void trySelect(MouseEvent event)
	{
		final DialogueWidgetController.Kind kind = controller.getKind();
		if (kind == DialogueWidgetController.Kind.OPTIONS)
		{
			for (final DialogueWidgetController.OptionHit hit : controller.getOptionHits())
			{
				if (hit.rect.contains(event.getPoint()))
				{
					final int subid = hit.subid;
					clientThread.invoke(() -> selectOption(subid));
					return;
				}
			}
		}
		else if (kind == DialogueWidgetController.Kind.NPC || kind == DialogueWidgetController.Kind.PLAYER)
		{
			final boolean isPlayer = controller.isPlayerSpeaker();
			clientThread.invoke(() -> continueDialogue(isPlayer));
		}
	}

	/** Selects dialogue option {@code subid} (child index) of the native options widget. */
	private void selectOption(int subid)
	{
		final Widget options = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
		if (options == null)
		{
			return;
		}
		final Widget child = options.getChild(subid);
		final String option = child != null && child.getText() != null ? child.getText() : "";
		// param0 = child subid (what getChild()/the menu resolves with), param1 = options widget id.
		client.menuAction(subid, InterfaceID.Chatmenu.OPTIONS, MenuAction.WIDGET_CONTINUE,
			subid, -1, option, "");
	}

	/** Advances a "click here to continue" NPC/player dialogue via its CONTINUE widget. */
	private void continueDialogue(boolean isPlayer)
	{
		final int continueId = isPlayer ? InterfaceID.ChatRight.CONTINUE : InterfaceID.ChatLeft.CONTINUE;
		final Widget cont = client.getWidget(continueId);
		if (cont == null)
		{
			// Final line / no continue button present: nothing to advance.
			return;
		}
		client.menuAction(-1, continueId, MenuAction.WIDGET_CONTINUE, 1, -1, "Continue", "");
	}
}
