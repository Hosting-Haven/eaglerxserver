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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.lax1dude.eaglercraft.backend.server.api.EnumMotdPassthroughPolicy;

/**
 * An immutable, revisioned view of every listener's MOTD passthrough
 * eligibility.
 *
 * <p>
 * The MOTD path runs on Netty threads and must never take a lock, so a whole
 * snapshot is published by a single volatile write and then read without
 * synchronisation. Everything here is deeply immutable: the maps and sets are
 * copies that cannot be reached or mutated by a caller.
 * </p>
 *
 * <p>
 * A snapshot carries a monotonically increasing {@link #revision()}. That is what
 * lets a caller distinguish "the policy I asked for is now active" from "some
 * other reload won the race", without having to inspect the policy contents.
 * </p>
 */
final class MotdPolicySnapshot {

	/** The state before any reload has run: nothing is overridden. */
	static final MotdPolicySnapshot EMPTY = new MotdPolicySnapshot(0L, Collections.emptyMap());

	/**
	 * One listener's eligibility.
	 */
	static final class ListenerPolicy {

		private final EnumMotdPassthroughPolicy policy;

		private final Set<String> allowed;

		/** Lower-cased allowed names, so a lookup never has to scan the set. */
		private final Set<String> allowedLookup;

		private ListenerPolicy(EnumMotdPassthroughPolicy policy, Collection<String> allowed) {
			this.policy = policy == null ? EnumMotdPassthroughPolicy.ALL : policy;

			Set<String> names = new LinkedHashSet<>();
			Set<String> lookup = new LinkedHashSet<>();

			if (allowed != null) {
				for (String name : allowed) {
					if (name == null) {
						continue;
					}

					String trimmed = name.trim();

					if (trimmed.isEmpty()) {
						continue;
					}

					// Keep the spelling the configuration used, so a diagnostic or an
					// operator-facing dump reads the way the file does.
					names.add(trimmed);
					lookup.add(trimmed.toLowerCase(Locale.ROOT));
				}
			}

			this.allowed = Collections.unmodifiableSet(names);
			this.allowedLookup = Collections.unmodifiableSet(lookup);
		}

		static ListenerPolicy of(EnumMotdPassthroughPolicy policy, Collection<String> allowed) {
			return new ListenerPolicy(policy, allowed);
		}

		EnumMotdPassthroughPolicy policy() {
			return policy;
		}

		Collection<String> allowed() {
			return allowed;
		}

		/**
		 * Case-insensitive membership, matching how the configuration is compared
		 * everywhere else.
		 */
		boolean allows(String backend) {
			return backend != null && allowedLookup.contains(backend.toLowerCase(Locale.ROOT));
		}
	}

	private final long revision;

	private final Map<String, ListenerPolicy> byListener;

	private MotdPolicySnapshot(long revision, Map<String, ListenerPolicy> byListener) {
		this.revision = revision;
		this.byListener = byListener;
	}

	/**
	 * Build the next snapshot, copying the map so no caller retains a reference to
	 * the internal state.
	 */
	static MotdPolicySnapshot of(long revision, Map<String, ListenerPolicy> byListener) {
		Map<String, ListenerPolicy> copy = new LinkedHashMap<>();

		if (byListener != null) {
			copy.putAll(byListener);
		}

		return new MotdPolicySnapshot(revision, Collections.unmodifiableMap(copy));
	}

	long revision() {
		return revision;
	}

	/**
	 * The policy published for a listener, or null when this snapshot says nothing
	 * about it.
	 *
	 * Null is the signal to fall back to the listener's startup configuration,
	 * which is what keeps a listener that was added after the last reload working.
	 */
	ListenerPolicy forListener(String listenerName) {
		return listenerName == null ? null : byListener.get(listenerName);
	}

	Map<String, ListenerPolicy> listeners() {
		return byListener;
	}

	boolean isEmpty() {
		return byListener.isEmpty();
	}

}
