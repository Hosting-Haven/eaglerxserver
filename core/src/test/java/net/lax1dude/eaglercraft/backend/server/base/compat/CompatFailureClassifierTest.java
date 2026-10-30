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

package net.lax1dude.eaglercraft.backend.server.base.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rule under test: retry only when retrying can help.
 * 
 * These are the classifications behind the reported bug, where every failure --
 * a whitelist rejection and an unresolvable hostname included -- was answered by
 * cycling 340 / 754 / 47 and then blaming the protocol.
 */
class CompatFailureClassifierTest {

	private static CompatFailure classify(BackendConnectStatus status, BackendReasonKind reason, Throwable cause) {
		return CompatFailureClassifier.classify(status, reason, cause);
	}

	// ------------------------------------------------------------------
	// terminal backend rejections
	// ------------------------------------------------------------------

	@Test
	@DisplayName("a whitelist rejection is terminal and never retried")
	void whitelistRejectionIsTerminal() {
		CompatFailure f = classify(BackendConnectStatus.SERVER_DISCONNECTED, BackendReasonKind.BACKEND_AUTHORED, null);

		assertEquals(CompatFailureCategory.BACKEND_REJECTED, f.getCategory());
		assertFalse(f.isRetryable(), "a whitelist rejection must not be retried");
		assertFalse(f.advancesProtocol());
	}

	@Test
	@DisplayName("a ban is terminal and never retried")
	void banIsTerminal() {
		CompatFailure f = classify(BackendConnectStatus.SERVER_DISCONNECTED, BackendReasonKind.BACKEND_AUTHORED, null);

		assertEquals(CompatFailureCategory.BACKEND_REJECTED, f.getCategory());
		assertFalse(f.isRetryable());
	}

	@Test
	@DisplayName("a full server is terminal and never retried")
	void serverFullIsTerminal() {
		CompatFailure f = classify(BackendConnectStatus.SERVER_DISCONNECTED, BackendReasonKind.BACKEND_AUTHORED, null);

		assertEquals(CompatFailureCategory.BACKEND_REJECTED, f.getCategory());
		assertFalse(f.isRetryable());
	}

	@Test
	@DisplayName("an authentication rejection is terminal")
	void authenticationRejectionIsTerminal() {
		CompatFailure f = classify(BackendConnectStatus.SERVER_DISCONNECTED, BackendReasonKind.BACKEND_AUTHORED, null);

		assertFalse(f.isRetryable());
	}

	// ------------------------------------------------------------------
	// DNS
	// ------------------------------------------------------------------

	@Test
	@DisplayName("an unresolvable backend host is a DNS failure, not a protocol problem")
	void unknownHostIsDnsFailure() {
		CompatFailure f = classify(BackendConnectStatus.UNKNOWN, BackendReasonKind.NONE,
				new UnknownHostException("09d1dc06-027e-460f-8ac6-5f24a74cafea"));

		assertEquals(CompatFailureCategory.DNS_RESOLUTION_FAILURE, f.getCategory());
		assertFalse(f.isRetryable(), "cycling protocol versions cannot resolve a hostname");
	}

	@Test
	@DisplayName("a DNS failure wrapped in CompletionException is still a DNS failure")
	void wrappedUnknownHostIsDnsFailure() {
		Throwable wrapped = new CompletionException(new UnknownHostException("backend.internal"));

		CompatFailure f = classify(BackendConnectStatus.UNKNOWN, BackendReasonKind.NONE, wrapped);

		assertEquals(CompatFailureCategory.DNS_RESOLUTION_FAILURE, f.getCategory());
		assertFalse(f.isRetryable());
	}

	@Test
	@DisplayName("a deeply wrapped DNS failure is still detected")
	void doublyWrappedUnknownHostIsDnsFailure() {
		Throwable wrapped = new ExecutionException(new CompletionException(new UnknownHostException("nope")));

		CompatFailure f = classify(BackendConnectStatus.UNKNOWN, BackendReasonKind.NONE, wrapped);

		assertEquals(CompatFailureCategory.DNS_RESOLUTION_FAILURE, f.getCategory());
	}

	@Test
	@DisplayName("an unresolved NIO address is a DNS failure")
	void unresolvedAddressIsDnsFailure() {
		CompatFailure f = classify(BackendConnectStatus.UNKNOWN, BackendReasonKind.NONE,
				new UnresolvedAddressException());

		assertEquals(CompatFailureCategory.DNS_RESOLUTION_FAILURE, f.getCategory());
		assertFalse(f.isRetryable());
	}

	// ------------------------------------------------------------------
	// connectivity
	// ------------------------------------------------------------------

	@Test
	@DisplayName("a refused connection is terminal, since a closed port stays closed")
	void connectionRefusedIsTerminal() {
		CompatFailure f = classify(BackendConnectStatus.UNKNOWN, BackendReasonKind.NONE,
				new ConnectException("Connection refused"));

		assertEquals(CompatFailureCategory.CONNECTION_REFUSED, f.getCategory());
		assertFalse(f.isRetryable(), "another protocol version cannot open a closed port");
	}

	@Test
	@DisplayName("an unreachable route may be retried, but not by changing protocol")
	void networkUnreachableIsBoundedRetry() {
		CompatFailure f = classify(BackendConnectStatus.UNKNOWN, BackendReasonKind.NONE,
				new NoRouteToHostException("No route to host"));

		assertEquals(CompatFailureCategory.NETWORK_UNREACHABLE, f.getCategory());
		assertTrue(f.isRetryable(), "an unreachable route can recover");
		assertFalse(f.advancesProtocol(), "the protocol version is not the problem");
	}

	@Test
	@DisplayName("a timeout may be retried, but not by changing protocol")
	void timeoutIsBoundedRetry() {
		CompatFailure f = classify(BackendConnectStatus.UNKNOWN, BackendReasonKind.NONE,
				new SocketTimeoutException("connect timed out"));

		assertEquals(CompatFailureCategory.TIMEOUT, f.getCategory());
		assertTrue(f.isRetryable());
		assertFalse(f.advancesProtocol());
	}

	@Test
	@DisplayName("a TimeoutException is treated as a timeout")
	void timeoutExceptionIsTimeout() {
		CompatFailure f = classify(BackendConnectStatus.UNKNOWN, BackendReasonKind.NONE, new TimeoutException("slow"));

		assertEquals(CompatFailureCategory.TIMEOUT, f.getCategory());
	}

	// ------------------------------------------------------------------
	// genuine protocol mismatch
	// ------------------------------------------------------------------

	@Test
	@DisplayName("the platform's generic connection failure still allows protocol fallback")
	void transportGenericAllowsProtocolFallback() {
		CompatFailure f = classify(BackendConnectStatus.SERVER_DISCONNECTED, BackendReasonKind.TRANSPORT_GENERIC, null);

		assertEquals(CompatFailureCategory.PROTOCOL_MISMATCH, f.getCategory());
		assertTrue(f.isRetryable());
		assertTrue(f.advancesProtocol(), "the only category that should change protocol version");
	}

	@Test
	@DisplayName("a backend that disconnects with no reason still allows protocol fallback")
	void disconnectedWithoutReasonAllowsProtocolFallback() {
		CompatFailure f = classify(BackendConnectStatus.SERVER_DISCONNECTED, BackendReasonKind.NONE, null);

		assertEquals(CompatFailureCategory.PROTOCOL_MISMATCH, f.getCategory());
		assertTrue(f.isRetryable());
		assertTrue(f.advancesProtocol());
	}

	// ------------------------------------------------------------------
	// termination statuses
	// ------------------------------------------------------------------

	@Test
	@DisplayName("already connected is terminal")
	void alreadyConnectedIsTerminal() {
		CompatFailure f = classify(BackendConnectStatus.ALREADY_CONNECTED, BackendReasonKind.NONE, null);

		assertEquals(CompatFailureCategory.ALREADY_CONNECTED, f.getCategory());
		assertFalse(f.isRetryable());
	}

	@Test
	@DisplayName("a connection in progress is terminal")
	void connectionInProgressIsTerminal() {
		CompatFailure f = classify(BackendConnectStatus.CONNECTION_IN_PROGRESS, BackendReasonKind.NONE, null);

		assertEquals(CompatFailureCategory.CONNECTION_IN_PROGRESS, f.getCategory());
		assertFalse(f.isRetryable());
	}

	@Test
	@DisplayName("a cancelled connection is terminal")
	void connectionCancelledIsTerminal() {
		CompatFailure f = classify(BackendConnectStatus.CONNECTION_CANCELLED, BackendReasonKind.NONE, null);

		assertEquals(CompatFailureCategory.CONNECTION_CANCELLED, f.getCategory());
		assertFalse(f.isRetryable());
	}

	// ------------------------------------------------------------------
	// never invent a diagnosis
	// ------------------------------------------------------------------

	@Test
	@DisplayName("an unrecognised status is reported as unknown, never as a protocol mismatch")
	void unknownStatusIsUnknown() {
		CompatFailure f = classify(BackendConnectStatus.UNKNOWN, BackendReasonKind.NONE, null);

		assertEquals(CompatFailureCategory.UNKNOWN, f.getCategory());
		assertFalse(f.isRetryable());
	}

	@Test
	@DisplayName("an unrecognised exception is not blamed on the protocol")
	void unrecognisedExceptionIsUnknown() {
		CompatFailure f = classify(BackendConnectStatus.UNKNOWN, BackendReasonKind.NONE,
				new IllegalStateException("something odd"));

		assertEquals(CompatFailureCategory.UNKNOWN, f.getCategory());
		assertFalse(f.isRetryable());
	}

	@Test
	@DisplayName("null inputs are handled without throwing")
	void nullInputsAreSafe() {
		CompatFailure f = classify(null, null, null);

		assertEquals(CompatFailureCategory.UNKNOWN, f.getCategory());
		assertFalse(f.isRetryable());
	}

	@Test
	@DisplayName("only PROTOCOL_MISMATCH changes the protocol version")
	void onlyProtocolMismatchAdvancesProtocol() {
		for (CompatFailureCategory category : CompatFailureCategory.values()) {
			if (category.advancesProtocol()) {
				assertEquals(CompatFailureCategory.PROTOCOL_MISMATCH, category);
			}
		}
	}

	// ------------------------------------------------------------------
	// log detail
	// ------------------------------------------------------------------

	@Test
	@DisplayName("the technical detail is captured for the log, not for the player")
	void detailCarriesExceptionClass() {
		CompatFailure f = classify(BackendConnectStatus.UNKNOWN, BackendReasonKind.NONE,
				new UnknownHostException("backend.internal"));

		assertTrue(f.getDetail().contains("UnknownHostException"), f.getDetail());
	}

	@Test
	@DisplayName("the root cause is unwrapped past asynchronous wrappers")
	void rootCauseUnwrapsAsyncWrappers() {
		Throwable root = new UnknownHostException("x");
		Throwable wrapped = new CompletionException(new ExecutionException(root));

		assertEquals(root, CompatFailureClassifier.rootCause(wrapped));
	}

}
