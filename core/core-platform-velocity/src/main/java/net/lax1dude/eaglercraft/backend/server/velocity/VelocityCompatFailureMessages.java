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

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.lax1dude.eaglercraft.backend.server.base.compat.CompatFailureCategory;

/**
 * Turns a classified backend failure into the message the player actually sees.
 * 
 * Two rules are enforced here:
 * 
 * <ul>
 * <li>When the backend authored a message, that exact {@link Component} is
 * returned untouched, so its colours, formatting and translatable keys survive
 * to the client. Toasting it into a plain string is what made useful messages
 * unreadable before.</li>
 * <li>When the panel has to explain the failure itself, the wording names the
 * real problem. A protocol incompatibility is only ever claimed for
 * {@link CompatFailureCategory#PROTOCOL_MISMATCH}.</li>
 * </ul>
 * 
 * Synthesised messages are plain (non-translatable) text on purpose: a custom
 * translation key the client does not know would render as the raw key.
 */
final class VelocityCompatFailureMessages {

	private VelocityCompatFailureMessages() {
	}

	/**
	 * @param category      the classified failure
	 * @param serverName    the backend alias, never a private host or address
	 * @param backendReason the reason supplied by the backend, if any
	 */
	static Component componentFor(CompatFailureCategory category, String serverName, Component backendReason) {
		// Anything the backend said is the best possible answer, so it wins. The
		// classifier only reaches BACKEND_REJECTED when that message exists.
		if (backendReason != null && category == CompatFailureCategory.BACKEND_REJECTED) {
			return backendReason;
		}

		switch (category) {
			case PROTOCOL_MISMATCH:
				return Component.text("Unable to connect to " + serverName
						+ ": this client version is not compatible with the server.", NamedTextColor.RED);
			case DNS_RESOLUTION_FAILURE:
				return Component.text("Unable to reach " + serverName
						+ ": the server's address could not be resolved. Please try again later, or ask an administrator to check the backend configuration.",
						NamedTextColor.RED);
			case CONNECTION_REFUSED:
				return Component.text("Unable to reach " + serverName
						+ ": the server refused the connection, so it is probably offline.", NamedTextColor.RED);
			case NETWORK_UNREACHABLE:
				return Component.text("Unable to reach " + serverName
						+ ": the server is not reachable from the proxy right now. Please try again shortly.",
						NamedTextColor.RED);
			case TIMEOUT:
				return Component.text("Unable to reach " + serverName
						+ ": the server did not respond in time. Please try again shortly.", NamedTextColor.RED);
			case ALREADY_CONNECTED:
				return Component.text("You are already connected to " + serverName + ".", NamedTextColor.RED);
			case CONNECTION_IN_PROGRESS:
				return Component.text("A connection to " + serverName + " is already in progress.",
						NamedTextColor.RED);
			case CONNECTION_CANCELLED:
				return Component.text("The connection to " + serverName + " was cancelled.", NamedTextColor.RED);
			case BACKEND_REJECTED:
				// The backend rejected the player but supplied no usable message.
				return Component.text("The server " + serverName + " did not let you join.", NamedTextColor.RED);
			default:
				// Deliberately neutral: an unidentified failure is not evidence that the
				// protocol version was wrong.
				return Component.text("Unable to connect to " + serverName
						+ ". Please try again, or contact an administrator if this keeps happening.",
						NamedTextColor.RED);
		}
	}

}
