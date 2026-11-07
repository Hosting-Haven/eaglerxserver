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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent.ForwardResult;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.query.ProxyQueryEvent;
import com.velocitypowered.api.permission.PermissionSubject;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.TranslatableComponent;
import net.lax1dude.eaglercraft.backend.server.adapter.IEaglerXServerPlayerCountHandler;
import net.lax1dude.eaglercraft.backend.server.adapter.IEaglerXServerMessageHandler;
import net.lax1dude.eaglercraft.backend.server.adapter.IPipelineData;
import net.lax1dude.eaglercraft.backend.server.adapter.IPlatformPlayer;
import net.lax1dude.eaglercraft.backend.server.adapter.IPlatformServer;
import net.lax1dude.eaglercraft.backend.server.adapter.PipelineAttributes;
import net.lax1dude.eaglercraft.backend.server.base.BasePlayerInstance;
import net.lax1dude.eaglercraft.backend.server.base.ProtocolLadder;
import net.lax1dude.eaglercraft.backend.server.base.compat.BackendConnectStatus;
import net.lax1dude.eaglercraft.backend.server.base.compat.BackendReasonKind;
import net.lax1dude.eaglercraft.backend.server.base.compat.CompatFailure;
import net.lax1dude.eaglercraft.backend.server.base.compat.CompatFailureCategory;
import net.lax1dude.eaglercraft.backend.server.base.compat.CompatFailureClassifier;
import net.lax1dude.eaglercraft.backend.server.velocity.PlatformPluginVelocity.PluginMessageHandler;

class VelocityListener {

	static final int MAX_PROTOCOL_RETRIES = 4;
	// A transient transport failure may be re-attempted, but only a bounded number
	// of times and without changing protocol version, because the protocol version
	// has nothing to do with a timeout or an unreachable route.
	static final int MAX_TRANSIENT_RETRIES = 1;
	private static final int[] RETRY_PROTOCOLS = new int[] { 754, 340, 47 };

	private static class BackendProtocolOverride {
		String targetServer;
		int protocol;
		boolean managedByViaVersion;
	}

	private final PlatformPluginVelocity plugin;
	private final ConcurrentMap<UUID, BackendProtocolOverride> backendProtocolOverrides = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, Integer> successfulBackendProtocols = new ConcurrentHashMap<>();
	private final Set<UUID> bypassManagedPreConnect = ConcurrentHashMap.newKeySet();
	private final Set<UUID> managedConnectInProgress = ConcurrentHashMap.newKeySet();

	VelocityListener(PlatformPluginVelocity plugin) {
		this.plugin = plugin;
                this.motdPassthrough = plugin.getMotdPassthrough();
        }

        private final MotdPassthrough motdPassthrough;

        /**
         * Mirror the Java MOTD onto the Eaglercraft response.
         *
         * Runs before client listeners so a network's own MOTD plugin can still
         * override the result; the passthrough is a default, not a cage.
         */
        @Subscribe(priority = 16384, async = false)
        public void onEaglercraftMOTDEvent(net.lax1dude.eaglercraft.backend.server.api.velocity.event.EaglercraftMOTDEvent event) {
                motdPassthrough.apply(event.getMOTDConnection());
        }

	@Subscribe(priority = -16384, async = false)
	public void onGameProfileRequestEvent(GameProfileRequestEvent gameProfileEvent) {
		IPipelineData pipelineData = VelocityUnsafe.getInboundChannel(gameProfileEvent.getConnection())
				.attr(PipelineAttributes.<IPipelineData>pipelineData()).get();
		gameProfileEvent.setGameProfile(plugin.initializeLogin(pipelineData, gameProfileEvent.getGameProfile()));
	}

	@Subscribe(async = false)
	public void onPermissionsSetupEvent(PermissionsSetupEvent permissionsSetupEvent) {
		// Fired right before compression is enabled
		PermissionSubject p = permissionsSetupEvent.getSubject();
		if (p instanceof Player player) {
			IPipelineData conn = VelocityUnsafe.getInboundChannel(player)
					.attr(PipelineAttributes.<IPipelineData>pipelineData()).get();
			if (conn != null && conn.isCompressionDisable()) {
				VelocityUnsafe.injectCompressionDisable(plugin.proxy(), player);
			}
		}
	}

	@Subscribe(priority = Short.MAX_VALUE, async = true)
	public void onPostLoginEvent(PostLoginEvent loginEvent, Continuation cont) {
		Player player = loginEvent.getPlayer();
		IPipelineData conn = VelocityUnsafe.getInboundChannel(player)
				.attr(PipelineAttributes.<IPipelineData>pipelineData()).getAndSet(null);
		awaitPlayState(conn, () -> {
			try {
				plugin.initializePlayer(player, conn, (b) -> {
					if (b) {
						cont.resume();
					} else {
						// Hang forever on cancel, connection is already dead, async callback will GC
					}
				});
			} catch (Exception ex) {
				cont.resumeWithException(ex);
			}
		});
	}

	private static void awaitPlayState(IPipelineData conn, Runnable cont) {
		if (conn != null) {
			conn.awaitPlayState(cont);
		} else {
			cont.run();
		}
	}

	@Subscribe(priority = Short.MIN_VALUE, async = false)
	public void onPlayerDisconnected(DisconnectEvent disconnectEvent) {
		UUID playerId = disconnectEvent.getPlayer().getUniqueId();
		managedConnectInProgress.remove(playerId);
		bypassManagedPreConnect.remove(playerId);
		backendProtocolOverrides.remove(playerId);
		plugin.dropPlayer(disconnectEvent.getPlayer());
	}

	@Subscribe(priority = Short.MIN_VALUE, async = false)
	public void onServerPreConnected(ServerPreConnectEvent connectEvent) {
		Player player = connectEvent.getPlayer();
		UUID playerId = player.getUniqueId();
		IPlatformPlayer<Player> platformPlayer = plugin.getPlayer(player);
		boolean eaglerPlayer = isEaglerPlayer(platformPlayer);
		boolean trace = plugin.isHeavyConnectionDebugEnabled();
		if (trace && eaglerPlayer) {
			plugin.logger().info("[trace] pre-connect player=" + player.getUsername() + " target="
					+ connectEvent.getOriginalServer().getServerInfo().getName() + " protocol="
					+ player.getProtocolVersion().getProtocol() + " allowed=" + connectEvent.getResult().isAllowed());
		}

		if (bypassManagedPreConnect.remove(playerId)) {
			if (trace && eaglerPlayer) {
				plugin.logger().info("[trace] pre-connect bypassed for " + player.getUsername());
			}
			return;
		}

		if (connectEvent.getResult().isAllowed()) {
			if (platformPlayer != null) {
				((VelocityPlayer) platformPlayer).server = null;
				plugin.handleServerPreConnect(platformPlayer);
				if (!eaglerPlayer) {
					return;
				}

				RegisteredServer target = connectEvent.getResult().getServer().orElse(connectEvent.getOriginalServer());
				if (managedConnectInProgress.add(playerId)) {
					connectEvent.setResult(ServerPreConnectEvent.ServerResult.denied());
					int originalProtocol = player.getProtocolVersion().getProtocol();
					plugin.logger().info("[compat] Managing connect for " + player.getUsername() + " -> "
							+ target.getServerInfo().getName() + " (start protocol " + originalProtocol + ")");
					startManagedConnectAttempt(player, target, originalProtocol,
							buildProtocolAttemptPlan(originalProtocol,
									successfulBackendProtocols.get(target.getServerInfo().getName())),
							0, 0, new ArrayList<>(MAX_PROTOCOL_RETRIES + 1));
				}
			}
		}
	}

	@Subscribe(priority = Short.MAX_VALUE, async = false)
	public void onServerPostConnected(ServerPostConnectEvent connectEvent) {
		Player player = connectEvent.getPlayer();
		IPlatformPlayer<Player> platformPlayer = plugin.getPlayer(player);
		restoreManagedConnect(player);
		if (plugin.isHeavyConnectionDebugEnabled() && isEaglerPlayer(platformPlayer)) {
			plugin.logger().info("[trace] post-connect player=" + player.getUsername()
					+ " currentServer=" + player.getCurrentServer().map((s) -> s.getServer()
							.getServerInfo().getName()).orElse("<none>"));
		}

		Optional<ServerConnection> serverCon = player.getCurrentServer();
		if (serverCon.isPresent()) {
			RegisteredServer server = serverCon.get().getServer();
			if (platformPlayer != null) {
				IPlatformServer<Player> platformServer = null;
				platformServer = plugin.getRegisteredServers().get(server.getServerInfo().getName());
				if (platformServer == null) {
					platformServer = new VelocityServer(plugin, server, false);
				}
				((VelocityPlayer) platformPlayer).server = platformServer;
				plugin.handleServerPostConnect(platformPlayer, platformServer);
			}
		}
	}

	private static boolean isEaglerPlayer(IPlatformPlayer<Player> platformPlayer) {
		if (platformPlayer == null) {
			return false;
		}
		BasePlayerInstance<Player> attachment = platformPlayer.getPlayerAttachment();
		return attachment != null && attachment.isEaglerPlayer();
	}

	boolean isEaglerPlayer(Player player) {
		return isEaglerPlayer(plugin.getPlayer(player));
	}

	static int[] buildProtocolAttemptPlan(int currentProtocol, Integer knownBackendProtocol) {
		LinkedHashSet<Integer> plan = new LinkedHashSet<>();
		if (knownBackendProtocol != null) {
			plan.add(knownBackendProtocol);
		}
		plan.add(Integer.valueOf(currentProtocol));
		// Dynamically pick the nearest lower release protocol so a client one release
		// ahead of the backend can downgrade gracefully instead of dropping all the
		// way to 1.16.5 and losing modern blocks.
		int[] ladder = ProtocolLadder.buildForConnection(currentProtocol);
		for (int i = ladder.length - 1; i >= 0; --i) {
			if (ladder[i] < currentProtocol) {
				plan.add(Integer.valueOf(ladder[i]));
				break;
			}
		}
		for (int protocol : RETRY_PROTOCOLS) {
			plan.add(Integer.valueOf(protocol));
		}
		int[] ret = new int[plan.size()];
		int i = 0;
		for (Integer protocol : plan) {
			ret[i++] = protocol.intValue();
		}
		return ret;
	}

	private void startManagedConnectAttempt(Player player, RegisteredServer target, int originalProtocol, int[] plan,
			int attemptIndex, int transientRetries, List<Integer> tried) {
		if (!player.isActive()) {
			restoreManagedConnect(player);
			return;
		}

		String serverName = target.getServerInfo().getName();

		if (attemptIndex >= plan.length || attemptIndex > MAX_PROTOCOL_RETRIES) {
			plugin.logger().warn("[compat] Exhausted managed retries for " + player.getUsername() + " -> " + serverName
					+ " tried=" + tried + " category=PROTOCOL_MISMATCH reason=protocol ladder exhausted");
			disconnectOrNotifyFailure(player, target, VelocityCompatFailureMessages
					.componentFor(CompatFailureCategory.PROTOCOL_MISMATCH, serverName, null));
			restoreManagedConnect(player);
			return;
		}

		int protocol = plan[attemptIndex];
		if (!setBackendProtocolOverride(player, target, originalProtocol, protocol)) {
			disconnectOrNotifyFailure(player, target, Component.text("Unable to connect to " + serverName
					+ ": ViaVersion is required for cross-version backends", NamedTextColor.RED));
			restoreManagedConnect(player);
			return;
		}

		tried.add(Integer.valueOf(protocol));
		plugin.logger().info("[compat] Managed connect attempt " + (attemptIndex + 1) + "/"
				+ Math.min(plan.length, MAX_PROTOCOL_RETRIES + 1) + " for " + player.getUsername() + " -> " + serverName
				+ " using protocol " + protocol);
		if (plugin.isHeavyConnectionDebugEnabled()) {
			plugin.logger().info("[trace] connect attempt state player=" + player.getUsername() + " target=" + serverName
					+ " tried=" + tried + " originalProtocol=" + originalProtocol + " transientRetries="
					+ transientRetries);
		}

		bypassManagedPreConnect.add(player.getUniqueId());
		player.createConnectionRequest(target).connect().whenComplete((result, throwable) -> {
			if (!player.isActive()) {
				restoreManagedConnect(player);
				return;
			}

			// A rejected login must never become the cached protocol for this backend.
			if (result != null && result.isSuccessful()) {
				successfulBackendProtocols.put(serverName, Integer.valueOf(protocol));
				plugin.logger().info("[compat] Managed connect success for " + player.getUsername() + " -> " + serverName
						+ " after attempts=" + tried + " (cached protocol " + protocol + ")");
				return;
			}

			Component backendReason = result != null ? result.getReasonComponent().orElse(null) : null;
			ConnectionRequestBuilder.Status status = result != null ? result.getStatus() : null;

			String reasonText = plainReason(backendReason);

			CompatFailure failure = CompatFailureClassifier.classify(mapStatus(status), reasonKindOf(backendReason),
					throwable);

			CompatRetryStep step = CompatRetryStep.next(failure, attemptIndex, transientRetries, plan.length,
					MAX_PROTOCOL_RETRIES, MAX_TRANSIENT_RETRIES);

			// The category and the retry decision are logged together with the backend
			// and the attempted protocol, so a support request can be answered without
			// reproducing the connection.
			plugin.logger().info("[compat] Managed attempt failed for " + player.getUsername() + " -> " + serverName
					+ " protocol=" + protocol + " status=" + status + " category=" + failure.getCategory()
					+ " retry=" + step.retry + " reason='" + reasonText + "'"
					+ (failure.getDetail().isEmpty() ? "" : " detail=" + failure.getDetail()));

			if (!step.retry) {
				disconnectOrNotifyFailure(player, target,
						VelocityCompatFailureMessages.componentFor(failure.getCategory(), serverName, backendReason));
				restoreManagedConnect(player);
				return;
			}

			startManagedConnectAttempt(player, target, originalProtocol, plan, step.attemptIndex,
					step.transientRetries, tried);
		});
	}

	/**
	 * Map the platform status onto the platform-neutral enum.
	 */
	static BackendConnectStatus mapStatus(ConnectionRequestBuilder.Status status) {
		if (status == null) {
			return BackendConnectStatus.UNKNOWN;
		}

		switch (status) {
			case SUCCESS:
				return BackendConnectStatus.SUCCESS;
			case SERVER_DISCONNECTED:
				return BackendConnectStatus.SERVER_DISCONNECTED;
			case ALREADY_CONNECTED:
				return BackendConnectStatus.ALREADY_CONNECTED;
			case CONNECTION_IN_PROGRESS:
				return BackendConnectStatus.CONNECTION_IN_PROGRESS;
			case CONNECTION_CANCELLED:
				return BackendConnectStatus.CONNECTION_CANCELLED;
			default:
				return BackendConnectStatus.UNKNOWN;
		}
	}

	/**
	 * Decide who authored a disconnect reason.
	 * 
	 * Velocity reports a failed backend connection as SERVER_DISCONNECTED with its
	 * own translatable "internal server connection error" when the backend never
	 * sent a kick of its own. That key is the difference between "the backend said
	 * no" and "the connection never worked", and it is what makes it safe to retry
	 * the ladder for a real protocol mismatch while never retrying a whitelist
	 * rejection.
	 */
	static BackendReasonKind reasonKindOf(Component reason) {
		if (reason == null) {
			return BackendReasonKind.NONE;
		}

		if (reason instanceof TranslatableComponent) {
			String key = ((TranslatableComponent) reason).key();

			if (key != null && key.startsWith("velocity.error.")) {
				return BackendReasonKind.TRANSPORT_GENERIC;
			}
		}

		if (Component.empty().equals(reason)) {
			return BackendReasonKind.NONE;
		}

		return BackendReasonKind.BACKEND_AUTHORED;
	}

	private String plainReason(Component reason) {
		return reason != null ? plugin.getComponentHelper().serializePlainText(reason) : "";
	}

	private void disconnectOrNotifyFailure(Player player, RegisteredServer target, Component reasonComponent) {
		if (player.getCurrentServer().isPresent()) {
			player.sendMessage(reasonComponent);
		} else {
			// The component is passed through untouched so the client renders the real
			// reason, including colours and translatable text.
			player.disconnect(reasonComponent);
		}
		plugin.logger()
				.warn("[compat] Final failure for " + player.getUsername() + " -> " + target.getServerInfo().getName());
	}

	private boolean setBackendProtocolOverride(Player player, RegisteredServer target, int originalProtocol,
			int protocol) {
		BackendProtocolOverride override = new BackendProtocolOverride();
		override.targetServer = target.getServerInfo().getName();
		override.protocol = protocol;
		override.managedByViaVersion = VelocityUnsafe.setViaBackendProtocol(plugin.proxy(), override.targetServer,
				originalProtocol, protocol, plugin.logger());
		if (!override.managedByViaVersion && protocol != originalProtocol) {
			plugin.logger().warn("[compat] Cannot translate " + originalProtocol + " -> " + protocol + " for backend "
					+ override.targetServer + " because ViaVersion is unavailable or incompatible");
			return false;
		}
		backendProtocolOverrides.put(player.getUniqueId(), override);
		if (plugin.isHeavyConnectionDebugEnabled()) {
			plugin.logger().info("[trace] backend protocol candidate server=" + override.targetServer + " client="
					+ originalProtocol + " backend=" + protocol + " viaVersion=" + override.managedByViaVersion);
		}
		return true;
	}

	Integer getBackendProtocolOverride(Player player, String serverName) {
		BackendProtocolOverride override = backendProtocolOverrides.get(player.getUniqueId());
		if (override == null || override.managedByViaVersion || !override.targetServer.equals(serverName)) {
			return null;
		}
		return Integer.valueOf(override.protocol);
	}

	private void restoreManagedConnect(Player player) {
		UUID playerId = player.getUniqueId();
		boolean wasManaged = managedConnectInProgress.remove(playerId);
		bypassManagedPreConnect.remove(playerId);
		backendProtocolOverrides.remove(playerId);
		if (wasManaged && plugin.isHeavyConnectionDebugEnabled()) {
			plugin.logger().info("[trace] cleared managed connect state for " + player.getUsername());
		}
	}

	@Subscribe(priority = Short.MAX_VALUE, async = false)
	public void onPluginMessageEvent(PluginMessageEvent evt) {
		PluginMessageHandler handler = plugin.registeredChannelsMap.get(evt.getIdentifier());
		if (handler != null) {
			evt.setResult(ForwardResult.handled());
			ChannelMessageSource src = evt.getSource();
			ChannelMessageSink dst = evt.getTarget();
			if (handler.backend) {
				IEaglerXServerMessageHandler<Player> ls = handler.handler;
				if (ls != null && (src instanceof ServerConnection) && (dst instanceof Player dst2)) {
					IPlatformPlayer<Player> player = plugin.getPlayer(dst2);
					if (player != null) {
						ls.handle(handler.channel, player, evt.getData());
					}
				}
			} else {
				IEaglerXServerMessageHandler<Player> ls = handler.handler;
				if (ls != null && (src instanceof Player src2) && (dst instanceof ServerConnection)) {
					IPlatformPlayer<Player> player = plugin.getPlayer(src2);
					if (player != null) {
						ls.handle(handler.channel, player, evt.getData());
					}
				}
			}
		}
	}

	@Subscribe(priority = 16384, async = false)
	public void onProxyPingEvent(ProxyPingEvent evt) {
		IEaglerXServerPlayerCountHandler count = plugin.playerCountHandler;
		if (count != null) {
			evt.setPing(evt.getPing().asBuilder().onlinePlayers(count.getPlayerTotal())
					.maximumPlayers(count.getPlayerMax()).build());
		}
	}

	@Subscribe(priority = 16384, async = false)
	public void onProxyQueryEvent(ProxyQueryEvent evt) {
		IEaglerXServerPlayerCountHandler count = plugin.playerCountHandler;
		if (count != null) {
			evt.setResponse(evt.getResponse().toBuilder().currentPlayers(count.getPlayerTotal())
					.maxPlayers(count.getPlayerMax()).build());
		}
	}

}
