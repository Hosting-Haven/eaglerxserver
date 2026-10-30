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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Which Java status a listener mirrors is a per-listener decision with a
 * proxy-wide default, and a network's public server list depends on that
 * precedence being right. None of it needs a running proxy, so it is tested
 * directly rather than through a mock.
 */
class MotdPassthroughTargetTest {

        private static final String GLOBAL = "eaglercraft.motd.target";
        private static final String PREFIX = GLOBAL + ".";

        @AfterEach
        void clearOverrides() {
                System.clearProperty(GLOBAL);
                System.clearProperty(PREFIX + "lobby");
                System.clearProperty(PREFIX + "lobby-2");
                System.clearProperty(PREFIX + "backend");
                System.clearProperty(PREFIX + "staff");
        }

        @Test
        void listenerNamesAreFlattenedForEnvironmentVariables() {
                assertEquals("LOBBY", MotdPassthrough.envName("lobby"));
                assertEquals("LOBBY_2", MotdPassthrough.envName("lobby-2"));
                assertEquals("EU_WEST_A", MotdPassthrough.envName("eu.west a"));
        }

        @Test
        void optOutWordsAreRecognised() {
                assertTrue(MotdPassthrough.isOptOut("off"));
                assertTrue(MotdPassthrough.isOptOut("OFF"));
                assertTrue(MotdPassthrough.isOptOut("  listeners "));
                assertFalse(MotdPassthrough.isOptOut("10.0.0.5:25565"));
                assertFalse(MotdPassthrough.isOptOut(null));
        }

        @Test
        void aListenerWithoutItsOwnTargetInheritsTheDefault() {
                System.setProperty(GLOBAL, "10.0.0.1:25565");

                InetSocketAddress target = new MotdPassthrough(null).targetForListener("lobby", null);

                assertTarget(target, "10.0.0.1", 25565);
        }

        @Test
        void aListenersOwnTargetWinsOverTheDefault() {
                System.setProperty(GLOBAL, "10.0.0.1:25565");
                System.setProperty(PREFIX + "backend", "10.0.0.9:25599");

                MotdPassthrough passthrough = new MotdPassthrough(null);

                assertTarget(passthrough.targetForListener("backend", null), "10.0.0.9", 25599);
                assertTarget(passthrough.targetForListener("lobby", null), "10.0.0.1", 25565);
        }

        @Test
        void aListenerCanOptOutAndKeepItsListenersTomlMotd() {
                System.setProperty(GLOBAL, "10.0.0.1:25565");
                System.setProperty(PREFIX + "lobby-2", "off");

                assertNull(new MotdPassthrough(null).targetForListener("lobby-2", null));
        }

        @Test
        void theRawOverrideIsReadPerListener() {
                System.setProperty(PREFIX + "lobby-2", "backend");

                MotdPassthrough passthrough = new MotdPassthrough(null);

                assertEquals("backend", passthrough.listenerOverride("lobby-2"));
                assertNull(passthrough.listenerOverride("lobby"));
                assertNull(passthrough.listenerOverride(null));
        }

        @Test
        void hostAndPortAreParsed() {
                assertTarget(MotdPassthrough.parseHostPort("10.0.0.5:25565"), "10.0.0.5", 25565);
                assertTarget(MotdPassthrough.parseHostPort("  example.org : 25566 "), "example.org", 25566);
                // Last colon wins, so a bare IPv6 literal still parses.
                assertTarget(MotdPassthrough.parseHostPort("::1:25565"), "::1", 25565);
        }

        /** The setting the operator actually edits, with no properties set at all. */
        @Test
        void theListenersTomlTargetIsUsedOnItsOwn() {
                InetSocketAddress target = new MotdPassthrough(null).targetForListener("staff", "10.0.0.9:25599");

                assertTarget(target, "10.0.0.9", 25599);
        }

        @Test
        void theListenersTomlTargetIsOverriddenByTheProperty() {
                System.setProperty(PREFIX + "staff", "10.0.0.7:25000");

                InetSocketAddress target = new MotdPassthrough(null).targetForListener("staff", "10.0.0.9:25599");

                assertTarget(target, "10.0.0.7", 25000);
        }

        @Test
        void theListenersTomlOptOutWinsOverTheGlobalDefault() {
                System.setProperty(GLOBAL, "10.0.0.1:25565");

                assertNull(new MotdPassthrough(null).targetForListener("staff", "off"));
        }

        /** A typo in listeners.toml must not strand the listener; it inherits. */
        @Test
        void aBrokenListenersTomlTargetFallsBackToTheDefault() {
                System.setProperty(GLOBAL, "10.0.0.1:25565");

                InetSocketAddress target = new MotdPassthrough(null).targetForListener("staff", "no-port-here");
                assertTarget(target, "10.0.0.1", 25565);
        }

        @Test
        void brokenTargetsAreRejected() {
                assertThrows(IllegalArgumentException.class, () -> MotdPassthrough.parseHostPort("example.org"));
                assertThrows(IllegalArgumentException.class, () -> MotdPassthrough.parseHostPort(":25565"));
                assertThrows(IllegalArgumentException.class, () -> MotdPassthrough.parseHostPort("host:port"));
                assertThrows(IllegalArgumentException.class, () -> MotdPassthrough.parseHostPort("host:0"));
                assertThrows(IllegalArgumentException.class, () -> MotdPassthrough.parseHostPort("host:70000"));
        }

        private static void assertTarget(InetSocketAddress target, String host, int port) {
                assertNotNull(target);
                assertEquals(host, target.getHostString());
                assertEquals(port, target.getPort());
        }

}
