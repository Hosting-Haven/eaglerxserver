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

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which backend Velocity would send a connection to, decided from the hostname
 * alone.
 *
 * This mirrors the routing in Velocity's own
 * {@code ConnectedPlayer.getNextServerToTry}, verified against the deployed
 * proxy's bytecode rather than assumed:
 *
 * <pre>
 * host = virtualHost.getHostString().toLowerCase(Locale.ROOT)
 * candidates = forcedHosts.getOrDefault(host, emptyList())
 * if (candidates.isEmpty()) {
 *     candidates = attemptConnectionOrder
 * }
 * </pre>
 *
 * Two consequences are easy to get wrong and are pinned by tests:
 *
 *  - the hostname is matched WITHOUT its port, because Velocity matches on
 *    {@code getHostString()}, and case-insensitively;
 *  - {@code try} is only consulted when there is no forced-host entry. A forced
 *    host with no reachable backend does NOT fall back to {@code try}, so
 *    neither may this.
 *
 * Kept free of Velocity types so the rules can be tested directly.
 */
final class MotdRoutingResolver {

        private MotdRoutingResolver() {
        }

        /**
         * The backends Velocity would attempt, in order.
         *
         * @param hostname     the requested host, possibly including a port
         * @param forcedHosts  {@code ProxyConfig.getForcedHosts()}
         * @param attemptOrder {@code ProxyConfig.getAttemptConnectionOrder()}
         */
        static List<String> candidateOrder(String hostname, Map<String, List<String>> forcedHosts,
                        List<String> attemptOrder) {
                if (forcedHosts != null) {
                        List<String> forced = forcedHosts.get(normaliseHostname(hostname));
                        if (forced != null && !forced.isEmpty()) {
                                return forced;
                        }
                }

                return attemptOrder == null ? Collections.emptyList() : attemptOrder;
        }

        /**
         * Lowercase the host and drop any port, matching Velocity's
         * {@code getHostString()} comparison.
         *
         * Handles a bracketed IPv6 literal ({@code [::1]:25565}) as well as a plain
         * one ({@code ::1}), and only strips a port when there is exactly one colon so
         * an unbracketed IPv6 address is not mangled.
         */
        static String normaliseHostname(String hostname) {
                if (hostname == null) {
                        return "";
                }

                String host = hostname.trim();
                if (host.isEmpty()) {
                        return "";
                }

                if (host.charAt(0) == '[') {
                        int close = host.indexOf(']');
                        if (close > 0) {
                                host = host.substring(1, close);
                        }
                } else {
                        int colon = host.lastIndexOf(':');
                        if (colon > 0 && host.indexOf(':') == colon) {
                                host = host.substring(0, colon);
                        }
                }

                return host.toLowerCase(Locale.ROOT);
        }

        /**
         * The first candidate that is currently usable, in configured priority order.
         *
         * Priority is the whole point: a later candidate must never win merely because
         * it happened to answer first, so this walks the list rather than picking the
         * first success from a concurrent burst.
         */
        static String firstAvailable(List<String> candidates, java.util.function.Predicate<String> available) {
                if (candidates == null) {
                        return null;
                }

                for (String candidate : candidates) {
                        if (candidate != null && !candidate.isEmpty() && available.test(candidate)) {
                                return candidate;
                        }
                }

                return null;
        }

}
