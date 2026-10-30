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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.lax1dude.eaglercraft.backend.server.base.ServerIconLoader;

/**
 * A minimal Minecraft server list ping client.
 *
 * This talks the ordinary Java "status" protocol, which is the only way to
 * obtain exactly what a Java client would see: the MOTD as Velocity currently
 * renders it (including any plugin that rewrites pings), the favicon, and the
 * live player counts. Reading velocity.toml instead would only produce the
 * configured value and would miss all of that.
 *
 * Deliberately hand-rolled rather than pulling in a protocol library: the whole
 * exchange is two small packets, and this has to run on the proxy's own
 * classpath without adding a dependency.
 */
final class MotdPing {

        private static final int STATUS_PROTOCOL = 1;
        private static final int MAX_PACKET_SIZE = 1 << 21;

        private MotdPing() {
        }

        /**
         * Ping {@code address} and translate the response into MOTD form.
         *
         * @throws IOException if the server cannot be reached or the response is
         *                     malformed. Callers treat that as "keep the existing
         *                     MOTD", never as a fatal error.
         */
        static MotdSnapshot ping(InetSocketAddress address, int timeoutMillis) throws IOException {
                String host = address.getHostString();
                int port = address.getPort();
                if (host == null || host.isEmpty()) {
                        host = "127.0.0.1";
                }

                String json;
                try (Socket socket = new Socket()) {
                        socket.setTcpNoDelay(true);
                        socket.connect(new InetSocketAddress(host, port), timeoutMillis);
                        socket.setSoTimeout(timeoutMillis);

                        OutputStream out = socket.getOutputStream();
                        try (ByteArrayOutputStream handshake = new ByteArrayOutputStream()) {
                                writeVarInt(handshake, 0x00);
                                writeVarInt(handshake, -1); // protocol version: -1 = "just asking"
                                writeString(handshake, host);
                                writeUnsignedShort(handshake, port);
                                writeVarInt(handshake, STATUS_PROTOCOL);
                                writeVarIntPacket(out, handshake.toByteArray());
                        }
                        writeVarIntPacket(out, new byte[] { 0x00 });
                        out.flush();

                        InputStream in = socket.getInputStream();
                        int length = readVarInt(in);
                        if (length <= 0 || length > MAX_PACKET_SIZE) {
                                throw new IOException("unexpected status packet length " + length);
                        }
                        byte[] payload = readFully(in, length);
                        int[] offset = new int[] { 0 };
                        int packetId = readVarInt(payload, offset);
                        if (packetId != 0x00) {
                                throw new IOException("unexpected status packet id " + packetId);
                        }
                        json = readString(payload, offset);
                }

                return fromJson(json);
        }

        /**
         * Translate a status JSON document into a snapshot. Separate from the
         * socket work so the parsing rules can be tested without a server.
         */
        static MotdSnapshot fromJson(String json) throws IOException {
                JsonElement rootElement = JsonParser.parseString(json);
                if (!rootElement.isJsonObject()) {
                        throw new IOException("status response was not a JSON object");
                }
                JsonObject root = rootElement.getAsJsonObject();

                List<String> motd = motdLines(root.get("description"));

                int online = 0;
                int max = 0;
                List<String> sample = Collections.emptyList();

                JsonElement playersElement = root.get("players");
                if (playersElement != null && playersElement.isJsonObject()) {
                        JsonObject players = playersElement.getAsJsonObject();
                        online = intOrZero(players.get("online"));
                        max = intOrZero(players.get("max"));
                        sample = sampleNames(players.get("sample"));
                }

                byte[] icon = faviconBytes(root.get("favicon"));

                return new MotdSnapshot(motd, icon, online, max, sample);
        }

        /**
         * The MOTD as at most two lines of section-sign formatted text.
         *
         * Java sends either a plain string or a chat component (object or array).
         * Both are normalised through Adventure so colour codes survive, because
         * an Eaglercraft client renders the legacy form.
         */
        private static List<String> motdLines(JsonElement description) {
                if (description == null || description.isJsonNull()) {
                        return Collections.emptyList();
                }

                String legacy;
                try {
                        Component component = GsonComponentSerializer.gson().deserialize(description.toString());
                        legacy = LegacyComponentSerializer.legacySection().serialize(component);
                } catch (RuntimeException ex) {
                        // A server can send something Adventure refuses to parse; the raw
                        // text is still better than dropping the MOTD entirely.
                        legacy = description.isJsonPrimitive() ? description.getAsString() : "";
                }

                if (legacy.isEmpty()) {
                        return Collections.emptyList();
                }

                List<String> lines = new ArrayList<>(2);
                for (String line : legacy.split("\n", -1)) {
                        if (lines.size() == 2) {
                                break;
                        }
                        lines.add(stripTrailingFormatting(line));
                }

                return lines;
        }

        /**
         * A trailing colour code with no text after it renders as nothing, but it
         * does make the line non-empty, so trim it to keep the two-line check honest.
         */
        private static String stripTrailingFormatting(String line) {
                int end = line.length();
                while (end >= 2 && line.charAt(end - 2) == '\u00a7') {
                        end -= 2;
                }
                return end == line.length() ? line : line.substring(0, end);
        }

        private static List<String> sampleNames(JsonElement sample) {
                if (sample == null || !sample.isJsonArray()) {
                        return Collections.emptyList();
                }

                JsonArray array = sample.getAsJsonArray();
                List<String> names = new ArrayList<>(array.size());
                for (JsonElement entry : array) {
                        if (entry != null && entry.isJsonObject()) {
                                JsonElement name = entry.getAsJsonObject().get("name");
                                if (name != null && name.isJsonPrimitive()) {
                                        names.add(name.getAsString());
                                }
                        }
                }

                return names;
        }

        /**
         * The favicon as raw RGBA8 pixels, which is what the MOTD response carries.
         */
        private static byte[] faviconBytes(JsonElement favicon) {
                if (favicon == null || !favicon.isJsonPrimitive()) {
                        return null;
                }

                String data = favicon.getAsString();
                int comma = data.indexOf(',');
                if (comma < 0) {
                        return null;
                }

                try {
                        byte[] png = Base64.getDecoder().decode(data.substring(comma + 1).trim());
                        if (png.length == 0) {
                                return null;
                        }
                        return ServerIconLoader.loadServerIcon(new ByteArrayInputStream(png));
                } catch (RuntimeException | IOException ex) {
                        // A broken favicon must not cost the whole MOTD.
                        return null;
                }
        }

        private static int intOrZero(JsonElement element) {
                if (element == null || !element.isJsonPrimitive()) {
                        return 0;
                }
                try {
                        return element.getAsInt();
                } catch (RuntimeException ex) {
                        return 0;
                }
        }

        // ---- protocol primitives -------------------------------------------------

        private static void writeVarIntPacket(OutputStream out, byte[] payload) throws IOException {
                writeVarInt(out, payload.length);
                out.write(payload);
        }

        private static void writeVarInt(OutputStream out, int value) throws IOException {
                while (true) {
                        int bits = value & 0x7F;
                        value >>>= 7;
                        if (value != 0) {
                                out.write(bits | 0x80);
                        } else {
                                out.write(bits);
                                return;
                        }
                }
        }

        private static void writeString(OutputStream out, String value) throws IOException {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                writeVarInt(out, bytes.length);
                out.write(bytes);
        }

        private static void writeUnsignedShort(OutputStream out, int value) throws IOException {
                out.write((value >>> 8) & 0xFF);
                out.write(value & 0xFF);
        }

        private static int readVarInt(InputStream in) throws IOException {
                int value = 0;
                int size = 0;
                while (true) {
                        int b = in.read();
                        if (b < 0) {
                                throw new EOFException("stream ended while reading a VarInt");
                        }
                        value |= (b & 0x7F) << (7 * size);
                        if ((b & 0x80) == 0) {
                                return value;
                        }
                        if (++size > 5) {
                                throw new IOException("VarInt is too large");
                        }
                }
        }

        private static int readVarInt(byte[] buffer, int[] offset) throws IOException {
                int value = 0;
                int size = 0;
                while (true) {
                        if (offset[0] >= buffer.length) {
                                throw new EOFException("packet ended while reading a VarInt");
                        }
                        int b = buffer[offset[0]++] & 0xFF;
                        value |= (b & 0x7F) << (7 * size);
                        if ((b & 0x80) == 0) {
                                return value;
                        }
                        if (++size > 5) {
                                throw new IOException("VarInt is too large");
                        }
                }
        }

        private static String readString(byte[] buffer, int[] offset) throws IOException {
                int length = readVarInt(buffer, offset);
                if (length < 0 || offset[0] + length > buffer.length) {
                        throw new EOFException("string length " + length + " exceeds the packet");
                }
                String value = new String(buffer, offset[0], length, StandardCharsets.UTF_8);
                offset[0] += length;
                return value;
        }

        private static byte[] readFully(InputStream in, int length) throws IOException {
                byte[] buffer = new byte[length];
                int read = 0;
                while (read < length) {
                        int count = in.read(buffer, read, length - read);
                        if (count < 0) {
                                throw new EOFException("stream ended with " + (length - read) + " bytes outstanding");
                        }
                        read += count;
                }
                return buffer;
        }

}
