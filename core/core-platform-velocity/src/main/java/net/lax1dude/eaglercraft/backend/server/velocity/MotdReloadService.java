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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import net.lax1dude.eaglercraft.backend.server.api.EnumMotdPassthroughPolicy;

/**
 * Reads the persisted MOTD eligibility settings and publishes them at runtime.
 *
 * <p>
 * Deliberately narrow: it reads listener configuration and takes only the two
 * hot-reloadable properties from it. The listener's target, port, bind address,
 * TLS material and everything else are read but discarded, so a reload cannot
 * quietly apply a change the operator was told would need a restart.
 * </p>
 *
 * <p>
 * The order of operations is the safety property: the whole proposal is built
 * and validated first, and only a fully valid proposal is published. A failed
 * reload therefore leaves the previously active policy exactly as it was, rather
 * than partially applying a policy across some listeners and not others.
 * </p>
 *
 * <p>
 * Threading: {@link #reload} is called from a command thread and may block on the
 * file system, which is fine there. The MOTD path never calls it; it only reads
 * the published snapshot, which is a single volatile read.
 * </p>
 */
final class MotdReloadService {

	/**
	 * The only parts of a listener a reload is allowed to look at.
	 *
	 * Narrower than ConfigDataListener on purpose: a reload that cannot see a
	 * listener's port, TLS material or bind address cannot accidentally apply them,
	 * and the reload logic stays testable without a 40-argument constructor.
	 */
	interface ListenerView {
		String name();

		EnumMotdPassthroughPolicy policy();

		/**
		 * The value as written in the file, or null when it was blank.
		 *
		 * The parsed enum alone cannot express "this was invalid": the loader falls
		 * back to ALL so a typo cannot stop the proxy booting. A reload must reject
		 * that fallback instead of silently widening the policy.
		 */
		String policyRaw();

		Collection<String> allowed();
	}

	/** Supplies freshly loaded listener configuration. Throwing is a failed reload. */
	interface ConfigSource {
		Collection<ListenerView> load() throws IOException;
	}

	/** Reports whether a name is a server Velocity knows about. */
	interface ServerNames {
		boolean exists(String name);
	}

	/** Receives the outcome, so it can be recorded for the control plane to read. */
	interface OutcomeSink {
		void accept(ReloadOutcome outcome);
	}

	/**
	 * Everything an operator (or FoxPanel) needs to tell "persisted" from "active".
	 */
	static final class ReloadOutcome {

		private final boolean ok;

		private final long revision;

		private final String error;

		private final Map<String, MotdPolicySnapshot.ListenerPolicy> listeners;

		private final long at = System.currentTimeMillis();

		private ReloadOutcome(boolean ok, long revision, String error,
				Map<String, MotdPolicySnapshot.ListenerPolicy> listeners) {
			this.ok = ok;
			this.revision = revision;
			this.error = error;
			this.listeners = listeners;
		}

		static ReloadOutcome of(MotdPolicyReloader.ReloadResult result) {
			return new ReloadOutcome(result.ok(), result.revision(), result.error(), result.applied());
		}

		boolean ok() {
			return ok;
		}

		long revision() {
			return revision;
		}

		String error() {
			return error;
		}

		Map<String, MotdPolicySnapshot.ListenerPolicy> listeners() {
			return listeners;
		}

		long at() {
			return at;
		}
	}

	private final MotdPolicyReloader reloader;

	private final ConfigSource config;

	private final ServerNames servers;

	private final OutcomeSink sink;

	MotdReloadService(MotdPolicyReloader reloader, ConfigSource config, ServerNames servers, OutcomeSink sink) {
		this.reloader = reloader;
		this.config = config;
		this.servers = servers;
		this.sink = sink;
	}

	MotdPolicyReloader reloader() {
		return reloader;
	}

	/**
	 * Reload the two hot-reloadable MOTD properties for every listener.
	 *
	 * @param expectedRevision when non-null, reject the reload if a newer revision
	 *                         is already active. This is what stops a slow
	 *                         FoxPanel request from overwriting a policy that an
	 *                         operator has since changed.
	 */
	ReloadOutcome reload(Long expectedRevision) {
		Collection<ListenerView> listeners;

		try {
			listeners = config.load();
		} catch (IOException ex) {
			// Invalid TOML lands here. Nothing is published, so the running policy is
			// untouched and the proxy keeps serving the last known-good values.
			return record(ReloadOutcome.of(MotdPolicyReloader.ReloadResult.failure(reloader.activeRevision(),
					"could not read the configuration: " + describe(ex))));
		} catch (RuntimeException ex) {
			return record(ReloadOutcome.of(MotdPolicyReloader.ReloadResult.failure(reloader.activeRevision(),
					"could not read the configuration: " + describe(ex))));
		}

		// ---- build the whole proposal before touching anything ----
		Map<String, MotdPolicySnapshot.ListenerPolicy> proposed = new LinkedHashMap<>();
		List<String> unknown = new ArrayList<>();
		List<String> invalidPolicies = new ArrayList<>();

		if (listeners != null) {
			for (ListenerView listener : listeners) {
				if (listener == null) {
					continue;
				}

				String name = listener.name();
				String rawPolicy = listener.policyRaw();

				// Reject rather than inherit the loader's boot-time fallback. The previous
				// policy stays active, which is the only safe reading of "this value is
				// wrong": widening it to ALL could suddenly publish MOTDs the operator
				// had deliberately excluded.
				if (rawPolicy != null && !rawPolicy.trim().isEmpty()
						&& EnumMotdPassthroughPolicy.parse(rawPolicy) == null) {
					invalidPolicies.add((name == null ? "<unnamed>" : name) + " -> \"" + rawPolicy.trim() + "\"");
				}

				if (name == null || name.trim().isEmpty()) {
					continue;
				}

				Collection<String> allowed = listener.allowed();

				if (allowed != null) {
					for (String backend : allowed) {
						if (backend == null || backend.trim().isEmpty()) {
							continue;
						}

						if (servers != null && !servers.exists(backend.trim())) {
							unknown.add(name + " -> \"" + backend.trim() + "\"");
						}
					}
				}

				proposed.put(name, MotdPolicySnapshot.ListenerPolicy.of(listener.policy(), allowed));
			}
		}

		if (!invalidPolicies.isEmpty()) {
			return record(ReloadOutcome.of(MotdPolicyReloader.ReloadResult.failure(reloader.activeRevision(),
					"invalid " + "motd_passthrough_backend_policy for " + String.join(", ", invalidPolicies)
							+ "; expected one of: " + EnumMotdPassthroughPolicy.describeValidValues()
							+ ". The previous policy is still active.")));
		}

		if (!unknown.isEmpty()) {
			// A typo must be visible and must not silently disable a listener's MOTD.
			return record(ReloadOutcome.of(MotdPolicyReloader.ReloadResult.failure(reloader.activeRevision(),
					"these allowed backends are not defined in velocity.toml: " + String.join(", ", unknown))));
		}

		if (proposed.isEmpty()) {
			return record(ReloadOutcome.of(MotdPolicyReloader.ReloadResult.failure(reloader.activeRevision(),
					"no listeners with a motd_passthrough_backend_policy were found")));
		}

		return record(ReloadOutcome.of(reloader.publish(proposed, expectedRevision)));
	}

	private ReloadOutcome record(ReloadOutcome outcome) {
		if (sink != null) {
			try {
				sink.accept(outcome);
			} catch (RuntimeException ex) {
				// Recording the outcome must never change the outcome itself.
				System.err.println("[EaglerXServer] could not record MOTD reload status: " + describe(ex));
			}
		}

		return outcome;
	}

	private static String describe(Throwable t) {
		String message = t.getMessage();
		String type = t.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
		return message == null || message.isEmpty() ? type : type + " (" + message + ")";
	}

}
