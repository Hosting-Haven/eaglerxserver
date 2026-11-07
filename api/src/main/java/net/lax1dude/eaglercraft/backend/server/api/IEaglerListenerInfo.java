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

import java.net.SocketAddress;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Collection;
import java.util.Collections;

import net.lax1dude.eaglercraft.backend.server.api.attribute.IAttributeHolder;

public interface IEaglerListenerInfo extends IAttributeHolder {

	@Nonnull
	String getName();

	@Nonnull
	SocketAddress getAddress();

	boolean isDualStack();

	boolean isTLSEnabled();

	boolean isTLSRequired();

	boolean isTLSManagedByPlugin();

	@Nonnull
	ITLSManager getTLSManager() throws IllegalStateException;

	@Nullable
	byte[] getServerIcon();

	void setServerIcon(@Nullable byte[] pixels);

	@Nonnull
	List<String> getServerMOTD();

	void setServerMOTD(@Nullable List<String> motd);

	boolean isForwardIP();

	/**
	 * Where this listener should read its Java MOTD from, or null to use the
	 * proxy-wide default.
	 *
	 * Accepts the same values as the MOTD passthrough setting: {@code host:port},
	 * the name of a backend server, or an opt-out word such as {@code off} to keep
	 * this listener's configured MOTD. Defaults to null so that implementations
	 * which predate this setting are unaffected.
	 */
	@Nullable
	default String getMotdPassthroughTarget() {
		return null;
	}

	/**
	 * Which backends may supply the MOTD when {@link #getMotdPassthroughTarget()}
	 * is {@code auto}.
	 *
	 * <p>
	 * Only constrains where MOTD information is read from. Player routing is decided
	 * solely by Velocity's {@code try} list and {@code [forced-hosts]}.
	 * </p>
	 *
	 * Defaults to {@link EnumMotdPassthroughPolicy#ALL} so that implementations
	 * which predate this setting keep the previous behaviour.
	 */
	@Nonnull
	default EnumMotdPassthroughPolicy getMotdPassthroughBackendPolicy() {
		return EnumMotdPassthroughPolicy.ALL;
	}

	/**
	 * The backend names permitted to supply the MOTD under
	 * {@link EnumMotdPassthroughPolicy#SELECTED}, empty for every other policy.
	 *
	 * Names refer to servers registered in velocity.toml. Defaults to an empty list
	 * so that implementations which predate this setting are unaffected.
	 */
	@Nonnull
	default Collection<String> getMotdPassthroughAllowedBackends() {
		return Collections.emptyList();
	}

}
