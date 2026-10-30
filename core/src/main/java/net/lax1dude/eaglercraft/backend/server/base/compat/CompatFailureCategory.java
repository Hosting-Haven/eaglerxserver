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

/**
 * Why a backend connection attempt failed, and therefore whether retrying could
 * possibly change the outcome.
 * 
 * The retry policy is deliberately conservative: retrying is only allowed when
 * the failure is plausibly caused by the thing being varied (the protocol
 * version) or by something transient.
 */
public enum CompatFailureCategory {

	/**
	 * The backend deliberately refused this player and said why (whitelist, ban,
	 * server full, authentication, or a plugin message). Terminal: the same answer
	 * will come back for every protocol version, and re-attempting login a few more
	 * times only spams the backend and delays the message.
	 */
	BACKEND_REJECTED(false, false),

	/**
	 * The backend could not negotiate a protocol version. This is the only category
	 * where walking to another supported protocol version is legitimate.
	 */
	PROTOCOL_MISMATCH(true, true),

	/**
	 * The backend hostname could not be resolved. Terminal: the protocol version is
	 * unrelated to DNS.
	 */
	DNS_RESOLUTION_FAILURE(false, false),

	/**
	 * The backend refused the TCP connection (nothing listening). Terminal: a
	 * different protocol version cannot make a closed port open.
	 */
	CONNECTION_REFUSED(false, false),

	/**
	 * The network reported the host unreachable. Could be transient, so a bounded
	 * re-attempt of the same protocol is allowed.
	 */
	NETWORK_UNREACHABLE(true, false),

	/**
	 * The attempt timed out. Could be transient, so a bounded re-attempt of the same
	 * protocol is allowed.
	 */
	TIMEOUT(true, false),

	/** The player is already connected to that backend. Terminal, not an error. */
	ALREADY_CONNECTED(false, false),

	/** A connection to that backend is already in progress. Terminal. */
	CONNECTION_IN_PROGRESS(false, false),

	/** A plugin cancelled the connection. Terminal. */
	CONNECTION_CANCELLED(false, false),

	/**
	 * Nothing identified the failure. Terminal and reported neutrally: an unknown
	 * error is never evidence of a protocol mismatch.
	 */
	UNKNOWN(false, false);

	private final boolean retryable;
	private final boolean advancesProtocol;

	CompatFailureCategory(boolean retryable, boolean advancesProtocol) {
		this.retryable = retryable;
		this.advancesProtocol = advancesProtocol;
	}

	/**
	 * Whether another attempt may be made at all.
	 */
	public boolean isRetryable() {
		return retryable;
	}

	/**
	 * Whether that attempt should move to the next protocol version. When false the
	 * same protocol is retried, which is the only meaningful behaviour for a
	 * transient transport failure.
	 */
	public boolean advancesProtocol() {
		return advancesProtocol;
	}

}
