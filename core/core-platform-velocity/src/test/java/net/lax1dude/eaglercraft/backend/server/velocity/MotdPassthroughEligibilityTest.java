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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.lax1dude.eaglercraft.backend.server.api.EnumMotdPassthroughPolicy;

/**
 * Backend eligibility for {@code auto} MOTD passthrough.
 *
 * The invariant these tests exist to protect is that eligibility is a FILTER
 * applied after routing, never a second routing pass. An excluded backend must
 * leave the listener showing its own MOTD; it must never cause a different
 * backend to be chosen, because that would describe a server the player is not
 * being sent to.
 */
class MotdPassthroughEligibilityTest {

        private static Collection<String> names(String... values) {
                return Arrays.asList(values);
        }

        // ------------------------------------------------------------------
        // backward compatibility
        // ------------------------------------------------------------------

        /**
         * A listener whose configuration predates this setting reports the default
         * policy and an empty list, which must behave exactly like "all".
         */
        @Test
        void absentSettingKeepsThePreviousBehaviour() {
                assertTrue(MotdPassthroughEligibility.permits(EnumMotdPassthroughPolicy.ALL, null, "lobby"));
                assertTrue(MotdPassthroughEligibility.permits(EnumMotdPassthroughPolicy.ALL,
                                Collections.emptyList(), "lobby"));

                // A null policy is what an implementation that predates the setting yields.
                assertTrue(MotdPassthroughEligibility.permits(null, null, "lobby"));
        }

        // ------------------------------------------------------------------
        // the three policies
        // ------------------------------------------------------------------

        @Test
        void allPermitsEveryBackendEvenWhenAListIsConfigured() {
                assertTrue(MotdPassthroughEligibility.permits(EnumMotdPassthroughPolicy.ALL,
                                names("lobby"), "minigames"));
        }

        @Test
        void nonePermitsNoBackend() {
                assertFalse(MotdPassthroughEligibility.permits(EnumMotdPassthroughPolicy.NONE, null, "lobby"));
                assertFalse(MotdPassthroughEligibility.permits(EnumMotdPassthroughPolicy.NONE,
                                names("lobby"), "lobby"));
        }

        @Test
        void selectedPermitsOnlyListedBackends() {
                Collection<String> allowed = names("minigames");

                assertTrue(MotdPassthroughEligibility.permits(EnumMotdPassthroughPolicy.SELECTED, allowed, "minigames"));
                assertFalse(MotdPassthroughEligibility.permits(EnumMotdPassthroughPolicy.SELECTED, allowed, "factions"));
        }

        @Test
        void selectedIsCaseInsensitiveAndIgnoresSurroundingSpace() {
                Collection<String> allowed = names("  MiniGames  ");

                assertTrue(MotdPassthroughEligibility.permits(EnumMotdPassthroughPolicy.SELECTED, allowed, "minigames"));
                assertTrue(MotdPassthroughEligibility.permits(EnumMotdPassthroughPolicy.SELECTED, allowed, "MINIGAMES"));
        }

        @Test
        void selectedWithNoNamesPermitsNothing() {
                assertFalse(MotdPassthroughEligibility.permits(EnumMotdPassthroughPolicy.SELECTED,
                                Collections.emptyList(), "lobby"));
                assertFalse(MotdPassthroughEligibility.permits(EnumMotdPassthroughPolicy.SELECTED, null, "lobby"));
        }

        @Test
        void blankAndNullEntriesNeverMatch() {
                Collection<String> allowed = names("", "   ", null);

                assertFalse(MotdPassthroughEligibility.permits(EnumMotdPassthroughPolicy.SELECTED, allowed, "lobby"));
                assertFalse(MotdPassthroughEligibility.permits(EnumMotdPassthroughPolicy.SELECTED, allowed, ""));
                assertFalse(MotdPassthroughEligibility.permits(EnumMotdPassthroughPolicy.SELECTED, allowed, null));
        }

        /**
         * A name that velocity.toml does not define simply never matches. The listener
         * keeps its own MOTD rather than failing the whole status request.
         */
        @Test
        void unknownBackendNameIsNeverPermitted() {
                Collection<String> allowed = names("lobby", "does-not-exist");

                assertFalse(MotdPassthroughEligibility.permits(EnumMotdPassthroughPolicy.SELECTED, allowed, "survival"));
                assertTrue(MotdPassthroughEligibility.permits(EnumMotdPassthroughPolicy.SELECTED, allowed, "lobby"));
        }

        // ------------------------------------------------------------------
        // composition with the routing logic
        // ------------------------------------------------------------------

        /**
         * Mirrors {@code MotdPassthrough.applyAuto}: pick a backend from the routing
         * order using freshness, then filter. Null means "leave the listener's own
         * MOTD in place".
         */
        private static String resolve(List<String> candidates, Set<String> healthy,
                        EnumMotdPassthroughPolicy policy, Collection<String> allowed) {
                String chosen = MotdRoutingResolver.firstAvailable(candidates, healthy::contains);
                if (chosen == null) {
                        return null;
                }
                return MotdPassthroughEligibility.permits(policy, allowed, chosen) ? chosen : null;
        }

        /**
         * The central invariant: excluding the healthy backend must NOT hand the MOTD
         * to a permitted but lower-priority one.
         */
        @Test
        void excludedHealthyBackendIsNotReplacedByAPermittedOne() {
                List<String> candidates = Arrays.asList("lobby", "minigames");
                Set<String> healthy = Set.of("lobby", "minigames");

                assertNull(resolve(candidates, healthy, EnumMotdPassthroughPolicy.SELECTED, names("minigames")),
                                "an excluded backend must not be swapped for a permitted one");
        }

        /**
         * Fallback that exists is Velocity's own: the first candidate is skipped
         * because it is unavailable, and the next one is then judged normally.
         */
        @Test
        void unavailableBackendFallsThroughToAPermittedOne() {
                List<String> candidates = Arrays.asList("lobby", "minigames");
                Set<String> healthy = Set.of("minigames");

                assertEquals("minigames", resolve(candidates, healthy, EnumMotdPassthroughPolicy.SELECTED,
                                names("minigames")));

                // The same fall-through still applies the filter to the survivor.
                assertNull(resolve(candidates, healthy, EnumMotdPassthroughPolicy.SELECTED, names("lobby")));
        }

        @Test
        void nothingPermittedLeavesTheListenerMotd() {
                List<String> candidates = Collections.singletonList("lobby");

                assertNull(resolve(candidates, Set.of("lobby"), EnumMotdPassthroughPolicy.NONE, names("lobby")));
        }

        /**
         * Forced hosts are resolved before eligibility and never fall back to the
         * global {@code try} list, even when the forced backend is excluded.
         */
        @Test
        void forcedHostSelectionNeverFallsBackToTheTryList() {
                Map<String, List<String>> forcedHosts = new LinkedHashMap<>();
                forcedHosts.put("creative.example.com", Collections.singletonList("creative"));

                List<String> candidates = MotdRoutingResolver.candidateOrder("creative.example.com", forcedHosts,
                                Arrays.asList("lobby", "minigames"));

                assertEquals(Collections.singletonList("creative"), candidates);

                // "creative" is excluded, and "lobby" from the try list must NOT be used.
                assertNull(resolve(candidates, Set.of("creative", "lobby", "minigames"),
                                EnumMotdPassthroughPolicy.SELECTED, names("lobby", "minigames")));
        }

        /**
         * Two listeners configured differently must not influence each other.
         */
        @Test
        void listenersWithDifferentPoliciesResolveIndependently() {
                List<String> candidates = Collections.singletonList("lobby");
                Set<String> healthy = Set.of("lobby");

                String permissive = resolve(candidates, healthy, EnumMotdPassthroughPolicy.ALL, null);
                String restrictive = resolve(candidates, healthy, EnumMotdPassthroughPolicy.SELECTED,
                                names("minigames"));
                String disabled = resolve(candidates, healthy, EnumMotdPassthroughPolicy.NONE, null);

                assertEquals("lobby", permissive);
                assertNull(restrictive);
                assertNull(disabled);
        }

}
