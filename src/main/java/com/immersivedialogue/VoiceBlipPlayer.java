package com.immersivedialogue;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.audio.AudioPlayer;

/**
 * Plays the bundled voice-blip WAVs as short, fire-and-forget sounds synced to the dialogue typewriter.
 *
 * <p>Playback goes through RuneLite's {@link AudioPlayer}, which loads the resource, opens a self-closing
 * audio line and starts it. That work is blocking IO plus line acquisition, so every blip is dispatched to a
 * single background thread — a frame must never block on audio or disk IO. {@link #play} only enqueues the
 * request and returns immediately; it never blocks or throws on the client thread.</p>
 *
 * <p>All audio is best-effort: on a machine with no mixer (headless CI, or {@code ./gradlew run} without an
 * output device) {@link AudioPlayer#play} simply fails and the blip is silently dropped. Nothing here ever
 * throws on the client thread.</p>
 */
@Slf4j
@Singleton
class VoiceBlipPlayer
{
	/**
	 * Cap on queued-but-not-yet-played blips. The typewriter can request blips faster than the audio
	 * subsystem opens lines; rather than build a backlog (and play blips after the line has finished
	 * typing), excess requests are dropped — a skipped blip is imperceptible.
	 */
	private static final int MAX_PENDING = 8;

	private final AudioPlayer audioPlayer;

	/**
	 * Single daemon thread that runs the blocking load/open/start off the client thread. Lazily
	 * (re)created by {@link #ensurePlayer} so toggling the plugin off and on again resumes playback;
	 * {@link #dispose} shuts it down.
	 */
	private ThreadPoolExecutor player;

	@Inject
	VoiceBlipPlayer(AudioPlayer audioPlayer)
	{
		this.audioPlayer = audioPlayer;
	}

	/**
	 * Play one blip at the given volume (1-100). Safe to call from the client thread: it only enqueues work
	 * and returns; it never opens a line, blocks, or throws. Dropped silently when the queue is saturated or
	 * the audio subsystem is unavailable.
	 */
	void play(String resourceKey, int volumePercent)
	{
		final float gainDb = gainFor(volumePercent);
		// DiscardPolicy silently drops the task when the queue is full or the executor is shutting down,
		// so execute() never throws and the client thread is never affected.
		ensurePlayer().execute(() ->
		{
			try
			{
				audioPlayer.play(VoiceBlipPlayer.class, resourceKey, gainDb);
			}
			catch (Exception e)
			{
				// Best-effort: unsupported format, no audio line, headless mixer, or missing resource.
				log.debug("Voice blip play skipped: {}", resourceKey, e);
			}
		});
	}

	/** Lazily (re)create the player thread. Synchronized against {@link #dispose}; cheap on the fast path. */
	private synchronized ThreadPoolExecutor ensurePlayer()
	{
		if (player == null || player.isShutdown())
		{
			player = new ThreadPoolExecutor(
				1, 1, 0L, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>(MAX_PENDING),
				r ->
				{
					final Thread t = new Thread(r, "immersive-dialogue-voice");
					t.setDaemon(true);
					return t;
				},
				new ThreadPoolExecutor.DiscardPolicy());
		}
		return player;
	}

	/**
	 * Map a 1-100 volume to a gain in decibels for {@link AudioPlayer}. Perceived loudness is logarithmic,
	 * so the linear fraction is converted to dB: 100% is 0 dB (native level) and 1% is about -40 dB. Floored
	 * at 1% because {@code log10(0)} is undefined.
	 */
	private static float gainFor(int volumePercent)
	{
		final float fraction = Math.max(1, volumePercent) / 100f;
		return (float) (20.0 * Math.log10(fraction));
	}

	/** Stop the player thread and release it. Called on plugin shutdown. */
	synchronized void dispose()
	{
		if (player != null)
		{
			player.shutdownNow();
			player = null;
		}
	}
}
