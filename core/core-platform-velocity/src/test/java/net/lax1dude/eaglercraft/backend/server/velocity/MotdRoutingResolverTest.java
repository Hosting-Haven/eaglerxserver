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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The routing rules auto mode depends on, pinned against Velocity's own
 * behaviour.
 *
 * These were read out of the deployed proxy's bytecode
 * ({@code ConnectedPlayer.getNextServerToTry}) rather than assumed from
 * documentation, because the details that matter here are the surprising ones:
 * the hostname is matched without its port and case-insensitively, and the
 * default {@code try} order is consulted ONLY when there is no forced-host entry
 * at all.
 */
class MotdRoutingResolverTest {

        private static Map<String, List<String>> forced(Object... hostAndServers) {
                Map<String, List<String>> map = new LinkedHashMap<>();
                for (int i = 0; i < hostAndServers.length; i += 2) {
                        map.put((String) hostAndServers[i], Arrays.asList((String[]) hostAndServers[i + 1]));
                }
                return map;
        }

        @Test
        void forcedHostWinsOverTheDefaultOrder() {
                Map<String, List<String>> hosts = forced("creative.example.com", new String[] { "creative" });

                assertEquals(Collections.singletonList("creative"),
                                MotdRoutingResolver.candidateOrder("creative.example.com", hosts,
                                                Arrays.asList("lobby", "survival")));
        }

        @Test
        void unknownHostnameFallsBackToTheDefaultOrder() {
                Map<String, List<String>> hosts = forced("creative.example.com", new String[] { "creative" });

                assertEquals(Arrays.asList("lobby", "survival"),
                                MotdRoutingResolver.candidateOrder("other.example.com", hosts,
                                                Arrays.asList("lobby", "survival")));
        }

        @Test
        void hostnameMatchingIgnoresCase() {
                Map<String, List<String>> hosts = forced("creative.example.com", new String[] { "creative" });

                assertEquals(Collections.singletonList("creative"),
                                MotdRoutingResolver.candidateOrder("Creative.Example.COM", hosts,
                                                Collections.singletonList("lobby")));
        }

        @Test
        void hostnameMatchingIgnoresThePort() {
                // The WebSocket Host header carries the listener port; Velocity matches
                // on getHostString(), which does not.
                Map<String, List<String>> hosts = forced("creative.example.com", new String[] { "creative" });

                assertEquals(Collections.singletonList("creative"),
                                MotdRoutingResolver.candidateOrder("creative.example.com:3008", hosts,
                                                Collections.singletonList("lobby")));
        }

        @Test
        void bracketedIpv6KeepsItsAddressAndLosesItsPort() {
                assertEquals("::1", MotdRoutingResolver.normaliseHostname("[::1]:25565"));
                assertEquals("::1", MotdRoutingResolver.normaliseHostname("[::1]"));
        }

        @Test
        void bareIpv6IsNotMangled() {
                // No brackets and several colons: there is no port to strip, so the
                // address must survive intact.
                assertEquals("2001:db8::1", MotdRoutingResolver.normaliseHostname("2001:db8::1"));
        }

        @Test
        void missingHostnameBecomesEmptyRatherThanThrowing() {
                assertEquals("", MotdRoutingResolver.normaliseHostname(null));
                assertEquals("", MotdRoutingResolver.normaliseHostname("   "));
        }

        /**
         * A forced host does NOT fall back to the default order.
         *
         * This is Velocity's behaviour: the try list is only consulted when the
         * forced-host list is empty. Inventing a fallback here would show a MOTD for
         * a backend the player would never be sent to.
         */
        @Test
        void forcedHostDoesNotFallBackToTheDefaultOrder() {
                Map<String, List<String>> hosts = forced("creative.example.com", new String[] { "creative" });

                List<String> candidates = MotdRoutingResolver.candidateOrder("creative.example.com", hosts,
                                Arrays.asList("lobby", "survival"));

                assertEquals(Collections.singletonList("creative"), candidates);
                assertTrue(!candidates.contains("lobby"));
        }

        @Test
        void multipleForcedHostCandidatesKeepTheirConfiguredOrder() {
                Map<String, List<String>> hosts = forced("creative.example.com", new String[] { "a", "b" });

                assertEquals(Arrays.asList("a", "b"), MotdRoutingResolver.candidateOrder("creative.example.com",
                                hosts, Collections.singletonList("lobby")));
        }

        @Test
        void anEmptyForcedHostEntryUsesTheDefaultOrder() {
                Map<String, List<String>> hosts = forced("creative.example.com", new String[0]);

                assertEquals(Collections.singletonList("lobby"), MotdRoutingResolver.candidateOrder(
                                "creative.example.com", hosts, Collections.singletonList("lobby")));
        }

        @Test
        void nothingConfiguredMeansNoCandidates() {
                assertTrue(MotdRoutingResolver.candidateOrder("anything", Collections.emptyMap(), null).isEmpty());
                assertTrue(MotdRoutingResolver.candidateOrder("anything", null, Collections.emptyList()).isEmpty());
        }

        // --- availability selection -------------------------------------

        @Test
        void theFirstAvailableCandidateWins() {
                assertEquals("a", MotdRoutingResolver.firstAvailable(Arrays.asList("a", "b"), name -> true));
        }

        /**
         * Priority beats speed. A later candidate must not win because it happened to
         * answer first, so the choice is made by walking the list in order.
         */
        @Test
        void anUnavailablePrimaryHandsOverToTheNextCandidate() {
                assertEquals("b", MotdRoutingResolver.firstAvailable(Arrays.asList("a", "b"),
                                name -> name.equals("b")));
        }

        @Test
        void noAvailableCandidateYieldsNull() {
                assertNull(MotdRoutingResolver.firstAvailable(Arrays.asList("a", "b"), name -> false));
                assertNull(MotdRoutingResolver.firstAvailable(Collections.emptyList(), name -> true));
                assertNull(MotdRoutingResolver.firstAvailable(null, name -> true));
        }

        /**
         * A backend that is in the routing config but no longer registered must not
         * block the candidates behind it.
         */
        @Test
        void aDeletedBackendIsSkipped() {
                assertEquals("c", MotdRoutingResolver.firstAvailable(Arrays.asList("gone", "b", "c"),
                                name -> name.equals("c")));
        }

}
