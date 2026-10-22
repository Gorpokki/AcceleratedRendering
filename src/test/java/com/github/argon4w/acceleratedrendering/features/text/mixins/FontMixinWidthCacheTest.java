package com.github.argon4w.acceleratedrendering.features.text.mixins;

import com.github.argon4w.acceleratedrendering.features.text.cache.ISeekableFormattedText;
import com.github.argon4w.acceleratedrendering.features.text.cache.StringText;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

// The width wrappers only reach the cache inside a live, GPU-backed client, so this test
// drives the mixin's own width cache with the same read-and-fill steps the wrappers use.
class FontMixinWidthCacheTest {

	private static final int ROUNDS		= 20;
	private static final int THREADS	= 8;
	private static final int KEYS		= 20_000;

	@Test
	void concurrentWidthCallsKeepTheCacheIntact() throws Exception {
		var keys = new ISeekableFormattedText[KEYS];

		for (var i = 0; i < KEYS; i++) {
			keys[i] = new StringText("text-" + i);
		}

		var pool = Executors.newFixedThreadPool(THREADS, runnable -> {
			var thread = new Thread(runnable);
			thread.setDaemon(true); // a corrupted open hash map can spin forever
			return thread;
		});

		try {
			for (var round = 0; round < ROUNDS; round++) {
				var widths	= widthsOf(new FontMixin());
				var start	= new CountDownLatch(1);
				var futures	= new ArrayList<Future<?>>();

				for (var thread = 0; thread < THREADS; thread++) {
					futures.add(pool.submit(() -> {
						start.await();

						for (var i = 0; i < KEYS; i++) {
							assertEquals(widthOf(i), cachedWidth(widths, keys[i], i));
						}

						return null;
					}));
				}

				start.countDown();

				for (var future : futures) {
					future.get(30, TimeUnit.SECONDS);
				}

				for (var i = 0; i < KEYS; i++) {
					assertEquals(widthOf(i), widths.getOrDefault(keys[i], -1));
				}
			}
		} finally {
			pool.shutdownNow();
		}
	}

	// Same steps as FontMixin.wrapWidth1/2/3 on the accelerated path.
	private static int cachedWidth(Object2IntMap<ISeekableFormattedText> widths, ISeekableFormattedText key, int index) {
		var width = widths.getOrDefault(key, -1);

		if (width == -1) {
			width = widthOf(index);

			widths.put(key, width);
		}

		return width;
	}

	private static int widthOf(int index) {
		return index % 997 + 1;
	}

	@SuppressWarnings("unchecked")
	private static Object2IntMap<ISeekableFormattedText> widthsOf(FontMixin font) throws ReflectiveOperationException {
		var field = FontMixin.class.getDeclaredField("widths");

		field.setAccessible(true);

		return (Object2IntMap<ISeekableFormattedText>) field.get(font);
	}
}
