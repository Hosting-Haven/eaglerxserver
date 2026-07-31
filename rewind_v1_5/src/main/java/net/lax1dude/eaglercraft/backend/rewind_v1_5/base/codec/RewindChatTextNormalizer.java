/*
 * Copyright (c) 2026. All Rights Reserved.
 */

package net.lax1dude.eaglercraft.backend.rewind_v1_5.base.codec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RewindChatTextNormalizer {

	private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("%(?:\\d+\\$)?s");
	private static final Pattern MULTI_WHITESPACE_PATTERN = Pattern.compile("\\s+");
	private static final Pattern TRANSLATE_KEY_PATTERN = Pattern.compile("\\\"translate\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
	private static final Pattern TEXT_FIELD_PATTERN = Pattern.compile("\\\"text\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
	private static final Pattern STRING_LITERAL_PATTERN = Pattern.compile("\\\"((?:\\\\.|[^\\\"])*)\\\"");
	private static final Pattern BARE_FORMAT_PATTERN = Pattern.compile("\\{\\d+\\}");
	private static final Pattern BINARY_JUNK_PATTERN = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
	private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,16}");

	private static final Map<String, String> TRANSLATION_KEY_MAP = new LinkedHashMap<>();
	private static final Set<String> FORBIDDEN_LITERAL_TOKENS = new HashSet<>();
	private static final Set<String> JSON_FIELD_NAMES = new HashSet<>();

	static {
		TRANSLATION_KEY_MAP.put("multiplayer.player.joined", "%s joined the game");
		TRANSLATION_KEY_MAP.put("multiplayer.player.left", "%s left the game");
		TRANSLATION_KEY_MAP.put("chat.type.text", "<%s> %s");
		TRANSLATION_KEY_MAP.put("death.attack.player", "%s was slain by %s");
		TRANSLATION_KEY_MAP.put("disconnect.timeout", "Timed out");
		FORBIDDEN_LITERAL_TOKENS.add("someone");
		FORBIDDEN_LITERAL_TOKENS.add("%s");
		FORBIDDEN_LITERAL_TOKENS.add("%1$s");
		FORBIDDEN_LITERAL_TOKENS.add("{0}");
		FORBIDDEN_LITERAL_TOKENS.add("multiplayer.player.joined");
		FORBIDDEN_LITERAL_TOKENS.add("multiplayer.player.left");
		FORBIDDEN_LITERAL_TOKENS.add("chat.type.text");
		FORBIDDEN_LITERAL_TOKENS.add("death.attack.player");
		JSON_FIELD_NAMES.add("text");
		JSON_FIELD_NAMES.add("translate");
		JSON_FIELD_NAMES.add("with");
		JSON_FIELD_NAMES.add("score");
		JSON_FIELD_NAMES.add("name");
		JSON_FIELD_NAMES.add("extra");
		JSON_FIELD_NAMES.add("color");
		JSON_FIELD_NAMES.add("bold");
		JSON_FIELD_NAMES.add("italic");
		JSON_FIELD_NAMES.add("underlined");
		JSON_FIELD_NAMES.add("strikethrough");
		JSON_FIELD_NAMES.add("obfuscated");
	}

	static final class NormalizationResult {
		final boolean dropped;
		final String normalizedText;
		final String translationKey;
		final String reason;

		private NormalizationResult(boolean dropped, String normalizedText, String translationKey, String reason) {
			this.dropped = dropped;
			this.normalizedText = normalizedText;
			this.translationKey = translationKey;
			this.reason = reason;
		}

		static NormalizationResult emit(String normalizedText, String translationKey) {
			return new NormalizationResult(false, normalizedText, translationKey, null);
		}

		static NormalizationResult drop(String translationKey, String reason) {
			return new NormalizationResult(true, null, translationKey, reason);
		}
	}

	private RewindChatTextNormalizer() {
	}

	static NormalizationResult normalizeChatStrict(String raw, String legacy, String plain) {
		String translationKey = extractTranslationKey(raw);
		String safeLegacy = sanitizeControlChars(legacy);
		String safePlain = sanitizeControlChars(plain);
		if (containsBinaryJunk(raw) || containsBinaryJunk(legacy) || containsBinaryJunk(plain)) {
			return NormalizationResult.drop(translationKey, "binary_payload");
		}
		if (isPlayerAttributed(translationKey)) {
			List<String> usernames = extractUsernames(raw);
			int requiredUsernames = getRequiredUsernameCount(translationKey);
			if (usernames.size() < requiredUsernames) {
				return NormalizationResult.drop(translationKey, "missing_username_arg");
			}
			String rendered = renderPlayerAttributed(translationKey, usernames, safeLegacy, safePlain);
			if (rendered == null || rendered.isEmpty()) {
				return NormalizationResult.drop(translationKey, "render_failed");
			}
			if (containsForbiddenTokens(rendered) || hasUnresolvedTokens(rendered)) {
				return NormalizationResult.drop(translationKey, "unresolved_tokens");
			}
			if (!containsRequiredUsernames(rendered, usernames, requiredUsernames)) {
				return NormalizationResult.drop(translationKey, "missing_rendered_username");
			}
			return NormalizationResult.emit(rendered, translationKey);
		}
		String candidate = safeLegacy;
		if (candidate.isEmpty() || hasUnresolvedTokens(candidate)) {
			candidate = safePlain;
		}
		if (candidate.isEmpty() || hasUnresolvedTokens(candidate) || containsForbiddenTokens(candidate)) {
			return NormalizationResult.drop(translationKey, "invalid_non_player_message");
		}
		return NormalizationResult.emit(candidate, translationKey);
	}

	static String normalize(String input) {
		return sanitizeControlChars(input);
	}

	static boolean hasUnresolvedTokens(String input) {
		if (input == null || input.isEmpty()) {
			return false;
		}
		if (PLACEHOLDER_PATTERN.matcher(input).find()) {
			return true;
		}
		if (BARE_FORMAT_PATTERN.matcher(input).find()) {
			return true;
		}
		for (String key : TRANSLATION_KEY_MAP.keySet()) {
			if (input.contains(key)) {
				return true;
			}
		}
		return false;
	}

	static String previewForLog(String text, int maxLength) {
		String sanitized = sanitizeControlChars(text);
		if (sanitized.length() <= maxLength) {
			return sanitized;
		}
		return sanitized.substring(0, maxLength) + "...";
	}

	private static boolean isPlayerAttributed(String translationKey) {
		if (translationKey == null) {
			return false;
		}
		return "multiplayer.player.joined".equals(translationKey)
				|| "multiplayer.player.left".equals(translationKey)
				|| "chat.type.text".equals(translationKey)
				|| translationKey.startsWith("death.attack.");
	}

	private static String extractTranslationKey(String raw) {
		if (raw == null) {
			return "none";
		}
		Matcher matcher = TRANSLATE_KEY_PATTERN.matcher(raw);
		if (matcher.find()) {
			return matcher.group(1);
		}
		return "none";
	}

	private static int getRequiredUsernameCount(String translationKey) {
		if ("death.attack.player".equals(translationKey)) {
			return 2;
		}
		if ("chat.type.text".equals(translationKey)) {
			return 1;
		}
		if (translationKey != null && translationKey.startsWith("death.attack.")) {
			return 1;
		}
		if ("multiplayer.player.joined".equals(translationKey) || "multiplayer.player.left".equals(translationKey)) {
			return 1;
		}
		return 0;
	}

	private static List<String> extractUsernames(String raw) {
		if (raw == null || raw.isEmpty()) {
			return Collections.emptyList();
		}
		String withArray = extractWithArray(raw);
		if (withArray == null) {
			return Collections.emptyList();
		}
		ArrayList<String> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		Matcher textMatcher = TEXT_FIELD_PATTERN.matcher(withArray);
		while (textMatcher.find()) {
			String val = sanitizeControlChars(unescapeJson(textMatcher.group(1)));
			if (isUsername(val) && seen.add(val)) {
				out.add(val);
			}
		}
		Matcher strMatcher = STRING_LITERAL_PATTERN.matcher(withArray);
		while (strMatcher.find()) {
			String val = sanitizeControlChars(unescapeJson(strMatcher.group(1)));
			if (isUsername(val) && !JSON_FIELD_NAMES.contains(val) && seen.add(val)) {
				out.add(val);
			}
		}
		return out;
	}

	private static String extractWithArray(String raw) {
		int withIdx = raw.indexOf("\"with\"");
		if (withIdx < 0) {
			return null;
		}
		int openIdx = raw.indexOf('[', withIdx);
		if (openIdx < 0) {
			return null;
		}
		int depth = 0;
		for (int i = openIdx; i < raw.length(); ++i) {
			char chr = raw.charAt(i);
			if (chr == '[') {
				++depth;
			} else if (chr == ']') {
				--depth;
				if (depth == 0) {
					return raw.substring(openIdx, i + 1);
				}
			}
		}
		return null;
	}

	private static String renderPlayerAttributed(String translationKey, List<String> usernames, String legacy,
			String plain) {
		String template = TRANSLATION_KEY_MAP.get(translationKey);
		if (template != null) {
			String rendered = template;
			for (int i = 0; i < usernames.size(); ++i) {
				rendered = rendered.replaceFirst("%(?:\\\\d+\\\\$)?s", Matcher.quoteReplacement(usernames.get(i)));
			}
			rendered = sanitizeControlChars(rendered);
			if (!hasUnresolvedTokens(rendered)) {
				return rendered;
			}
		}
		String candidate = plain;
		if (candidate == null || candidate.isEmpty()) {
			candidate = legacy;
		}
		candidate = sanitizeControlChars(candidate);
		if (candidate.isEmpty() || hasUnresolvedTokens(candidate)) {
			return null;
		}
		return candidate;
	}

	private static boolean containsRequiredUsernames(String rendered, List<String> usernames, int requiredCount) {
		if (requiredCount <= 0) {
			return true;
		}
		for (int i = 0; i < requiredCount && i < usernames.size(); ++i) {
			if (!rendered.contains(usernames.get(i))) {
				return false;
			}
		}
		return true;
	}

	private static boolean isUsername(String val) {
		return val != null && USERNAME_PATTERN.matcher(val).matches();
	}

	private static boolean containsForbiddenTokens(String rendered) {
		if (rendered == null) {
			return false;
		}
		String lower = rendered.toLowerCase();
		for (String token : FORBIDDEN_LITERAL_TOKENS) {
			if (lower.contains(token.toLowerCase())) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsBinaryJunk(String input) {
		if (input == null || input.isEmpty()) {
			return false;
		}
		return BINARY_JUNK_PATTERN.matcher(input).find();
	}

	private static String unescapeJson(String in) {
		return in.replace("\\\\\"", "\"").replace("\\\\\\\\", "\\");
	}

	private static String sanitizeControlChars(String input) {
		if (input == null || input.isEmpty()) {
			return "";
		}
		StringBuilder out = new StringBuilder(input.length());
		for (int i = 0; i < input.length(); ++i) {
			char chr = input.charAt(i);
			if (chr == '\n' || chr == '\r' || chr == '\t') {
				out.append(' ');
				continue;
			}
			if (chr == '\u00A7') {
				out.append(chr);
				continue;
			}
			if (Character.isHighSurrogate(chr)) {
				if (i + 1 < input.length()) {
					char nxt = input.charAt(i + 1);
					if (Character.isLowSurrogate(nxt)) {
						out.append(chr);
						out.append(nxt);
						++i;
					}
				}
				continue;
			}
			if (Character.isLowSurrogate(chr) || Character.isISOControl(chr)) {
				continue;
			}
			out.append(chr);
		}
		return MULTI_WHITESPACE_PATTERN.matcher(out.toString()).replaceAll(" ").trim();
	}

}