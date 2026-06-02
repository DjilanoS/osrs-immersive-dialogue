package com.immersivedialogue;

import java.awt.event.KeyEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyListener;

/**
 * Lets the spacebar advance a relocated "click here to continue" NPC/player dialogue, matching what a
 * left click on the box already does. We only OBSERVE the user's real keypress and respond with the same
 * sanctioned {@link DialogueWidgetController#continueDialogue()} ({@code menuAction}) path the mouse uses —
 * no input event is synthesized, so this stays within the plugin rules (which forbid injecting key events,
 * not reacting to them).
 *
 * <p>The native client's own spacebar-continue keys off the CONTINUE widget, which we hide, so it no longer
 * fires; we take ownership of the key only while such a dialogue is on screen and pass it through otherwise.
 * Callbacks arrive on the AWT thread, so the game interaction is hopped onto the client thread.</p>
 */
@Singleton
class DialogueKeyListener implements KeyListener
{
	private final ClientThread clientThread;
	private final DialogueWidgetController controller;

	/** Tracks the held state so OS key auto-repeat advances one line per press, like a single click. */
	private boolean spaceDown;

	@Inject
	DialogueKeyListener(ClientThread clientThread, DialogueWidgetController controller)
	{
		this.clientThread = clientThread;
		this.controller = controller;
	}

	@Override
	public void keyTyped(KeyEvent event)
	{
		// Swallow the matching space character so it can never leak into a text field while we own the key.
		if (event.getKeyChar() == ' ' && handles())
		{
			event.consume();
		}
	}

	@Override
	public void keyPressed(KeyEvent event)
	{
		if (event.getKeyCode() != KeyEvent.VK_SPACE || !handles())
		{
			return;
		}
		event.consume();
		if (spaceDown)
		{
			// Auto-repeat while held: ignore, so one tap advances exactly one line (mirrors one click).
			return;
		}
		spaceDown = true;
		clientThread.invoke(controller::continueDialogue);
	}

	@Override
	public void keyReleased(KeyEvent event)
	{
		if (event.getKeyCode() != KeyEvent.VK_SPACE)
		{
			return;
		}
		spaceDown = false;
		if (handles())
		{
			event.consume();
		}
	}

	@Override
	public void focusLost()
	{
		// A release can be missed when focus leaves mid-press; clear the latch so the next press still fires.
		spaceDown = false;
	}

	/** Spacebar is ours only while a relocated NPC/player "click to continue" dialogue is on screen. */
	private boolean handles()
	{
		final DialogueWidgetController.Kind kind = controller.getKind();
		return kind == DialogueWidgetController.Kind.NPC || kind == DialogueWidgetController.Kind.PLAYER;
	}
}
