package com.immersivedialogue;

import java.awt.event.KeyEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.input.KeyListener;

/**
 * While the dialogue typewriter is still revealing a line, the first Space / Enter press finishes the reveal
 * instead of advancing: this listener consumes that one keystroke and tells the controller to skip. Once the
 * line is fully revealed it consumes nothing, so the next Space / Enter falls through to the game's own native
 * dialogue advance.
 *
 * <p>Like {@link DialogueMouseListener}, this only ever <b>consumes</b> an input event — it never synthesizes or
 * injects one, and never sends a game action.</p>
 */
@Singleton
class DialogueKeyListener implements KeyListener
{
	private final DialogueWidgetController controller;

	@Inject
	DialogueKeyListener(DialogueWidgetController controller)
	{
		this.controller = controller;
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		final int code = e.getKeyCode();
		if ((code == KeyEvent.VK_SPACE || code == KeyEvent.VK_ENTER) && controller.isRevealing())
		{
			controller.requestSkip();
			e.consume();
		}
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
	}
}
