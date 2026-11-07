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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.lax1dude.eaglercraft.backend.server.base.compat.CompatFailureCategory;

/**
 * What the player is actually shown for each failure category.
 */
class VelocityCompatFailureMessagesTest {

	private static final String SERVER = "lobby";

	private static String plain(Component component) {
		return PlainTextComponentSerializer.plainText().serialize(component);
	}

	// ------------------------------------------------------------------
	// the backend's own message must survive untouched
	// ------------------------------------------------------------------

	@Test
	@DisplayName("a backend rejection is shown verbatim, not flattened")
	void backendRejectionIsPreservedVerbatim() {
		Component backend = Component.text("You are not whitelisted on this server!", NamedTextColor.RED);

		Component result = VelocityCompatFailureMessages.componentFor(
				CompatFailureCategory.BACKEND_REJECTED, SERVER, backend);

		// Identity, not equality: no re-wrapping, no loss of styling.
		assertSame(backend, result);
		assertEquals("You are not whitelisted on this server!", plain(result));
	}

	@Test
	@DisplayName("a translatable backend reason keeps its key so the client localises it")
	void translatableBackendReasonKeepsItsKey() {
		TranslatableComponent backend = Component.translatable("multiplayer.disconnect.not_whitelisted");

		Component result = VelocityCompatFailureMessages.componentFor(
				CompatFailureCategory.BACKEND_REJECTED, SERVER, backend);

		assertSame(backend, result);
		TranslatableComponent translatable = assertInstanceOf(TranslatableComponent.class, result);
		assertEquals("multiplayer.disconnect.not_whitelisted", translatable.key());
	}

	@Test
	@DisplayName("a styled backend reason keeps its colour")
	void backendReasonKeepsItsColour() {
		Component backend = Component.text("Server is full", NamedTextColor.GOLD);

		Component result = VelocityCompatFailureMessages.componentFor(
				CompatFailureCategory.BACKEND_REJECTED, SERVER, backend);

		assertEquals(NamedTextColor.GOLD, result.color());
	}

	// ------------------------------------------------------------------
	// truthful wording for the failures the panel must explain
	// ------------------------------------------------------------------

	@Test
	@DisplayName("a DNS failure names resolution, not protocol compatibility")
	void dnsFailureIsActionable() {
		String message = plain(VelocityCompatFailureMessages.componentFor(
				CompatFailureCategory.DNS_RESOLUTION_FAILURE, SERVER, null));

		assertTrue(message.contains(SERVER), message);
		assertTrue(message.toLowerCase().contains("address could not be resolved"), message);
		assertFalse(message.toLowerCase().contains("protocol"), message);
		assertFalse(message.toLowerCase().contains("incompatible"), message);
	}

	@Test
	@DisplayName("a DNS failure never leaks the backend host or address")
	void dnsFailureDoesNotLeakAddress() {
		String message = plain(VelocityCompatFailureMessages.componentFor(
				CompatFailureCategory.DNS_RESOLUTION_FAILURE, SERVER, null));

		assertFalse(message.contains("09d1dc06-027e-460f-8ac6-5f24a74cafea"), message);
		assertFalse(message.matches("(?s).*\\b\\d{1,3}(\\.\\d{1,3}){3}\\b.*"), message);
		assertFalse(message.matches("(?s).*:\\d{2,5}\\b.*"), message);
	}

	@Test
	@DisplayName("a refused connection says the server is probably offline")
	void refusedConnectionMentionsOffline() {
		String message = plain(VelocityCompatFailureMessages.componentFor(
				CompatFailureCategory.CONNECTION_REFUSED, SERVER, null));

		assertTrue(message.contains(SERVER), message);
		assertTrue(message.toLowerCase().contains("refused"), message);
		assertFalse(message.toLowerCase().contains("protocol"), message);
	}

	@Test
	@DisplayName("an unreachable network is described as not reachable")
	void unreachableMentionsReachability() {
		String message = plain(VelocityCompatFailureMessages.componentFor(
				CompatFailureCategory.NETWORK_UNREACHABLE, SERVER, null));

		assertTrue(message.toLowerCase().contains("not reachable"), message);
		assertFalse(message.toLowerCase().contains("protocol"), message);
	}

	@Test
	@DisplayName("a timeout is described as not responding")
	void timeoutMentionsNoResponse() {
		String message = plain(VelocityCompatFailureMessages.componentFor(
				CompatFailureCategory.TIMEOUT, SERVER, null));

		assertTrue(message.toLowerCase().contains("did not respond"), message);
		assertFalse(message.toLowerCase().contains("protocol"), message);
	}

	@Test
	@DisplayName("only a genuine mismatch may claim version incompatibility")
	void protocolMismatchMentionsCompatibility() {
		String message = plain(VelocityCompatFailureMessages.componentFor(
				CompatFailureCategory.PROTOCOL_MISMATCH, SERVER, null));

		assertTrue(message.toLowerCase().contains("not compatible"), message);
		assertTrue(message.contains(SERVER), message);
	}

	@Test
	@DisplayName("an unknown failure stays neutral and never blames the protocol")
	void unknownFailureIsNeutral() {
		String message = plain(VelocityCompatFailureMessages.componentFor(
				CompatFailureCategory.UNKNOWN, SERVER, null));

		assertTrue(message.contains(SERVER), message);
		assertFalse(message.toLowerCase().contains("protocol"), message);
		assertFalse(message.toLowerCase().contains("incompatible"), message);
		assertFalse(message.toLowerCase().contains("version"), message);
	}

	@Test
	@DisplayName("a rejection with no message still tells the player they were refused")
	void rejectionWithoutMessageIsStillTruthful() {
		String message = plain(VelocityCompatFailureMessages.componentFor(
				CompatFailureCategory.BACKEND_REJECTED, SERVER, null));

		assertTrue(message.contains(SERVER), message);
		assertTrue(message.toLowerCase().contains("did not let you join"), message);
		assertFalse(message.toLowerCase().contains("protocol"), message);
	}

	@Test
	@DisplayName("every synthesised message is red and mentions the backend alias")
	void synthesisedMessagesAreConsistent() {
		for (CompatFailureCategory category : CompatFailureCategory.values()) {
			if (category == CompatFailureCategory.BACKEND_REJECTED) {
				continue;
			}

			Component component = VelocityCompatFailureMessages.componentFor(category, SERVER, null);

			assertEquals(NamedTextColor.RED, component.color(), "colour for " + category);
			assertTrue(plain(component).contains(SERVER), "server name for " + category);
		}
	}

}
