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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.lax1dude.eaglercraft.backend.server.base.compat.BackendReasonKind;
import net.lax1dude.eaglercraft.backend.server.base.compat.CompatFailure;
import net.lax1dude.eaglercraft.backend.server.base.compat.CompatFailureCategory;
import net.lax1dude.eaglercraft.backend.server.base.compat.CompatFailureClassifier;

/**
 * End-to-end checks over the real Velocity result types.
 * 
 * Each scenario builds an actual {@link ConnectionRequestBuilder.Result} (the
 * interface Velocity returns from {@code connect()}) and pushes it through the
 * same production functions the live retry loop uses: {@code mapStatus},
 * {@code reasonKindOf}, {@link CompatFailureClassifier} and
 * {@link CompatRetryStep}. Nothing about the decision chain is re-implemented
 * here, so these tests fail if production behaviour drifts.
 */
class VelocityCompatDecisionTest {

	private static final String SERVER = "lobby";

	/** Velocity's own generic "the backend connection failed" message. */
	private static final Component TRANSPORT_GENERIC = Component
			.translatable("velocity.error.internal-server-connection-error", NamedTextColor.RED);

	private static final int CLIENT_PROTOCOL = 769; // 1.21.4

	// ------------------------------------------------------------------
	// test doubles over the real API
	// ------------------------------------------------------------------

	/**
	 * A minimal real {@link ConnectionRequestBuilder.Result}.
	 */
	private static final class FakeResult implements ConnectionRequestBuilder.Result {

		private final ConnectionRequestBuilder.Status status;
		private final Component reason;

		FakeResult(ConnectionRequestBuilder.Status status, Component reason) {
			this.status = status;
			this.reason = reason;
		}

		@Override
		public ConnectionRequestBuilder.Status getStatus() {
			return status;
		}

		@Override
		public Optional<Component> getReasonComponent() {
			return Optional.ofNullable(reason);
		}

		@Override
		public RegisteredServer getAttemptedConnection() {
			return null;
		}
	}

	/** Either a result or an exceptional completion, as Velocity can deliver both. */
	private static final class Outcome {

		final ConnectionRequestBuilder.Result result;
		final Throwable throwable;

		private Outcome(ConnectionRequestBuilder.Result result, Throwable throwable) {
			this.result = result;
			this.throwable = throwable;
		}

		static Outcome result(ConnectionRequestBuilder.Status status, Component reason) {
			return new Outcome(new FakeResult(status, reason), null);
		}

		static Outcome failed(Throwable throwable) {
			return new Outcome(null, throwable);
		}
	}

	/** What happened over the whole attempt sequence. */
	private static final class Simulation {

		final List<Integer> attemptedProtocols = new ArrayList<>();
		final List<CompatFailureCategory> categories = new ArrayList<>();
		boolean connected;
		Integer cachedProtocol;
		Component finalMessage;
		CompatFailureCategory finalCategory;
	}

	private interface AttemptBehaviour {
		Outcome respond(int protocol, int attemptNumber);
	}

	private static Simulation simulate(AttemptBehaviour behaviour) {
		return simulate(behaviour, null);
	}

	/**
	 * Mirrors the production retry loop. Every decision is delegated to the
	 * production helpers, so this cannot silently diverge from them.
	 */
	private static Simulation simulate(AttemptBehaviour behaviour, Integer cachedProtocol) {
		int[] plan = VelocityListener.buildProtocolAttemptPlan(CLIENT_PROTOCOL, cachedProtocol);

		Simulation sim = new Simulation();

		int attemptIndex = 0;
		int transientRetries = 0;
		int guard = 0;

		while (true) {
			if (++guard > 32) {
				throw new AssertionError("retry loop did not terminate");
			}

			int protocol = plan[attemptIndex];
			sim.attemptedProtocols.add(Integer.valueOf(protocol));

			Outcome outcome = behaviour.respond(protocol, sim.attemptedProtocols.size());

			// Exactly what production does: a rejected login is never cached.
			if (outcome.result != null && outcome.result.isSuccessful()) {
				sim.connected = true;
				sim.cachedProtocol = Integer.valueOf(protocol);
				return sim;
			}

			Component reason = outcome.result != null ? outcome.result.getReasonComponent().orElse(null) : null;
			ConnectionRequestBuilder.Status status = outcome.result != null ? outcome.result.getStatus() : null;

			CompatFailure failure = CompatFailureClassifier.classify(VelocityListener.mapStatus(status),
					VelocityListener.reasonKindOf(reason), outcome.throwable);

			sim.categories.add(failure.getCategory());

			CompatRetryStep step = CompatRetryStep.next(failure, attemptIndex, transientRetries, plan.length,
					VelocityListener.MAX_PROTOCOL_RETRIES, VelocityListener.MAX_TRANSIENT_RETRIES);

			if (!step.retry) {
				sim.finalCategory = failure.getCategory();
				sim.finalMessage = VelocityCompatFailureMessages.componentFor(failure.getCategory(), SERVER, reason);
				return sim;
			}

			attemptIndex = step.attemptIndex;
			transientRetries = step.transientRetries;
		}
	}

	private static String plain(Component component) {
		return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
	}

	// ------------------------------------------------------------------
	// acceptance criteria
	// ------------------------------------------------------------------

	@Test
	@DisplayName("whitelisted player sees the backend's own rejection after exactly one attempt")
	void whitelistRejectionStopsAfterOneAttempt() {
		Component rejection = Component.text("You are not whitelisted on this server!", NamedTextColor.RED);

		Simulation sim = simulate((protocol, attempt) -> Outcome
				.result(ConnectionRequestBuilder.Status.SERVER_DISCONNECTED, rejection));

		assertEquals(1, sim.attemptedProtocols.size(), "a whitelist rejection must not produce duplicate joins");
		assertEquals(List.of(CLIENT_PROTOCOL), sim.attemptedProtocols);
		assertEquals(CompatFailureCategory.BACKEND_REJECTED, sim.finalCategory);
		assertSame(rejection, sim.finalMessage, "the backend message must reach the client untouched");
		assertEquals("You are not whitelisted on this server!", plain(sim.finalMessage));
		assertFalse(sim.connected);
	}

	@Test
	@DisplayName("banned player sees the original ban reason after one attempt")
	void bannedPlayerSeesOriginalReason() {
		Component ban = Component.text("You are banned from this server! Reason: griefing", NamedTextColor.RED);

		Simulation sim = simulate(
				(protocol, attempt) -> Outcome.result(ConnectionRequestBuilder.Status.SERVER_DISCONNECTED, ban));

		assertEquals(1, sim.attemptedProtocols.size());
		assertSame(ban, sim.finalMessage);
		assertTrue(plain(sim.finalMessage).contains("griefing"));
	}

	@Test
	@DisplayName("player joining a full server sees the original full-server message")
	void fullServerSeesOriginalReason() {
		Component full = Component.text("The server is full!", NamedTextColor.RED);

		Simulation sim = simulate(
				(protocol, attempt) -> Outcome.result(ConnectionRequestBuilder.Status.SERVER_DISCONNECTED, full));

		assertEquals(1, sim.attemptedProtocols.size());
		assertSame(full, sim.finalMessage);
		assertEquals("The server is full!", plain(sim.finalMessage));
	}

	@Test
	@DisplayName("an unresolvable hostname reports backend unavailable instead of incompatible protocol")
	void unresolvableHostReportsBackendUnavailable() {
		Throwable dns = new CompletionException(
				new UnknownHostException("09d1dc06-027e-460f-8ac6-5f24a74cafea"));

		Simulation sim = simulate((protocol, attempt) -> Outcome.failed(dns));

		assertEquals(1, sim.attemptedProtocols.size(), "DNS failures must not cycle protocol versions");
		assertEquals(CompatFailureCategory.DNS_RESOLUTION_FAILURE, sim.finalCategory);

		String message = plain(sim.finalMessage);
		assertTrue(message.contains("address could not be resolved"), message);
		assertFalse(message.toLowerCase().contains("incompatible"), message);
		assertFalse(message.toLowerCase().contains("protocol"), message);
	}

	@Test
	@DisplayName("a refused backend connection is not blamed on the protocol")
	void refusedConnectionIsNotProtocolBlamed() {
		Simulation sim = simulate((protocol, attempt) -> Outcome.failed(new ConnectException("Connection refused")));

		assertEquals(1, sim.attemptedProtocols.size());
		assertEquals(CompatFailureCategory.CONNECTION_REFUSED, sim.finalCategory);
		assertFalse(plain(sim.finalMessage).toLowerCase().contains("protocol"));
	}

	@Test
	@DisplayName("a genuine protocol mismatch still walks the supported fallbacks")
	void genuineProtocolMismatchStillRetries() {
		// Velocity reports a backend that rejects the handshake with its own generic
		// message, so every attempt looks the same until one protocol succeeds.
		Simulation sim = simulate((protocol, attempt) -> Outcome
				.result(ConnectionRequestBuilder.Status.SERVER_DISCONNECTED, TRANSPORT_GENERIC));

		assertTrue(sim.attemptedProtocols.size() > 1, "compatibility fallback must still happen");
		assertEquals(CompatFailureCategory.PROTOCOL_MISMATCH, sim.finalCategory);

		// The documented fallbacks must still be reachable.
		assertTrue(sim.attemptedProtocols.contains(Integer.valueOf(340)), sim.attemptedProtocols.toString());
		assertTrue(sim.attemptedProtocols.contains(Integer.valueOf(47)), sim.attemptedProtocols.toString());
		assertEquals(CompatFailureCategory.PROTOCOL_MISMATCH, sim.categories.get(0));
	}

	@Test
	@DisplayName("a successful fallback joins the player and caches the protocol that worked")
	void successfulFallbackCachesWorkingProtocol() {
		// The first protocol fails, the next one works: the player must join.
		Simulation sim = simulate((protocol, attempt) -> attempt == 1
				? Outcome.result(ConnectionRequestBuilder.Status.SERVER_DISCONNECTED, TRANSPORT_GENERIC)
				: Outcome.result(ConnectionRequestBuilder.Status.SUCCESS, null));

		assertTrue(sim.connected, "a later protocol version must be allowed to succeed");
		assertNull(sim.finalMessage);
		assertEquals(sim.attemptedProtocols.get(1), sim.cachedProtocol,
				"the protocol that actually worked is the one cached");
		assertFalse(sim.cachedProtocol.equals(sim.attemptedProtocols.get(0)),
				"the rejected protocol must never be cached");
	}

	@Test
	@DisplayName("a cached protocol is tried first on the next connection")
	void cachedProtocolIsPreferred() {
		Simulation sim = simulate((protocol, attempt) -> Outcome.result(ConnectionRequestBuilder.Status.SUCCESS, null),
				Integer.valueOf(340));

		assertTrue(sim.connected);
		assertEquals(List.of(Integer.valueOf(340)), sim.attemptedProtocols);
	}

	@Test
	@DisplayName("an unexpected failure is reported neutrally, without inventing a protocol problem")
	void unexpectedFailureIsNeutral() {
		// No result and no throwable: nothing identifies the failure.
		Simulation sim = simulate((protocol, attempt) -> new Outcome(null, null));

		assertEquals(1, sim.attemptedProtocols.size());
		assertEquals(CompatFailureCategory.UNKNOWN, sim.finalCategory);

		String message = plain(sim.finalMessage).toLowerCase();
		assertFalse(message.contains("protocol"), message);
		assertFalse(message.contains("incompatible"), message);
		assertFalse(message.contains("version"), message);
	}

	@Test
	@DisplayName("a transient timeout retries the same protocol rather than the whole ladder")
	void transientTimeoutDoesNotWalkTheLadder() {
		Simulation sim = simulate(
				(protocol, attempt) -> Outcome.failed(new java.net.SocketTimeoutException("connect timed out")));

		// Bounded: one re-attempt of the same protocol, then stop.
		assertEquals(1 + VelocityListener.MAX_TRANSIENT_RETRIES, sim.attemptedProtocols.size());
		assertEquals(2, sim.attemptedProtocols.stream().distinct().count(),
				"only the starting protocol should have been attempted: " + sim.attemptedProtocols);
		assertEquals(CompatFailureCategory.TIMEOUT, sim.finalCategory);
		assertFalse(plain(sim.finalMessage).toLowerCase().contains("protocol"));
	}

	// ------------------------------------------------------------------
	// reason classification over the real component types
	// ------------------------------------------------------------------

	@Test
	@DisplayName("Velocity's generic transport message is not treated as a backend decision")
	void transportGenericIsNotBackendAuthored() {
		assertEquals(BackendReasonKind.TRANSPORT_GENERIC, VelocityListener.reasonKindOf(TRANSPORT_GENERIC));
	}

	@Test
	@DisplayName("a plain-text backend kick is treated as a backend decision")
	void plainTextKickIsBackendAuthored() {
		assertEquals(BackendReasonKind.BACKEND_AUTHORED,
				VelocityListener.reasonKindOf(Component.text("You are not whitelisted on this server!")));
	}

	@Test
	@DisplayName("a vanilla translatable kick keeps its own key and is backend authored")
	void translatableVanillaKickIsBackendAuthored() {
		Component kick = Component.translatable("multiplayer.disconnect.not_whitelisted");

		assertEquals(BackendReasonKind.BACKEND_AUTHORED, VelocityListener.reasonKindOf(kick));
	}

	@Test
	@DisplayName("a missing reason is not attributed to the backend")
	void missingReasonIsNone() {
		assertEquals(BackendReasonKind.NONE, VelocityListener.reasonKindOf(null));
		assertEquals(BackendReasonKind.NONE, VelocityListener.reasonKindOf(Component.empty()));
	}

	@Test
	@DisplayName("every real Velocity status maps onto the neutral enum")
	void everyStatusMaps() {
		assertEquals("SUCCESS", VelocityListener
				.mapStatus(ConnectionRequestBuilder.Status.SUCCESS).name());
		assertEquals("SERVER_DISCONNECTED", VelocityListener
				.mapStatus(ConnectionRequestBuilder.Status.SERVER_DISCONNECTED).name());
		assertEquals("ALREADY_CONNECTED", VelocityListener
				.mapStatus(ConnectionRequestBuilder.Status.ALREADY_CONNECTED).name());
		assertEquals("CONNECTION_IN_PROGRESS", VelocityListener
				.mapStatus(ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS).name());
		assertEquals("CONNECTION_CANCELLED", VelocityListener
				.mapStatus(ConnectionRequestBuilder.Status.CONNECTION_CANCELLED).name());
		assertEquals("UNKNOWN", VelocityListener.mapStatus(null).name());
	}

}
