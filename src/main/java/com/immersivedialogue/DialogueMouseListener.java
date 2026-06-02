package com.immersivedialogue;

import java.awt.event.MouseEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.client.input.MouseAdapter;

/**
 * Makes the relocated dialogue box behave like a solid surface: a mouse click landing on the box (or the
 * head beside it) is consumed so it does not fall through to the 3D world underneath while the player reads.
 *
 * <p>It only swallows the user's own clicks — it never synthesizes input or game actions. All dialogue
 * interaction (advance / option select) is handled natively by the game: spacebar continues, and the number
 * keys 1-5 select options, acting on the native widgets the controller deliberately leaves visible. This
 * listener therefore does nothing but block click-through.</p>
 *
 * <p>Callbacks fire on the AWT thread, so this only reads the immutable geometry snapshot published by
 * {@link DialogueWidgetController}.</p>
 */
@Singleton
class DialogueMouseListener extends MouseAdapter
{
	private final DialogueWidgetController controller;

	@Inject
	DialogueMouseListener(DialogueWidgetController controller)
	{
		this.controller = controller;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent event)
	{
		return consumeIfOverBox(event);
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent event)
	{
		return consumeIfOverBox(event);
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent event)
	{
		return consumeIfOverBox(event);
	}

	/** Swallow the click when it lands on the relocated box, so the world underneath is not clicked. */
	private MouseEvent consumeIfOverBox(MouseEvent event)
	{
		// Let the middle button (camera drag) through.
		if (SwingUtilities.isMiddleMouseButton(event))
		{
			return event;
		}
		if (controller.blocks(event.getX(), event.getY()))
		{
			event.consume();
		}
		return event;
	}
}
