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

package net.lax1dude.eaglercraft.backend.server.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Which backends may supply the MOTD when {@code motd_passthrough_target} is
 * {@code auto}.
 *
 * <p>
 * This decides only where MOTD information is read from. It never influences
 * which backend a player is actually routed to: Velocity's {@code try} list and
 * {@code [forced-hosts]} remain the only routing authority.
 * </p>
 */
public enum EnumMotdPassthroughPolicy {

	/** Every backend the automatic routing could select may supply the MOTD. */
	ALL("all"),

	/** Only the backends named in the allowed-backends list may supply it. */
	SELECTED("selected"),

	/** No backend may supply it; the listener's own server_motd always applies. */
	NONE("none");

	private static final EnumMotdPassthroughPolicy[] VALUES = values();

	private final String configName;

	private EnumMotdPassthroughPolicy(String configName) {
		this.configName = configName;
	}

	/**
	 * The token written in listeners.toml for this policy.
	 */
	@Nonnull
	public String configName() {
		return configName;
	}

	/**
	 * Parse a listeners.toml value, or null when it names no known policy.
	 *
	 * Null rather than an exception so a caller can report the bad value and fall
	 * back, which is what the config loader does for every other enum-like setting.
	 */
	@Nullable
	public static EnumMotdPassthroughPolicy parse(@Nullable String value) {
		if (value == null) {
			return null;
		}

		String token = value.trim();
		if (token.isEmpty()) {
			return null;
		}

		for (EnumMotdPassthroughPolicy policy : VALUES) {
			if (policy.configName.equalsIgnoreCase(token)) {
				return policy;
			}
		}

		return null;
	}

	/**
	 * Every config token, for building the "expected one of" diagnostic.
	 */
	@Nonnull
	public static String describeValidValues() {
		StringBuilder builder = new StringBuilder();
		for (EnumMotdPassthroughPolicy policy : VALUES) {
			if (builder.length() > 0) {
				builder.append(", ");
			}
			builder.append(policy.configName);
		}
		return builder.toString();
	}

}
