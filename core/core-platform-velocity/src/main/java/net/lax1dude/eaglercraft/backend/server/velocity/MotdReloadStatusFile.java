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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Records the outcome of a reload where an authenticated control plane can read
 * it.
 *
 * <p>
 * Why a file: FoxPanel can already write to a server's filesystem and already
 * sends console commands over the authenticated Wings channel, but it cannot
 * observe a command's return value. Reading a small status document afterwards
 * turns "we sent a command" into "the runtime reported revision N active", which
 * is the whole difference between claiming a change applied and knowing it did.
 * </p>
 *
 * <p>
 * The document is written to a temporary file first and then moved into place, so
 * a reader can never observe a half-written file. The payload carries no player
 * data, no addresses and no tokens: a revision, a boolean, an error string and
 * the effective policy per listener.
 * </p>
 */
final class MotdReloadStatusFile {

	/** Relative to the plugin's data directory. */
	static final String FILE_NAME = "motd-reload-status.json";

	private final Path path;

	MotdReloadStatusFile(Path dataDirectory) {
		this.path = dataDirectory.resolve(FILE_NAME);
	}

	Path path() {
		return path;
	}

	/**
	 * Record one outcome. Never throws: a status file that cannot be written must
	 * not turn a successful reload into a failure, so the caller logs and moves on.
	 */
	void write(MotdReloadService.ReloadOutcome outcome) throws IOException {
		JsonObject root = new JsonObject();

		root.addProperty("revision", outcome.revision());
		root.addProperty("ok", outcome.ok());
		root.addProperty("at", Instant.ofEpochMilli(outcome.at()).toString());

		if (outcome.error() == null) {
			root.add("error", com.google.gson.JsonNull.INSTANCE);
		} else {
			root.addProperty("error", outcome.error());
		}

		JsonObject listeners = new JsonObject();

		for (Map.Entry<String, MotdPolicySnapshot.ListenerPolicy> entry : outcome.listeners().entrySet()) {
			JsonObject one = new JsonObject();
			one.addProperty("policy", entry.getValue().policy().configName());

			JsonArray allowed = new JsonArray();

			for (String name : entry.getValue().allowed()) {
				allowed.add(name);
			}

			one.add("allowed", allowed);
			listeners.add(entry.getKey(), one);
		}

		root.add("listeners", listeners);

		String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);

		Path parent = path.getParent();

		if (parent != null) {
			Files.createDirectories(parent);
		}

		// Write-then-move so a reader never sees a partial document.
		Path temporary = path.resolveSibling(FILE_NAME + ".tmp");
		Files.write(temporary, json.getBytes(StandardCharsets.UTF_8));
		Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
	}

}
