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

import java.util.Collection;

import net.lax1dude.eaglercraft.backend.server.api.EnumMotdPassthroughPolicy;

/**
 * Whether one backend is allowed to supply MOTD information.
 *
 * <p>
 * This answers a single question about an <em>already chosen</em> backend. It is
 * deliberately not aware of routing, freshness, or ordering: the caller resolves
 * which backend Velocity would use first, and only then asks whether that choice
 * may be used. Answering in this order is what makes the feature purely a filter
 * on MOTD sourcing, so it can never change where a player is sent.
 * </p>
 *
 * <p>
 * The consequence is intentional and is pinned by tests: an excluded backend is
 * <em>not</em> replaced by the next permitted one. Every fallback that exists is
 * Velocity's own (a dead candidate is skipped because it is dead, not because it
 * is unlisted), and it happens before this check runs.
 * </p>
 *
 * Kept free of Velocity types so the rules can be tested directly.
 */
final class MotdPassthroughEligibility {

	private MotdPassthroughEligibility() {
	}

	/**
	 * Whether {@code backendName} may supply the MOTD under this policy.
	 *
	 * @param policy          the listener's policy; null is treated as
	 *                        {@link EnumMotdPassthroughPolicy#ALL} so a listener that
	 *                        predates the setting keeps the old behaviour
	 * @param allowedBackends the configured names, consulted only for
	 *                        {@link EnumMotdPassthroughPolicy#SELECTED}
	 * @param backendName     the backend already selected by the routing logic
	 */
	static boolean permits(EnumMotdPassthroughPolicy policy, Collection<String> allowedBackends, String backendName) {
		if (policy == null || policy == EnumMotdPassthroughPolicy.ALL) {
			return true;
		}

		if (policy == EnumMotdPassthroughPolicy.NONE) {
			return false;
		}

		// SELECTED. No name can match an empty list, and nothing can match a null name,
		// so both are a plain "no" rather than an error.
		if (backendName == null || backendName.isEmpty() || allowedBackends == null || allowedBackends.isEmpty()) {
			return false;
		}

		for (String allowed : allowedBackends) {
			if (allowed != null && allowed.trim().equalsIgnoreCase(backendName)) {
				return true;
			}
		}

		return false;
	}

}
