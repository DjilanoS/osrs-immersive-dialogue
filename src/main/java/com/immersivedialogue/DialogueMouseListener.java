package com.immersivedialogue;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.client.input.MouseAdapter;

/**
 * Makes the relocated dialogue box behave like a real surface:
 *
 * <ul>
 *     <li><b>Blocks click-through</b> — any click landing on the box (or the head beside it) is
 *     consumed so it never reaches the 3D world underneath. Turning on "Click through chatbox" opts out:
 *     the box stops swallowing clicks and they reach the world as if it were not there.</li>
 *     <li><b>ALT-drag reposition</b> — in drag mode, ALT + left-drag over the box moves it and
 *     persists the new position.</li>
 * </ul>
 *
 * The dialogue itself is advanced and selected entirely through the game's own native keyboard
 * (spacebar to continue, number keys to choose an option) — this listener never sends any game
 * action. Callbacks fire on the AWT thread, so it only reads immutable snapshots published by
 * {@link DialogueWidgetController}.
 */
@Singleton
class DialogueMouseListener extends MouseAdapter
{
	private final Client client;
	private final DialogueWidgetController controller;
	private final ImmersiveDialogueConfig config;

	/** ALT-drag state, tracked on the AWT thread across the press → drag → release of a reposition. */
	private boolean dragging;
	private int dragStartX;
	private int dragStartY;

	@Inject
	DialogueMouseListener(Client client, DialogueWidgetController controller, ImmersiveDialogueConfig config)
	{
		this.client = client;
		this.controller = controller;
		this.config = config;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent event)
	{
		final Point mouse = client.getMouseCanvasPosition();
		final int mx = mouse != null ? mouse.getX() : event.getX();
		final int my = mouse != null ? mouse.getY() : event.getY();

		// In drag mode, ALT + left-press on the box starts a drag-to-reposition.
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
		return handle(event);
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
		return handle(event);
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
		return handle(event);
	}

	/**
	 * Consumes any click landing on the box (or head) so it never reaches the 3D world underneath — unless
	 * "Click through chatbox" is on, in which case the box is left as a pass-through surface and the click
	 * falls through to the world as if the box were not there.
	 */
	private MouseEvent handle(MouseEvent event)
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
		// While the dialogue is still typing out, a left-click on the box finishes the reveal. Purely local
		// (nothing is sent to the game); this mirrors the Space-to-skip key handling.
		if (SwingUtilities.isLeftMouseButton(event) && controller.isRevealing())
		{
			controller.requestSkip();
		}
		if (config.clickThrough())
		{
			// Pass-through mode: leave the event untouched so the world click (and its right-click menu)
			// happens exactly as it would with no box on screen.
			return event;
		}
		// Block the world click underneath the box (and suppress its right-click menu).
		event.consume();
		return event;
	}
}
