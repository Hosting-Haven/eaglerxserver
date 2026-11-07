/*
 * Copyright (c) 2025 lax1dude. All Rights Reserved.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 */

package net.lax1dude.eaglercraft.backend.server.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import net.lax1dude.eaglercraft.backend.server.api.EnumMotdPassthroughPolicy;

/**
 * The publish path for runtime MOTD eligibility.
 *
 * The properties that matter are not the happy path:
 *
 *  - a failed reload must leave the previous policy EXACTLY as it was, because a
 *    half-applied policy would show one backend's MOTD to one listener and not
 *    another with no way to tell why;
 *  - concurrent reloads must not lose an update or reuse a revision number, or a
 *    FoxPanel client waiting for "revision N+1" would accept someone else's
 *    change as its own;
 *  - reads must not block a writer, because they happen on Netty threads.
 */
class MotdPolicyReloaderTest {

	private static Map<String, MotdPolicySnapshot.ListenerPolicy> policy(EnumMotdPassthroughPolicy policy,
			String... allowed) {
		Map<String, MotdPolicySnapshot.ListenerPolicy> map = new LinkedHashMap<>();
		map.put("listener0", MotdPolicySnapshot.ListenerPolicy.of(policy, Arrays.asList(allowed)));

		return map;
	}

	@Test
	void startsWithNothingOverridden() {
		MotdPolicyReloader reloader = new MotdPolicyReloader();

		assertEquals(0L, reloader.activeRevision());
		assertTrue(reloader.active().isEmpty());
		assertNull(reloader.active().forListener("listener0"),
				"a listener with no published policy must fall back to its startup configuration");
	}

	@Test
	void publishingBumpsTheRevisionAndBecomesVisible() {
		MotdPolicyReloader reloader = new MotdPolicyReloader();

		MotdPolicyReloader.ReloadResult result = reloader.publish(policy(EnumMotdPassthroughPolicy.NONE), null);

		assertTrue(result.ok());
		assertEquals(1L, result.revision());
		assertEquals(1L, reloader.activeRevision());
		assertEquals(EnumMotdPassthroughPolicy.NONE, reloader.active().forListener("listener0").policy());
	}

	/**
	 * A rejected proposal must not be observable at all.
	 */
	@Test
	void anEmptyProposalIsRejectedAndNothingChanges() {
		MotdPolicyReloader reloader = new MotdPolicyReloader();

		reloader.publish(policy(EnumMotdPassthroughPolicy.SELECTED, "lobby"), null);

		long before = reloader.activeRevision();

		MotdPolicyReloader.ReloadResult result = reloader
				.publish(Collections.<String, MotdPolicySnapshot.ListenerPolicy>emptyMap(), null);

		assertFalse(result.ok());
		assertEquals(before, reloader.activeRevision(), "the revision must not advance on a rejected reload");
		assertEquals(EnumMotdPassthroughPolicy.SELECTED, reloader.active().forListener("listener0").policy(),
				"the previously active policy must survive a rejected reload");
	}

	@Test
	void aNullPolicyForAListenerIsRejected() {
		MotdPolicyReloader reloader = new MotdPolicyReloader();

		Map<String, MotdPolicySnapshot.ListenerPolicy> bad = new LinkedHashMap<>();
		bad.put("listener0", null);

		MotdPolicyReloader.ReloadResult result = reloader.publish(bad, null);

		assertFalse(result.ok());
		assertEquals(0L, reloader.activeRevision());
		assertTrue(reloader.active().isEmpty());
	}

	/**
	 * Optimistic concurrency: a request based on a revision that is no longer
	 * current must be refused rather than silently winning.
	 */
	@Test
	void aStaleExpectedRevisionIsRejected() {
		MotdPolicyReloader reloader = new MotdPolicyReloader();

		reloader.publish(policy(EnumMotdPassthroughPolicy.ALL), null);
		reloader.publish(policy(EnumMotdPassthroughPolicy.NONE), null);

		// A caller that still believes revision 1 is current.
		MotdPolicyReloader.ReloadResult result = reloader.publish(policy(EnumMotdPassthroughPolicy.SELECTED, "lobby"),
				Long.valueOf(1L));

		assertFalse(result.ok(), "a stale request must not overwrite a newer policy");
		assertTrue(result.error().contains("stale"), result.error());
		assertEquals(2L, result.revision(), "the failure must report the revision still in force");
		assertEquals(EnumMotdPassthroughPolicy.NONE, reloader.active().forListener("listener0").policy());
	}

	@Test
	void aMatchingExpectedRevisionIsAccepted() {
		MotdPolicyReloader reloader = new MotdPolicyReloader();

		reloader.publish(policy(EnumMotdPassthroughPolicy.ALL), null);

		MotdPolicyReloader.ReloadResult result = reloader.publish(policy(EnumMotdPassthroughPolicy.NONE),
				Long.valueOf(1L));

		assertTrue(result.ok(), String.valueOf(result.error()));
		assertEquals(2L, result.revision());
	}

	@Test
	void clearRevertsToTheStartupConfiguration() {
		MotdPolicyReloader reloader = new MotdPolicyReloader();

		reloader.publish(policy(EnumMotdPassthroughPolicy.NONE), null);

		MotdPolicyReloader.ReloadResult result = reloader.clear();

		assertTrue(result.ok());
		assertTrue(reloader.active().isEmpty());
		assertNull(reloader.active().forListener("listener0"));
	}

	@Test
	void multipleListenersArePublishedTogether() {
		MotdPolicyReloader reloader = new MotdPolicyReloader();

		Map<String, MotdPolicySnapshot.ListenerPolicy> both = new LinkedHashMap<>();
		both.put("listener0", MotdPolicySnapshot.ListenerPolicy.of(EnumMotdPassthroughPolicy.NONE, null));
		both.put("listener1",
				MotdPolicySnapshot.ListenerPolicy.of(EnumMotdPassthroughPolicy.SELECTED, Arrays.asList("lobby")));

		MotdPolicyReloader.ReloadResult result = reloader.publish(both, null);

		assertTrue(result.ok());
		assertEquals(2, result.applied().size());
		assertEquals(EnumMotdPassthroughPolicy.NONE, reloader.active().forListener("listener0").policy());
		assertEquals(EnumMotdPassthroughPolicy.SELECTED, reloader.active().forListener("listener1").policy());
	}

	/**
	 * A published snapshot must be immutable, or a later caller could mutate the
	 * policy the Netty threads are reading.
	 */
	@Test
	void aPublishedSnapshotCannotBeMutatedByTheCaller() {
		MotdPolicyReloader reloader = new MotdPolicyReloader();

		Map<String, MotdPolicySnapshot.ListenerPolicy> proposed = policy(EnumMotdPassthroughPolicy.NONE);
		reloader.publish(proposed, null);

		// Mutating the map we passed in must not affect what is active.
		proposed.put("listener9", MotdPolicySnapshot.ListenerPolicy.of(EnumMotdPassthroughPolicy.ALL, null));

		assertNull(reloader.active().forListener("listener9"));

		try {
			reloader.active().listeners().put("listener8",
					MotdPolicySnapshot.ListenerPolicy.of(EnumMotdPassthroughPolicy.ALL, null));
			throw new AssertionError("the published map must be unmodifiable");
		} catch (UnsupportedOperationException expected) {
			// correct: the published view cannot be edited
		}
	}

	/**
	 * Two writers that both read revision N must not both publish N+1.
	 */
	@Test
	void concurrentReloadsNeverReuseARevision() throws Exception {
		final MotdPolicyReloader reloader = new MotdPolicyReloader();
		final int threads = 8;
		final int perThread = 25;

		final CountDownLatch start = new CountDownLatch(1);
		final CountDownLatch done = new CountDownLatch(threads);

		final List<Long> revisions = Collections.synchronizedList(new ArrayList<Long>());
		final AtomicInteger failures = new AtomicInteger();

		for (int i = 0; i < threads; i++) {
			new Thread(() -> {
				try {
					start.await();

					for (int j = 0; j < perThread; j++) {
						MotdPolicyReloader.ReloadResult result = reloader
								.publish(policy(EnumMotdPassthroughPolicy.SELECTED, "lobby"), null);

						if (result.ok()) {
							revisions.add(Long.valueOf(result.revision()));
						} else {
							failures.incrementAndGet();
						}
					}
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown();
				}
			}).start();
		}

		start.countDown();
		assertTrue(done.await(30, TimeUnit.SECONDS), "reload threads did not finish");
		assertEquals(0, failures.get(), "no publish should fail when no expected revision is supplied");

		assertEquals(threads * perThread, revisions.size(), "every publish must be recorded");

		// Every revision must be distinct and cover 1..N exactly once.
		long max = Collections.max(revisions);
		assertEquals(threads * perThread, max, "revisions must be dense and monotonic");

		assertEquals(max, revisions.stream().distinct().count(),
				"two writers must never publish the same revision");
	}

	/**
	 * A reader hammering the snapshot while writers publish must always see a
	 * complete snapshot, never a torn one, and must not deadlock.
	 */
	@Test
	void readersSeeACompleteSnapshotWhileWritersPublish() throws Exception {
		final MotdPolicyReloader reloader = new MotdPolicyReloader();
		final AtomicInteger torn = new AtomicInteger();
		final AtomicInteger reads = new AtomicInteger();
		final CountDownLatch stop = new CountDownLatch(1);

		Executors.newSingleThreadExecutor().submit(() -> {
			while (stop.getCount() > 0) {
				MotdPolicySnapshot snapshot = reloader.active();

				// Every listener present in a snapshot must have a policy: a partial
				// publish would leave a null behind.
				for (Map.Entry<String, MotdPolicySnapshot.ListenerPolicy> entry : snapshot.listeners().entrySet()) {
					if (entry.getKey() == null || entry.getValue() == null) {
						torn.incrementAndGet();
					}
				}

				reads.incrementAndGet();
			}

			return null;
		});

		ExecutorService writer = Executors.newSingleThreadExecutor();
		writer.submit(() -> {
			for (int i = 0; i < 500; i++) {
				Map<String, MotdPolicySnapshot.ListenerPolicy> both = new LinkedHashMap<>();
				both.put("listener0", MotdPolicySnapshot.ListenerPolicy.of(EnumMotdPassthroughPolicy.NONE, null));
				both.put("listener1", MotdPolicySnapshot.ListenerPolicy.of(EnumMotdPassthroughPolicy.ALL, null));
				reloader.publish(both, null);
			}

			return null;
		}).get(30, TimeUnit.SECONDS);

		stop.countDown();
		writer.shutdown();
		writer.awaitTermination(10, TimeUnit.SECONDS);

		assertEquals(0, torn.get(), "a reader observed a partially published snapshot");
		assertTrue(reads.get() > 0, "the reader never ran, so the test proved nothing");
		assertEquals(2, reloader.active().listeners().size());
		assertNotNull(reloader.active().forListener("listener0"));
	}

}
