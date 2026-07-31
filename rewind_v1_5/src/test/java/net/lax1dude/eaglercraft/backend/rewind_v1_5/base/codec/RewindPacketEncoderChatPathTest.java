package net.lax1dude.eaglercraft.backend.rewind_v1_5.base.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class RewindPacketEncoderChatPathTest {

	@BeforeEach
	public void resetCounters() {
		RewindPacketEncoder.resetInvalidChatDropCountForTests();
	}

	@Test
	public void strictPathProducesReadableUsernameText() {
		RewindChatTextNormalizer.NormalizationResult result = RewindPacketEncoder.normalizeChatForEncode(
				"{\"translate\":\"multiplayer.player.joined\",\"with\":[{\"text\":\"Alice\"}]}",
				(json) -> "multiplayer.player.joined", (json) -> "Alice joined the game");
		assertFalse(result.dropped);
		assertEquals("Alice joined the game", result.normalizedText);
		assertFalse(result.normalizedText.contains("someone"));
		assertFalse(result.normalizedText.contains("%s"));
		assertFalse(result.normalizedText.contains("multiplayer.player.joined"));
	}

	@Test
	public void missingUsernameDropsAndIncrementsCounter() {
		RewindChatTextNormalizer.NormalizationResult result = RewindPacketEncoder.normalizeChatForEncode(
				"{\"translate\":\"multiplayer.player.left\",\"with\":[]}",
				(json) -> "multiplayer.player.left", (json) -> "left the game");
		assertTrue(result.dropped);
		assertEquals("missing_username_arg", result.reason);
		assertNull(result.normalizedText);
		assertEquals(1L, RewindPacketEncoder.getInvalidChatDropCount());
	}

	@Test
	public void noOutputContainsForbiddenTokens() {
		RewindChatTextNormalizer.NormalizationResult result = RewindPacketEncoder.normalizeChatForEncode(
				"{\"translate\":\"death.attack.player\",\"with\":[{\"text\":\"Alice\"},{\"text\":\"Bob\"}]}",
				(json) -> "death.attack.player", (json) -> "Alice was slain by Bob");
		assertFalse(result.dropped);
		assertFalse(result.normalizedText.contains("someone"));
		assertFalse(result.normalizedText.contains("%s"));
		assertFalse(result.normalizedText.contains("death.attack.player"));
	}

	@Test
	public void chatTypeTextDoesNotLeakRawKey() {
		RewindChatTextNormalizer.NormalizationResult result = RewindPacketEncoder.normalizeChatForEncode(
				"{\"translate\":\"chat.type.text\",\"with\":[{\"text\":\"Alice\"},{\"text\":\"hello\"}]}",
				(json) -> "chat.type.text", (json) -> "<Alice> hello");
		assertFalse(result.dropped);
		assertEquals("<Alice> hello", result.normalizedText);
		assertFalse(result.normalizedText.contains("chat.type.text"));
		assertFalse(result.normalizedText.contains("%s"));
	}

}