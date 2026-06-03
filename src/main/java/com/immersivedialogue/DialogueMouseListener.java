package com.immersivedialogue;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.Point;
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

	/** ALT-drag state, tracked on the AWT thread across the press → drag → release of a reposition. */
	private boolean dragging;
	private int dragStartX;
	private int dragStartY;

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
		final Point mouse = client.getMouseCanvasPosition();
		final int mx = mouse != null ? mouse.getX() : event.getX();
		final int my = mouse != null ? mouse.getY() : event.getY();

		// In drag mode, ALT + left-press on the box starts a drag-to-reposition instead of selecting.
		if (config.dragMode() && event.isAltDown() && SwingUtilities.isLeftMouseButton(event)
			&& controller.blocks(mx, my))
		{
			// Event coords here: getMouseCanvasPosition() is not updated while a button is held.
			dragStartX = event.getX();
			dragStartY = event.getY();
			dragging = true;
			controller.beginDrag();
			event.consume();
			return event;
		}
		return handle(event, true);
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent event)
	{
		if (dragging)
		{
			controller.endDrag();
			dragging = false;
			event.consume();
			return event;
		}
		return handle(event, false);
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent event)
	{
		if (dragging)
		{
			controller.dragBy(event.getX() - dragStartX, event.getY() - dragStartY);
			event.consume();
		}
		return event;
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
		// Let the middle button (camera drag) through.
		if (SwingUtilities.isMiddleMouseButton(event))
		{
			return event;
		}
		// Canvas coords to match the overlay (these differ from raw event coords when stretched / HiDPI).
		final Point mouse = client.getMouseCanvasPosition();
		final int mx = mouse != null ? mouse.getX() : event.getX();
		final int my = mouse != null ? mouse.getY() : event.getY();
		if (!controller.blocks(mx, my))
		{
			return event;
		}

		if (select && SwingUtilities.isLeftMouseButton(event))
		{
			trySelect(mx, my);
		}
		// Block the world click underneath the box (and suppress its right-click menu).
		event.consume();
		return event;
	}

	private void trySelect(int mx, int my)
	{
		final DialogueWidgetController.Kind kind = controller.getKind();
		if (kind == DialogueWidgetController.Kind.OPTIONS)
		{
			for (final DialogueWidgetController.OptionHit hit : controller.getOptionHits())
			{
				if (hit.rect.contains(mx, my))
				{
					final int subid = hit.subid;
					clientThread.invoke(() -> selectOption(subid));
					return;
				}
			}
		}
		else if (kind == DialogueWidgetController.Kind.NPC || kind == DialogueWidgetController.Kind.PLAYER)
		{
			clientThread.invoke(controller::continueDialogue);
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
}
