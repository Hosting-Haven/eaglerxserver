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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;


/**
 * Owns the active MOTD eligibility policy and publishes new revisions of it.
 *
 * <p>
 * Two properties matter and neither is obvious:
 * </p>
 *
 * <ol>
 * <li><b>Readers never block.</b> {@link #active()} is a single volatile read.
 * The MOTD path runs on a Netty event loop and must not queue behind an operator
 * typing a command, so publishing swaps one immutable object and nothing on the
 * read side takes a lock.</li>
 *
 * <li><b>Writers are serialised and ordered.</b> {@link #publish} holds a lock
 * across the read-modify-write of the revision, so two concurrent reloads cannot
 * both observe revision N and both publish N+1. A caller may additionally supply
 * the revision it based its decision on; a mismatch is rejected rather than
 * silently overwriting a newer policy with an older one.</li>
 * </ol>
 *
 * <p>
 * Publishing is all-or-nothing: the proposed map is fully built and validated by
 * the caller before this class is involved, and the swap is a single assignment.
 * A failed reload therefore leaves the previously active policy untouched — there
 * is no intermediate state in which half the listeners use the new policy.
 * </p>
 */
final class MotdPolicyReloader {

	/**
	 * The outcome of one publish attempt.
	 */
	static final class ReloadResult {

		private final boolean ok;

		private final long revision;

		private final String error;

		private final Map<String, MotdPolicySnapshot.ListenerPolicy> applied;

		private ReloadResult(boolean ok, long revision, String error,
				Map<String, MotdPolicySnapshot.ListenerPolicy> applied) {
			this.ok = ok;
			this.revision = revision;
			this.error = error;
			this.applied = applied;
		}

		static ReloadResult success(long revision, Map<String, MotdPolicySnapshot.ListenerPolicy> applied) {
			return new ReloadResult(true, revision, null, Collections.unmodifiableMap(new LinkedHashMap<>(applied)));
		}

		static ReloadResult failure(long revision, String error) {
			return new ReloadResult(false, revision, error == null ? "reload failed" : error,
					Collections.emptyMap());
		}

		boolean ok() {
			return ok;
		}

		/**
		 * The revision currently active after this attempt. On failure this is the
		 * revision that is STILL active, not the one that was rejected.
		 */
		long revision() {
			return revision;
		}

		String error() {
			return error;
		}

		Map<String, MotdPolicySnapshot.ListenerPolicy> applied() {
			return applied;
		}
	}

	private final ReentrantLock writeLock = new ReentrantLock();

	/**
	 * Written under {@link #writeLock} and read without it. Exactly this field is
	 * the "publish": one reference assignment is atomic and immediately visible.
	 */
	private volatile MotdPolicySnapshot active = MotdPolicySnapshot.EMPTY;

	/**
	 * The policy currently in force. Never null; before the first reload this is
	 * {@link MotdPolicySnapshot#EMPTY}, which overrides nothing.
	 */
	MotdPolicySnapshot active() {
		return active;
	}

	long activeRevision() {
		return active.revision();
	}

	/**
	 * Publish a fully built proposal.
	 *
	 * @param proposed        every listener's policy, already parsed and validated
	 * @param expectedRevision when non-null, the revision the caller believed was
	 *                        active. A mismatch means someone else published in the
	 *                        meantime, so this request is stale and is rejected.
	 */
	ReloadResult publish(Map<String, MotdPolicySnapshot.ListenerPolicy> proposed, Long expectedRevision) {
		writeLock.lock();

		try {
			MotdPolicySnapshot current = active;

			if (expectedRevision != null && expectedRevision.longValue() != current.revision()) {
				return ReloadResult.failure(current.revision(),
						"stale reload: expected revision " + expectedRevision + " but " + current.revision()
								+ " is active. Reload the page and try again.");
			}

			if (proposed == null || proposed.isEmpty()) {
				return ReloadResult.failure(current.revision(),
						"the proposed policy is empty, so nothing was applied");
			}

			for (Map.Entry<String, MotdPolicySnapshot.ListenerPolicy> entry : proposed.entrySet()) {
				if (entry.getKey() == null || entry.getKey().trim().isEmpty()) {
					return ReloadResult.failure(current.revision(),
							"a listener had no name, so nothing was applied");
				}

				if (entry.getValue() == null) {
					return ReloadResult.failure(current.revision(),
							"listener \"" + entry.getKey() + "\" had no policy, so nothing was applied");
				}
			}

			long next = current.revision() + 1L;
			MotdPolicySnapshot published = MotdPolicySnapshot.of(next, proposed);

			// The publish. A reader either sees the previous snapshot or this one.
			active = published;

			return ReloadResult.success(next, published.listeners());
		} finally {
			writeLock.unlock();
		}
	}

	/**
	 * Revert to "nothing overridden", which restores every listener to the policy it
	 * was started with. Not exposed to operators; used to keep tests honest about
	 * the fallback path.
	 */
	ReloadResult clear() {
		writeLock.lock();

		try {
			long next = active.revision() + 1L;
			active = MotdPolicySnapshot.of(next, Collections.emptyMap());

			return ReloadResult.success(next, Collections.emptyMap());
		} finally {
			writeLock.unlock();
		}
	}

}
