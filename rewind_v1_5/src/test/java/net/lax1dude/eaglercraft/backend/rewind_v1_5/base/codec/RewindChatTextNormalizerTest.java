package net.lax1dude.eaglercraft.backend.rewind_v1_5.base.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class RewindChatTextNormalizerTest {

	@Test
	public void keyWithValidUsernameArgRendersExactUsername() {
		RewindChatTextNormalizer.NormalizationResult result = RewindChatTextNormalizer.normalizeChatStrict(
				"{\"translate\":\"multiplayer.player.joined\",\"with\":[{\"text\":\"Alice\"}]}",
				"multiplayer.player.joined", "Alice joined the game");
		assertFalse(result.dropped);
		assertEquals("Alice joined the game", result.normalizedText);
		assertTrue(result.normalizedText.contains("Alice"));
	}

	@Test
	public void keyWithMultipleArgsPlacesUsernamesCorrectly() {
		RewindChatTextNormalizer.NormalizationResult result = RewindChatTextNormalizer.normalizeChatStrict(
				"{\"translate\":\"death.attack.player\",\"with\":[{\"text\":\"Alice\"},{\"text\":\"Bob\"}]}",
				"death.attack.player", "Alice was slain by Bob");
		assertFalse(result.dropped);
		assertEquals("Alice was slain by Bob", result.normalizedText);
	}

	@Test
	public void chatTypeTextRendersSpeakerAndMessage() {
		RewindChatTextNormalizer.NormalizationResult result = RewindChatTextNormalizer.normalizeChatStrict(
				"{\"translate\":\"chat.type.text\",\"with\":[{\"text\":\"Alice\"},{\"text\":\"hello world\"}]}",
				"chat.type.text", "<Alice> hello world");
		assertFalse(result.dropped);
		assertEquals("<Alice> hello world", result.normalizedText);
		assertFalse(result.normalizedText.contains("chat.type.text"));
		assertFalse(result.normalizedText.contains("%s"));
	}

	@Test
	public void missingUsernameArgDropsMessage() {
		RewindChatTextNormalizer.NormalizationResult result = RewindChatTextNormalizer.normalizeChatStrict(
				"{\"translate\":\"multiplayer.player.joined\",\"with\":[]}", "multiplayer.player.joined",
				"joined the game");
		assertTrue(result.dropped);
		assertEquals("missing_username_arg", result.reason);
	}

	@Test
	public void corruptBinaryPayloadDropsMessage() {
		RewindChatTextNormalizer.NormalizationResult result = RewindChatTextNormalizer.normalizeChatStrict(
				"{\"translate\":\"multiplayer.player.joined\",\"with\":[{\"text\":\"Al\u0000ice\"}]}",
				"multiplayer.player.joined", "Al\u0000ice joined the game");
		assertTrue(result.dropped);
		assertEquals("binary_payload", result.reason);
	}

}