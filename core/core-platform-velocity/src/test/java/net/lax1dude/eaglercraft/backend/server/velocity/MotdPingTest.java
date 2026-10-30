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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The MOTD passthrough is only as good as its translation of a Java status
 * response, and that translation is the part that cannot be eyeballed on a live
 * server without a Java client to compare against. These cover the shapes a
 * server is actually allowed to send.
 */
class MotdPingTest {

        @Test
        void aPlainStringDescriptionBecomesOneLine() throws IOException {
                MotdSnapshot snapshot = MotdPing
                                .fromJson("{\"description\":\"Welcome to the server\",\"players\":{\"online\":3,\"max\":20}}");

                assertEquals(List.of("Welcome to the server"), snapshot.getMotd());
                assertEquals(3, snapshot.getOnline());
                assertEquals(20, snapshot.getMax());
        }

        @Test
        void aComponentDescriptionKeepsItsColours() throws IOException {
                MotdSnapshot snapshot = MotdPing.fromJson("{\"description\":{\"text\":\"Hello\",\"color\":\"gold\"}}");

                assertEquals(1, snapshot.getMotd().size());
                // Legacy section-sign form is what an Eaglercraft client renders.
                assertTrue(snapshot.getMotd().get(0).contains("\u00a7"),
                                "expected legacy formatting, got: " + snapshot.getMotd().get(0));
                assertTrue(snapshot.getMotd().get(0).contains("Hello"));
        }

        @Test
        void aTwoLineMotdKeepsBothLines() throws IOException {
                MotdSnapshot snapshot = MotdPing.fromJson("{\"description\":\"Line one\\nLine two\"}");

                assertEquals(List.of("Line one", "Line two"), snapshot.getMotd());
        }

        /**
         * The response only has room for two lines; a third must not leak through
         * and break the ordering of the rest of the payload.
         */
        @Test
        void aThirdLineIsDropped() throws IOException {
                MotdSnapshot snapshot = MotdPing.fromJson("{\"description\":\"One\\nTwo\\nThree\"}");

                assertEquals(List.of("One", "Two"), snapshot.getMotd());
        }

        /**
         * A server that sends no description must leave the configured MOTD in
         * place rather than blanking the entry.
         */
        @Test
        void aMissingDescriptionYieldsNoMotd() throws IOException {
                MotdSnapshot snapshot = MotdPing.fromJson("{\"players\":{\"online\":0,\"max\":10}}");

                assertTrue(snapshot.getMotd().isEmpty());
                assertTrue(!snapshot.hasMotd());
        }

        @Test
        void aColourCodeWithNoTextAfterItIsTrimmed() throws IOException {
                MotdSnapshot snapshot = MotdPing.fromJson("{\"description\":\"Visible\u00a7r\"}");

                assertEquals(List.of("Visible"), snapshot.getMotd());
        }

        @Test
        void thePlayerSampleIsExtracted() throws IOException {
                MotdSnapshot snapshot = MotdPing
                                .fromJson("{\"description\":\"x\",\"players\":{\"online\":2,\"max\":5,\"sample\":["
                                                + "{\"name\":\"Alice\",\"id\":\"00000000-0000-0000-0000-000000000000\"},"
                                                + "{\"name\":\"Bob\",\"id\":\"00000000-0000-0000-0000-000000000000\"}]}}");

                assertEquals(List.of("Alice", "Bob"), snapshot.getPlayerList());
        }

        @Test
        void anAbsentPlayerSectionIsZeroed() throws IOException {
                MotdSnapshot snapshot = MotdPing.fromJson("{\"description\":\"x\"}");

                assertEquals(0, snapshot.getOnline());
                assertEquals(0, snapshot.getMax());
                assertTrue(snapshot.getPlayerList().isEmpty());
        }

        /**
         * A favicon the decoder cannot handle must cost only the icon, never the
         * MOTD the customer actually reads.
         */
        @Test
        void aBrokenFaviconDoesNotDiscardTheMotd() throws IOException {
                MotdSnapshot snapshot = MotdPing
                                .fromJson("{\"description\":\"Still here\",\"favicon\":\"data:image/png;base64,bm90LWEtcG5n\"}");

                assertEquals(List.of("Still here"), snapshot.getMotd());
                assertNull(snapshot.getIcon());
        }

        @Test
        void malformedJsonIsAnError() {
                assertThrows(IOException.class, () -> MotdPing.fromJson("not json"));
        }

        @Test
        void aNonObjectResponseIsAnError() {
                assertThrows(IOException.class, () -> MotdPing.fromJson("[1,2,3]"));
        }

}
