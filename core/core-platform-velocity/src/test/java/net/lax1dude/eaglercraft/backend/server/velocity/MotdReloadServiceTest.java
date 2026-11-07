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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.lax1dude.eaglercraft.backend.server.api.EnumMotdPassthroughPolicy;

/**
 * Reading, validating and publishing the persisted eligibility settings.
 *
 * The most important case is the one that does NOT succeed: a reload that fails
 * must leave the runtime policy untouched. A proxy that silently dropped to
 * "show the listener MOTD" because of a typo in one backend name would be worse
 * than one that refused the change.
 */
class MotdReloadServiceTest {

	/** A listener view built by hand, so no 40-argument config object is needed. */
	private static MotdReloadService.ListenerView view(String name, EnumMotdPassthroughPolicy policy,
			String... allowed) {
		return viewRaw(name, policy, null, allowed);
	}

	/**
	 * A listener whose raw policy text is controlled independently of the parsed
	 * enum, which is how the loader's fallback can be reproduced.
	 */
	private static MotdReloadService.ListenerView viewRaw(String name, EnumMotdPassthroughPolicy policy,
			String policyRaw, String... allowed) {
		return new MotdReloadService.ListenerView() {

			@Override
			public String name() {
				return name;
			}

			@Override
			public EnumMotdPassthroughPolicy policy() {
				return policy;
			}

			@Override
			public String policyRaw() {
				return policyRaw;
			}

			@Override
			public Collection<String> allowed() {
				return Arrays.asList(allowed);
			}
		};
	}

	private static MotdReloadService service(MotdPolicyReloader reloader, MotdReloadService.ConfigSource source,
			Set<String> knownServers, List<MotdReloadService.ReloadOutcome> recorded) {
		return new MotdReloadService(reloader, source, knownServers::contains, recorded::add);
	}

	// ------------------------------------------------------------------
	// success
	// ------------------------------------------------------------------

	@Test
	void aValidReloadPublishesEveryListener() {
		MotdPolicyReloader reloader = new MotdPolicyReloader();
		List<MotdReloadService.ReloadOutcome> recorded = new ArrayList<>();

		MotdReloadService service = service(reloader,
				() -> Arrays.asList(view("listener0", EnumMotdPassthroughPolicy.NONE),
						view("listener1", EnumMotdPassthroughPolicy.SELECTED, "lobby")),
				new HashSet<>(Arrays.asList("lobby")), recorded);

		MotdReloadService.ReloadOutcome outcome = service.reload(null);

		assertTrue(outcome.ok(), String.valueOf(outcome.error()));
		assertEquals(1L, outcome.revision());
		assertEquals(2, outcome.listeners().size());
		assertEquals(EnumMotdPassthroughPolicy.NONE, reloader.active().forListener("listener0").policy());
		assertEquals(EnumMotdPassthroughPolicy.SELECTED, reloader.active().forListener("listener1").policy());
		assertEquals(1, recorded.size(), "the outcome must be recorded for the control plane");
	}

	@Test
	void allSelectsAndNoneAreAllAccepted() {
		for (EnumMotdPassthroughPolicy policy : EnumMotdPassthroughPolicy.values()) {
			MotdPolicyReloader reloader = new MotdPolicyReloader();

			MotdReloadService service = service(reloader,
					() -> Collections.singletonList(view("listener0", policy, "lobby")),
					Collections.singleton("lobby"), new ArrayList<>());

			MotdReloadService.ReloadOutcome outcome = service.reload(null);

			assertTrue(outcome.ok(), policy + ": " + outcome.error());
			assertEquals(policy, reloader.active().forListener("listener0").policy());
		}
	}

	@Test
	void allowedBackendNamesAreCarriedOntoTheSnapshot() {
		MotdPolicyReloader reloader = new MotdPolicyReloader();

		MotdReloadService service = service(reloader,
				() -> Collections.singletonList(
						view("listener0", EnumMotdPassthroughPolicy.SELECTED, "lobby", "minigames")),
				new HashSet<>(Arrays.asList("lobby", "minigames")), new ArrayList<>());

		assertTrue(service.reload(null).ok());

		MotdPolicySnapshot.ListenerPolicy published = reloader.active().forListener("listener0");

		assertTrue(published.allows("lobby"));
		assertTrue(published.allows("MINIGAMES"), "matching must be case insensitive");
		assertFalse(published.allows("survival"));
	}

	// ------------------------------------------------------------------
	// failure keeps the old policy
	// ------------------------------------------------------------------

	@Test
	void anUnreadableConfigurationLeavesTheActivePolicyAlone() {
		MotdPolicyReloader reloader = new MotdPolicyReloader();
		List<MotdReloadService.ReloadOutcome> recorded = new ArrayList<>();

		// First, a good reload.
		service(reloader, () -> Collections.singletonList(view("listener0", EnumMotdPassthroughPolicy.NONE)),
				Collections.<String>emptySet(), new ArrayList<>()).reload(null);

		// Then one where the file cannot be parsed, i.e. invalid TOML.
		MotdReloadService broken = service(reloader, () -> {
			throw new IOException("Invalid TOML document: unexpected assertion");
		}, Collections.<String>emptySet(), recorded);

		MotdReloadService.ReloadOutcome outcome = broken.reload(null);

		assertFalse(outcome.ok());
		assertTrue(outcome.error().contains("could not read the configuration"), outcome.error());
		assertEquals(1L, outcome.revision(), "the failure must report the revision still active");
		assertEquals(EnumMotdPassthroughPolicy.NONE, reloader.active().forListener("listener0").policy(),
				"a failed reload must not change the active policy");
		assertEquals(1, recorded.size(), "even a failure must be recorded");
	}

	@Test
	void anUnknownBackendNameRejectsTheWholeReload() {
		MotdPolicyReloader reloader = new MotdPolicyReloader();

		MotdReloadService service = service(reloader,
				() -> Collections.singletonList(
						view("listener0", EnumMotdPassthroughPolicy.SELECTED, "lobby", "ghost")),
				Collections.singleton("lobby"), new ArrayList<>());

		MotdReloadService.ReloadOutcome outcome = service.reload(null);

		assertFalse(outcome.ok());
		assertTrue(outcome.error().contains("ghost"), outcome.error());
		assertTrue(outcome.error().contains("not defined in velocity.toml"), outcome.error());

		// Crucially, nothing was published: one bad name must not half-apply the rest.
		assertEquals(0L, reloader.activeRevision());
		assertTrue(reloader.active().isEmpty());
	}

	/**
	 * A listener with no name cannot be addressed, so it is skipped rather than
	 * being published under a blank key that could never be matched.
	 */
	@Test
	void unnamedListenersAreSkipped() {
		MotdPolicyReloader reloader = new MotdPolicyReloader();

		MotdReloadService service = service(reloader,
				() -> Arrays.asList(view(null, EnumMotdPassthroughPolicy.NONE),
						view("listener1", EnumMotdPassthroughPolicy.ALL)),
				Collections.<String>emptySet(), new ArrayList<>());

		MotdReloadService.ReloadOutcome outcome = service.reload(null);

		assertTrue(outcome.ok(), String.valueOf(outcome.error()));
		assertEquals(1, outcome.listeners().size());
		assertEquals(EnumMotdPassthroughPolicy.ALL, reloader.active().forListener("listener1").policy());
	}

	/**
	 * The loader falls back to ALL when the value is unparseable, so that a typo
	 * cannot stop the proxy booting. A reload must NOT inherit that fallback: the
	 * operator wrote something wrong, and silently widening the policy could publish
	 * MOTDs they had deliberately excluded.
	 */
	@Test
	void anInvalidPolicyValueRejectsTheReloadInsteadOfFallingBackToAll() {
		MotdPolicyReloader reloader = new MotdPolicyReloader();

		// First establish a restrictive policy.
		service(reloader, () -> Collections.singletonList(view("listener0", EnumMotdPassthroughPolicy.NONE)),
				Collections.<String>emptySet(), new ArrayList<>()).reload(null);

		// Now the file contains a typo, which the loader reports as ALL.
		MotdReloadService broken = service(reloader,
				() -> Collections.singletonList(
						viewRaw("listener0", EnumMotdPassthroughPolicy.ALL, "bogus")),
				Collections.<String>emptySet(), new ArrayList<>());

		MotdReloadService.ReloadOutcome outcome = broken.reload(null);

		assertFalse(outcome.ok(), "an invalid policy value must be rejected");
		assertTrue(outcome.error().contains("bogus"), outcome.error());
		assertEquals(1L, outcome.revision(), "the failure must report the revision still active");
		assertEquals(EnumMotdPassthroughPolicy.NONE, reloader.active().forListener("listener0").policy(),
				"the previous policy must survive an invalid value");
	}

	@Test
	void aConfigurationWithNoListenersIsRejected() {
		MotdPolicyReloader reloader = new MotdPolicyReloader();

		MotdReloadService service = service(reloader, Collections::<MotdReloadService.ListenerView>emptyList,
				Collections.<String>emptySet(), new ArrayList<>());

		MotdReloadService.ReloadOutcome outcome = service.reload(null);

		assertFalse(outcome.ok());
		assertEquals(0L, reloader.activeRevision());
	}

	// ------------------------------------------------------------------
	// ordering
	// ------------------------------------------------------------------

	@Test
	void aStaleExpectedRevisionIsRefusedEndToEnd() {
		MotdPolicyReloader reloader = new MotdPolicyReloader();
		Set<String> known = Collections.singleton("lobby");

		MotdReloadService service = service(reloader,
				() -> Collections.singletonList(view("listener0", EnumMotdPassthroughPolicy.NONE)), known,
				new ArrayList<>());

		service.reload(null);
		service.reload(null);

		MotdReloadService.ReloadOutcome outcome = service.reload(Long.valueOf(1L));

		assertFalse(outcome.ok());
		assertTrue(outcome.error().contains("stale"), outcome.error());
		assertEquals(2L, reloader.activeRevision());
	}

	/**
	 * A sink that throws must not turn a good reload into a failure: the policy is
	 * already live by then.
	 */
	@Test
	void aFailingOutcomeSinkDoesNotChangeTheResult() {
		MotdPolicyReloader reloader = new MotdPolicyReloader();

		MotdReloadService service = new MotdReloadService(reloader,
				() -> Collections.singletonList(view("listener0", EnumMotdPassthroughPolicy.NONE)), name -> true,
				outcome -> {
					throw new IllegalStateException("disk full");
				});

		MotdReloadService.ReloadOutcome outcome = service.reload(null);

		assertTrue(outcome.ok());
		assertEquals(1L, reloader.activeRevision());
	}

	// ------------------------------------------------------------------
	// repeated reloads
	// ------------------------------------------------------------------

	/**
	 * Many reloads must not accumulate state: the revision advances, but the
	 * snapshot still holds exactly the listeners the configuration names.
	 */
	@Test
	void repeatedReloadsDoNotAccumulateListeners() {
		MotdPolicyReloader reloader = new MotdPolicyReloader();

		MotdReloadService service = service(reloader,
				() -> Collections.singletonList(view("listener0", EnumMotdPassthroughPolicy.NONE)),
				Collections.<String>emptySet(), new ArrayList<>());

		for (int i = 0; i < 200; i++) {
			assertTrue(service.reload(null).ok());
		}

		assertEquals(200L, reloader.activeRevision());
		assertEquals(1, reloader.active().listeners().size());
	}

}
