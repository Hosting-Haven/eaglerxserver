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

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Decides what a failed backend connection attempt actually means.
 * 
 * The rule this class exists to enforce: <b>retry only when retrying can
 * help</b>. A rejected login, an unresolvable hostname or a closed port all
 * produce the same answer for every protocol version, so cycling 1.16.5 /
 * 1.12.2 / 1.8 after them only delays the inevitable and hides the real reason
 * from the player.
 * 
 * Classification never invents a diagnosis. When nothing identifies the
 * failure, the result is {@link CompatFailureCategory#UNKNOWN} rather than a
 * protocol incompatibility.
 */
public final class CompatFailureClassifier {

	private static final int MAX_CAUSE_DEPTH = 10;

	private CompatFailureClassifier() {
	}

	/**
	 * Classify one failed attempt.
	 * 
	 * @param status     normalized outcome reported by the platform
	 * @param reasonKind who authored the disconnect message, if there is one
	 * @param cause      the exceptional completion cause, or null when the attempt
	 *                   completed normally with a failure status
	 */
	public static CompatFailure classify(BackendConnectStatus status, BackendReasonKind reasonKind, Throwable cause) {
		BackendReasonKind kind = reasonKind == null ? BackendReasonKind.NONE : reasonKind;

		// A throwable carries the most specific information, so it is inspected
		// first. An unrecognised throwable falls through to the status below.
		if (cause != null) {
			Throwable root = rootCause(cause);
			CompatFailureCategory byCause = classifyCause(root);

			if (byCause != null) {
				return CompatFailure.of(byCause, describe(root));
			}
		}

		BackendConnectStatus effective = status == null ? BackendConnectStatus.UNKNOWN : status;

		switch (effective) {
			case ALREADY_CONNECTED:
				return CompatFailure.of(CompatFailureCategory.ALREADY_CONNECTED);
			case CONNECTION_IN_PROGRESS:
				return CompatFailure.of(CompatFailureCategory.CONNECTION_IN_PROGRESS);
			case CONNECTION_CANCELLED:
				return CompatFailure.of(CompatFailureCategory.CONNECTION_CANCELLED);
			case SERVER_DISCONNECTED:
				if (kind == BackendReasonKind.BACKEND_AUTHORED) {
					// The backend answered deliberately. Every protocol version gets the
					// same answer, and the message belongs to the player.
					return CompatFailure.of(CompatFailureCategory.BACKEND_REJECTED);
				}

				// Either the platform generated its own connection-failure message or the
				// backend closed without one. Both are the classic signature of a failed
				// protocol negotiation, so the compatibility ladder may be walked.
				return CompatFailure.of(CompatFailureCategory.PROTOCOL_MISMATCH, kind == BackendReasonKind.TRANSPORT_GENERIC
						? "platform-reported connection failure"
						: "backend disconnected without a reason");
			default:
				return CompatFailure.of(CompatFailureCategory.UNKNOWN,
						cause != null ? describe(rootCause(cause)) : "unrecognised status");
		}
	}

	/**
	 * Map a transport-level exception onto a category, or null when the exception
	 * says nothing useful about reachability.
	 */
	private static CompatFailureCategory classifyCause(Throwable t) {
		if (t == null) {
			return null;
		}

		if (t instanceof UnknownHostException || t instanceof UnresolvedAddressException) {
			return CompatFailureCategory.DNS_RESOLUTION_FAILURE;
		}

		if (t instanceof NoRouteToHostException) {
			return CompatFailureCategory.NETWORK_UNREACHABLE;
		}

		if (t instanceof SocketTimeoutException || t instanceof TimeoutException) {
			return CompatFailureCategory.TIMEOUT;
		}

		if (t instanceof ConnectException) {
			// ConnectException is overwhelmingly "connection refused"; a nested
			// timeout still means the same thing operationally.
			return isTimedOut(t) ? CompatFailureCategory.TIMEOUT : CompatFailureCategory.CONNECTION_REFUSED;
		}

		if (t instanceof SocketException) {
			String message = lower(t.getMessage());

			if (message.contains("refused")) {
				return CompatFailureCategory.CONNECTION_REFUSED;
			}
			if (message.contains("unreachable") || message.contains("no route")) {
				return CompatFailureCategory.NETWORK_UNREACHABLE;
			}
			if (message.contains("timed out") || message.contains("timeout")) {
				return CompatFailureCategory.TIMEOUT;
			}
			if (message.contains("unknown host") || message.contains("resolve") || message.contains("nodename")) {
				return CompatFailureCategory.DNS_RESOLUTION_FAILURE;
			}

			// An unrecognised socket error is still a connectivity failure, but not one
			// we can name, so report it neutrally rather than guess.
			return null;
		}

		if (t instanceof IOException) {
			return null;
		}

		return null;
	}

	private static boolean isTimedOut(Throwable t) {
		String message = lower(t.getMessage());

		return message.contains("timed out") || message.contains("timeout");
	}

	/**
	 * Strip the asynchronous wrapping the platform adds so the real cause is
	 * examined.
	 */
	public static Throwable rootCause(Throwable t) {
		Throwable current = t;

		for (int i = 0; i < MAX_CAUSE_DEPTH; ++i) {
			if (current instanceof CompletionException || current instanceof ExecutionException
					|| current instanceof InvocationTargetException) {
				Throwable cause = current.getCause();
				if (cause == null || cause == current) {
					return current;
				}
				current = cause;
			} else {
				return current;
			}
		}

		return current;
	}

	private static String describe(Throwable t) {
		if (t == null) {
			return "";
		}

		String message = t.getMessage();

		return message == null ? t.getClass().getSimpleName()
				: t.getClass().getSimpleName() + ": " + message;
	}

	private static String lower(String s) {
		return s == null ? "" : s.toLowerCase(Locale.ROOT);
	}

}
