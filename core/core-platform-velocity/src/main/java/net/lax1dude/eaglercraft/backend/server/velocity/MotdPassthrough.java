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

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;

import net.lax1dude.eaglercraft.backend.server.api.IEaglerListenerInfo;
import net.lax1dude.eaglercraft.backend.server.api.query.IMOTDConnection;

/**
 * Mirrors the Java MOTD onto the Eaglercraft MOTD response.
 *
 * Eaglercraft clients get their MOTD from listeners.toml, which is a separate
 * setting from the one Java clients see. That means a network can advertise one
 * thing in the Java server list and something else entirely in the Eaglercraft
 * one, and administrators have to remember to change both.
 *
 * Rather than copying configuration (which would go stale the moment anything
 * rewrites the MOTD at ping time), this asks the Java status endpoint for the
 * real answer and reuses it.
 *
 * The status is polled in the background and the last good result is applied
 * synchronously when an Eaglercraft client asks for the MOTD. Two reasons:
 *
 *  - the MOTD path must never block on a socket, because a slow or unreachable
 *    target would stall every client's server list, and
 *  - a burst of clients refreshing at once must not become a burst of pings.
 *
 * If the target cannot be reached the previous snapshot is kept, so a temporary
 * outage degrades to a slightly stale MOTD rather than an empty one.
 */
class MotdPassthrough {

        /**
         * Where the Java status is read from.
         *
         * Unset means "this proxy": a loopback ping of the proxy's own Java
         * listener, which is the exact view a Java client gets. Set it to a
         * {@code host:port} to mirror some other server, or to a registered
         * Velocity server name to mirror that backend.
         *
         * Normally this is set per listener in listeners.toml instead, using
         * {@code motd_passthrough_target}. The property exists as an operational
         * override: qualifying it with a listener's name, as
         * {@code eaglercraft.motd.target.<listener>} or the environment variable
         * {@code EAGLERCRAFT_MOTD_TARGET_<LISTENER>} (upper-cased, with anything
         * that is not a letter or digit replaced by an underscore), points one
         * listener elsewhere without editing a file.
         *
         * Precedence is: per-listener property, then listeners.toml, then this
         * unqualified property, then the proxy's own listener. Setting a listener
         * to {@code off} leaves its listeners.toml MOTD alone, so one listener can
         * follow the proxy while another mirrors a backend.
         */
        static final String TARGET_PROPERTY = "eaglercraft.motd.target";
        static final String TARGET_ENV = "EAGLERCRAFT_MOTD_TARGET";

        private static final String TARGET_PROPERTY_PREFIX = TARGET_PROPERTY + ".";
        private static final String TARGET_ENV_PREFIX = TARGET_ENV + "_";

        /** Values that mean "do not override this listener at all". */
        private static final Set<String> DISABLED_VALUES = Set.of("off", "none", "false", "disabled", "listeners",
                        "toml", "default");

        private static final long REFRESH_SECONDS = 2L;
        private static final int PING_TIMEOUT_MILLIS = 1500;

        private static final Supplier<Collection<IEaglerListenerInfo>> NO_LISTENERS = Collections::emptyList;

        private final ProxyServer proxy;

        /**
         * The configured listeners, so their per-listener targets can be polled.
         * Supplied lazily because the passthrough is constructed before the config
         * has been read.
         */
        private volatile Supplier<Collection<IEaglerListenerInfo>> listeners = NO_LISTENERS;

        /**
         * The most recent successful status, per target address. Several listeners
         * can share a target, so this is keyed by target rather than by listener.
         * Written by the poller and read by the MOTD path.
         */
        private final Map<String, MotdSnapshot> snapshots = new ConcurrentHashMap<>();

        /**
         * Targets already reported as failing, so an unreachable address cannot
         * spam the console every two seconds. Per target rather than global, so one
         * dead target cannot silence the report for another.
         */
        private final Set<String> failuresLogged = ConcurrentHashMap.newKeySet();

        private ScheduledTask scheduledTask;

        MotdPassthrough(ProxyServer proxy) {
                this.proxy = proxy;
        }

        void start(Object plugin, Supplier<Collection<IEaglerListenerInfo>> listeners) {
                this.listeners = listeners == null ? NO_LISTENERS : listeners;
                try {
                        scheduledTask = proxy.getScheduler().buildTask(plugin, this::refresh)
                                        .repeat(REFRESH_SECONDS, TimeUnit.SECONDS).schedule();
                } catch (RuntimeException ex) {
                        // Scheduling is an optimisation; applying only the first poll would
                        // still work, so a failure here must not disable the feature.
                        refresh();
                }
        }

        void stop() {
                ScheduledTask task = scheduledTask;
                if (task != null) {
                        task.cancel();
                }
                scheduledTask = null;
        }

        /**
         * Take one status reading per distinct target.
         *
         * Every configured target is polled, not just the default one, because a
         * listener may be pointed at its own backend. Never throws: the caller is a
         * scheduled task and a target that is briefly down is expected, not
         * exceptional.
         */
        void refresh() {
                for (Map.Entry<String, InetSocketAddress> entry : collectTargets().entrySet()) {
                        String key = entry.getKey();
                        InetSocketAddress target = entry.getValue();

                        try {
                                MotdSnapshot fresh = MotdPing.ping(target, PING_TIMEOUT_MILLIS);
                                if (fresh.hasMotd()) {
                                        snapshots.put(key, fresh);
                                        failuresLogged.remove(key);
                                }
                        } catch (Exception ex) {
                                // Keep the last good reading, so a blip degrades to a slightly
                                // stale MOTD instead of blanking it.
                                logFailure(key, "could not read the Java MOTD from " + describeTarget(target), ex);
                        }
                }
        }

        /**
         * Apply the snapshot for this listener's target, if there is one.
         *
         * The listener's own override wins, otherwise the default applies. Silently
         * does nothing when this listener is opted out or before the first
         * successful poll, which leaves the configured listeners.toml MOTD in place.
         */
        void apply(IMOTDConnection connection) {
                IEaglerListenerInfo info = listenerInfo(connection);
                InetSocketAddress target = targetForListener(info == null ? null : info.getName(),
                                info == null ? null : info.getMotdPassthroughTarget());
                if (target == null) {
                        return;
                }

                MotdSnapshot current = snapshots.get(key(target));
                if (current == null) {
                        return;
                }

                if (current.hasMotd()) {
                        connection.setServerMOTD(current.getMotd());
                }

                byte[] icon = current.getIcon();
                if (icon != null) {
                        connection.setServerIcon(icon);
                }

                connection.setPlayerTotal(current.getOnline());
                connection.setPlayerMax(current.getMax());

                if (!current.getPlayerList().isEmpty()) {
                        connection.setPlayerList(current.getPlayerList());
                }
        }

        /**
         * Every address worth polling: the default, plus any address a listener has
         * been pointed at explicitly.
         *
         * Overrides are discovered by scanning configuration rather than by
         * enumerating listeners, so a listener that has never served a request is
         * still warm by the time its first client arrives.
         */
        private Map<String, InetSocketAddress> collectTargets() {
                Map<String, InetSocketAddress> targets = new LinkedHashMap<>();

                putTarget(targets, defaultTarget());

                for (String name : System.getProperties().stringPropertyNames()) {
                        if (name.startsWith(TARGET_PROPERTY_PREFIX)) {
                                putTarget(targets, parseOverride(System.getProperty(name), name));
                        }
                }

                for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
                        if (entry.getKey().startsWith(TARGET_ENV_PREFIX)) {
                                putTarget(targets, parseOverride(entry.getValue(), entry.getKey()));
                        }
                }

                // listeners.toml is the normal place to configure this, so those targets
                // must be polled too or they would never have a snapshot to apply.
                for (IEaglerListenerInfo listener : listeners.get()) {
                        String configured = listener.getMotdPassthroughTarget();
                        if (configured != null && !configured.trim().isEmpty() && !isOptOut(configured)) {
                                putTarget(targets, parseOverride(configured,
                                                listenerOverrideKey(listener.getName()) + " (listeners.toml)"));
                        }
                }

                return targets;
        }

        private static void putTarget(Map<String, InetSocketAddress> targets, InetSocketAddress address) {
                if (address != null) {
                        targets.putIfAbsent(key(address), address);
                }
        }

        /**
         * The target for one listener: its own override when it has one, otherwise
         * the default. Null means this listener keeps its listeners.toml MOTD.
         */
        InetSocketAddress targetForListener(String listenerName, String configuredTarget) {
                String override = listenerOverride(listenerName);
                if (override != null) {
                        if (isOptOut(override)) {
                                return null;
                        }

                        InetSocketAddress parsed = parseOverride(override, listenerOverrideKey(listenerName));
                        if (parsed != null) {
                                return parsed;
                        }
                        // A broken per-listener property logs once and then behaves as if
                        // unset, rather than freezing this listener on a stale MOTD.
                }

                if (configuredTarget != null) {
                        if (isOptOut(configuredTarget)) {
                                return null;
                        }

                        InetSocketAddress parsed = parseOverride(configuredTarget,
                                        listenerOverrideKey(listenerName) + " (listeners.toml)");
                        if (parsed != null) {
                                return parsed;
                        }
                }

                return defaultTarget();
        }

        /** The target used by every listener that has no override of its own. */
        private InetSocketAddress defaultTarget() {
                String override = firstNonEmpty(System.getProperty(TARGET_PROPERTY), System.getenv(TARGET_ENV));
                if (override != null) {
                        return parseOverride(override, TARGET_PROPERTY);
                }

                try {
                        return ownListener();
                } catch (RuntimeException ex) {
                        // Nothing may escape refresh(): if it did, every tick would fail and
                        // the MOTD would freeze on whatever was last read.
                        logFailure(TARGET_PROPERTY, "could not determine this proxy's own Java listener", ex);
                        return null;
                }
        }

        /** The raw override for one listener, or null when it has none. */
        String listenerOverride(String listenerName) {
                if (listenerName == null || listenerName.isEmpty()) {
                        return null;
                }

                String byProperty = firstNonEmpty(System.getProperty(TARGET_PROPERTY_PREFIX + listenerName), null);
                if (byProperty != null) {
                        return byProperty;
                }

                return firstNonEmpty(System.getenv(TARGET_ENV_PREFIX + envName(listenerName)), null);
        }

        private static String listenerOverrideKey(String listenerName) {
                return listenerName == null || listenerName.isEmpty() ? TARGET_PROPERTY
                                : TARGET_PROPERTY_PREFIX + listenerName;
        }

        /**
         * Environment variables cannot contain most punctuation, so a listener name
         * is flattened the same way: {@code lobby-2} becomes {@code LOBBY_2}.
         */
        static String envName(String listenerName) {
                StringBuilder out = new StringBuilder(listenerName.length());
                for (int i = 0; i < listenerName.length(); ++i) {
                        char c = listenerName.charAt(i);
                        out.append(Character.isLetterOrDigit(c) ? Character.toUpperCase(c) : '_');
                }
                return out.toString();
        }

        private static String firstNonEmpty(String first, String second) {
                if (first != null && !first.trim().isEmpty()) {
                        return first.trim();
                }
                if (second != null && !second.trim().isEmpty()) {
                        return second.trim();
                }
                return null;
        }

        /**
         * Parse an override into an address, treating the documented opt-out words
         * as "leave this listener alone" and a broken value as "log once, then
         * behave as if unset".
         */
        InetSocketAddress parseOverride(String value, String source) {
                String trimmed = value == null ? null : value.trim();
                if (trimmed == null || trimmed.isEmpty()) {
                        return null;
                }

                if (isOptOut(trimmed)) {
                        return null;
                }

                try {
                        return parseTarget(trimmed);
                } catch (RuntimeException ex) {
                        logFailure(source, "invalid MOTD passthrough target", ex);
                        return null;
                }
        }

        /** Whether an override value means "leave this listener's own MOTD alone". */
        static boolean isOptOut(String value) {
                return value != null && DISABLED_VALUES.contains(value.trim().toLowerCase(Locale.ROOT));
        }

        private static IEaglerListenerInfo listenerInfo(IMOTDConnection connection) {
                try {
                        return connection.getListenerInfo();
                } catch (RuntimeException ex) {
                        // A connection without a listener still deserves the default MOTD.
                        return null;
                }
        }

        private static String describeTarget(InetSocketAddress address) {
                String host = address.getHostString();
                return (host == null || host.isEmpty() ? "unknown" : host) + ":" + address.getPort();
        }

        private static String key(InetSocketAddress address) {
                String host = address.getHostString();
                return (host == null ? "" : host.toLowerCase(Locale.ROOT)) + ":" + address.getPort();
        }

        private InetSocketAddress parseTarget(String value) {
                // A bare server name addresses a backend registered in velocity.toml.
                if (!value.contains(":")) {
                        return proxy.getServer(value).map(RegisteredServer::getServerInfo)
                                        .map(info -> info.getAddress()).orElseThrow(() -> new IllegalArgumentException(
                                                        "no Velocity server named \"" + value + "\""));
                }

                return parseHostPort(value);
        }

        /**
         * Parse a literal {@code host:port}. Kept free of the proxy so the rules
         * that decide where a listener reads its MOTD can be tested directly.
         */
        static InetSocketAddress parseHostPort(String value) {
                // Last colon, so a bare IPv6 host still parses.
                int colon = value.lastIndexOf(':');
                if (colon < 0) {
                        throw new IllegalArgumentException("MOTD passthrough target has no port: " + value);
                }

                String host = value.substring(0, colon).trim();
                String portText = value.substring(colon + 1).trim();
                if (host.isEmpty()) {
                        throw new IllegalArgumentException("MOTD passthrough target has no host: " + value);
                }

                int port;
                try {
                        port = Integer.parseInt(portText);
                } catch (NumberFormatException ex) {
                        throw new IllegalArgumentException("MOTD passthrough target has no valid port: " + value);
                }

                if (port < 1 || port > 65535) {
                        throw new IllegalArgumentException("MOTD passthrough target port is out of range: " + value);
                }

                return InetSocketAddress.createUnresolved(host, port);
        }

        /**
         * The proxy's own Java listener, reached over loopback.
         *
         * A wildcard bind (0.0.0.0 / ::) is not a connectable address, so it is
         * replaced with the loopback address of the same family.
         */
        private InetSocketAddress ownListener() {
                InetSocketAddress bind = proxy.getBoundAddress();

                if (bind == null) {
                        return InetSocketAddress.createUnresolved("127.0.0.1", 25565);
                }

                String host = bind.getHostString();
                if (host == null || host.isEmpty() || bind.getAddress() == null || bind.getAddress().isAnyLocalAddress()) {
                        host = isIpv6Wildcard(host) ? "::1" : "127.0.0.1";
                }

                return InetSocketAddress.createUnresolved(host, bind.getPort() > 0 ? bind.getPort() : 25565);
        }

        private static boolean isIpv6Wildcard(String host) {
                return host != null && (host.equals("::") || host.equals("0:0:0:0:0:0:0:0"));
        }

        private void logFailure(String key, String message, Throwable cause) {
                if (!failuresLogged.add(key)) {
                        return;
                }

                ProxyServer target = proxy;
                if (target == null) {
                        // Nowhere to report it. Returning keeps this method safe to call
                        // from any failure path, including before the proxy is available.
                        return;
                }

                target.sendMessage(net.kyori.adventure.text.Component
                                .text("[EaglerXServer] " + message + ": " + describe(cause))
                                .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW));
        }

        private static String describe(Throwable cause) {
                String message = cause.getMessage();
                String type = cause.getClass().getSimpleName().toLowerCase(Locale.ROOT);
                return message == null || message.isEmpty() ? type : type + " (" + message + ")";
        }

}
