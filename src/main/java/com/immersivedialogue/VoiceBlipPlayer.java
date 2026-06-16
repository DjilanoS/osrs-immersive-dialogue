package com.immersivedialogue;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Singleton;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads the bundled voice-blip WAVs and plays them as short, fire-and-forget sounds synced to the dialogue
 * typewriter.
 *
 * <p>Every clip is opened once on a background thread during {@link #preloadAsync} — opening a line and reading
 * the resource are the only slow / blocking steps, and they must never happen on the client (render) thread.
 * {@link #play} then merely rewinds and starts an already-open clip, which is cheap and non-blocking. A small
 * ring of clips per resource lets a blip's tail keep sounding while the next one fires.</p>
 *
 * <p>All audio is best-effort: on a machine with no mixer (e.g. headless CI or {@code ./gradlew run} without an
 * output device) loading simply yields no clips and {@link #play} becomes a silent no-op. Nothing here ever
 * throws on the client thread.</p>
 */
@Slf4j
@Singleton
class VoiceBlipPlayer
{
	/** Pre-opened clips per resource so an overlapping blip (tail still playing) is not cut off. */
	private static final int POOL_PER_RESOURCE = 3;

	/** Opens lines + reads resources off the client thread; a frame must never block on audio or disk IO. */
	private final ExecutorService loader = Executors.newSingleThreadExecutor(r ->
	{
		final Thread t = new Thread(r, "immersive-dialogue-voice-loader");
		t.setDaemon(true);
		return t;
	});

	/** resourceKey -&gt; ring of opened clips. Published atomically from the loader thread; read on the client thread. */
	private volatile Map<String, Clip[]> clips = Collections.emptyMap();
	/** Round-robin index per resource, advanced on each play. */
	private final Map<String, Integer> cursor = new ConcurrentHashMap<>();

	/**
	 * Open every blip on a background thread. Idempotent: once loaded a second call is a no-op, so toggling the
	 * feature off and on again does not reopen lines.
	 */
	void preloadAsync(Set<String> resourceKeys)
	{
		loader.submit(() ->
		{
			try
			{
				load(resourceKeys);
			}
			catch (Throwable t)
			{
				// Best-effort: a load failure (e.g. no mixer) just leaves play() a no-op.
				log.debug("Voice blip preload failed", t);
			}
		});
	}

	private void load(Set<String> resourceKeys)
	{
		if (!clips.isEmpty())
		{
			return; // already loaded
		}
		final Map<String, Clip[]> built = new HashMap<>();
		for (final String key : resourceKeys)
		{
			try (InputStream raw = VoiceBlipPlayer.class.getResourceAsStream(key))
			{
				if (raw == null)
				{
					log.debug("Voice blip resource missing: {}", key);
					continue;
				}
				try (AudioInputStream ais = AudioSystem.getAudioInputStream(new BufferedInputStream(raw)))
				{
					final AudioFormat format = ais.getFormat();
					final byte[] pcm = readAll(ais);
					final Clip[] ring = new Clip[POOL_PER_RESOURCE];
					for (int i = 0; i < ring.length; i++)
					{
						final Clip clip = AudioSystem.getClip();
						clip.open(format, pcm, 0, pcm.length);
						ring[i] = clip;
					}
					built.put(key, ring);
				}
			}
			catch (Throwable t)
			{
				// Skip this clip (unsupported format / no line / headless); the others still load.
				log.debug("Voice blip load skipped: {}", key, t);
			}
		}
		clips = built; // atomic publish
	}

	private static byte[] readAll(InputStream in) throws java.io.IOException
	{
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) != -1)
		{
			out.write(buf, 0, n);
		}
		return out.toByteArray();
	}

	/**
	 * Play one blip at the given volume (0-100). Safe to call from the client thread: it never opens a line,
	 * blocks, or throws. No-ops when not yet loaded or when audio is unavailable.
	 */
	void play(String resourceKey, int volumePercent)
	{
		final Clip[] ring = clips.get(resourceKey);
		if (ring == null || ring.length == 0)
		{
			return;
		}
		final int idx = Math.floorMod(cursor.merge(resourceKey, 1, Integer::sum), ring.length);
		final Clip clip = ring[idx];
		try
		{
			if (clip.isRunning())
			{
				clip.stop();
			}
			setGain(clip, volumePercent);
			clip.setFramePosition(0);
			clip.start();
		}
		catch (Exception e)
		{
			log.debug("Voice blip play skipped", e);
		}
	}

	private static void setGain(Clip clip, int volumePercent)
	{
		if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN))
		{
			return;
		}
		final FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
		// Perceptual volume: map 1-100% to decibels (log10(0) is undefined, so floor the fraction at 1%).
		final float fraction = Math.max(1, volumePercent) / 100f;
		float db = (float) (20.0 * Math.log10(fraction));
		db = Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db));
		gain.setValue(db);
	}

	/** Stop the loader and release every audio line. Called on plugin shutdown. */
	void dispose()
	{
		loader.shutdownNow();
		for (final Clip[] ring : clips.values())
		{
			for (final Clip clip : ring)
			{
				try
				{
					clip.stop();
					clip.close();
				}
				catch (Exception ignored)
				{
					// releasing is best-effort
				}
			}
		}
		clips = Collections.emptyMap();
		cursor.clear();
	}
}
