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
import net.lax1dude.eaglercraft.backend.server.adapter.IEaglerXServerPlayerCountHandler;
import net.lax1dude.eaglercraft.backend.server.adapter.IEaglerXServerMessageHandler;
import net.lax1dude.eaglercraft.backend.server.adapter.IPipelineData;
import net.lax1dude.eaglercraft.backend.server.adapter.IPlatformPlayer;
import net.lax1dude.eaglercraft.backend.server.adapter.IPlatformServer;
import net.lax1dude.eaglercraft.backend.server.adapter.PipelineAttributes;
import net.lax1dude.eaglercraft.backend.server.velocity.PlatformPluginVelocity.PluginMessageHandler;

class VelocityListener {

	private static final int MAX_PROTOCOL_RETRIES = 4;
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
		boolean trace = plugin.isHeavyConnectionDebugEnabled();
		if (trace) {
			plugin.logger().info("[trace] pre-connect player=" + player.getUsername() + " target="
					+ connectEvent.getOriginalServer().getServerInfo().getName() + " protocol="
					+ player.getProtocolVersion().getProtocol() + " allowed=" + connectEvent.getResult().isAllowed());
		}

		if (bypassManagedPreConnect.remove(playerId)) {
			if (trace) {
				plugin.logger().info("[trace] pre-connect bypassed for " + player.getUsername());
			}
			return;
		}

		if (connectEvent.getResult().isAllowed()) {
			IPlatformPlayer<Player> platformPlayer = plugin.getPlayer(player);
			if (platformPlayer != null) {
				((VelocityPlayer) platformPlayer).server = null;
				plugin.handleServerPreConnect(platformPlayer);

				RegisteredServer target = connectEvent.getResult().getServer().orElse(connectEvent.getOriginalServer());
				if (managedConnectInProgress.add(playerId)) {
					connectEvent.setResult(ServerPreConnectEvent.ServerResult.denied());
					int originalProtocol = player.getProtocolVersion().getProtocol();
					plugin.logger().info("[compat] Managing connect for " + player.getUsername() + " -> "
							+ target.getServerInfo().getName() + " (start protocol " + originalProtocol + ")");
					startManagedConnectAttempt(player, target, originalProtocol,
							buildProtocolAttemptPlan(originalProtocol,
									successfulBackendProtocols.get(target.getServerInfo().getName())),
							0, new ArrayList<>(MAX_PROTOCOL_RETRIES + 1));
				}
			}
		}
	}

	@Subscribe(priority = Short.MAX_VALUE, async = false)
	public void onServerPostConnected(ServerPostConnectEvent connectEvent) {
		UUID playerId = connectEvent.getPlayer().getUniqueId();
		restoreManagedConnect(connectEvent.getPlayer());
		if (plugin.isHeavyConnectionDebugEnabled()) {
			plugin.logger().info("[trace] post-connect player=" + connectEvent.getPlayer().getUsername()
					+ " currentServer=" + connectEvent.getPlayer().getCurrentServer().map((s) -> s.getServer()
							.getServerInfo().getName()).orElse("<none>"));
		}

		Optional<ServerConnection> serverCon = connectEvent.getPlayer().getCurrentServer();
		if (serverCon.isPresent()) {
			RegisteredServer server = serverCon.get().getServer();
			IPlatformPlayer<Player> platformPlayer = plugin.getPlayer(connectEvent.getPlayer());
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

	private static int[] buildProtocolAttemptPlan(int currentProtocol, Integer knownBackendProtocol) {
		LinkedHashSet<Integer> plan = new LinkedHashSet<>();
		if (knownBackendProtocol != null) {
			plan.add(knownBackendProtocol);
		}
		plan.add(Integer.valueOf(currentProtocol));
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
			int attemptIndex, List<Integer> tried) {
		if (!player.isActive()) {
			restoreManagedConnect(player);
			return;
		}
		if (attemptIndex >= plan.length || attemptIndex > MAX_PROTOCOL_RETRIES) {
			plugin.logger().warn("[compat] Exhausted managed retries for " + player.getUsername() + " -> "
					+ target.getServerInfo().getName() + " tried=" + tried);
			disconnectOrNotifyFailure(player, target,
					Component.text("Unable to connect to " + target.getServerInfo().getName()
							+ ": incompatible protocol"));
			restoreManagedConnect(player);
			return;
		}

		int protocol = plan[attemptIndex];
		if (!setBackendProtocolOverride(player, target, originalProtocol, protocol)) {
			disconnectOrNotifyFailure(player, target, Component.text("Unable to connect to "
					+ target.getServerInfo().getName() + ": ViaVersion is required for cross-version backends"));
			restoreManagedConnect(player);
			return;
		}

		tried.add(Integer.valueOf(protocol));
		plugin.logger().info("[compat] Managed connect attempt " + (attemptIndex + 1) + "/"
				+ Math.min(plan.length, MAX_PROTOCOL_RETRIES + 1) + " for " + player.getUsername() + " -> "
				+ target.getServerInfo().getName() + " using protocol " + protocol);
		if (plugin.isHeavyConnectionDebugEnabled()) {
			plugin.logger().info("[trace] connect attempt state player=" + player.getUsername() + " target="
					+ target.getServerInfo().getName() + " tried=" + tried + " originalProtocol="
					+ originalProtocol);
		}

		bypassManagedPreConnect.add(player.getUniqueId());
		player.createConnectionRequest(target).connect().whenComplete((result, throwable) -> {
			if (!player.isActive()) {
				restoreManagedConnect(player);
				return;
			}

			if (throwable != null) {
				plugin.logger().warn("[compat] Managed attempt failed for " + player.getUsername() + " -> "
						+ target.getServerInfo().getName() + " protocol " + protocol + " throwable="
						+ throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
				startManagedConnectAttempt(player, target, originalProtocol, plan, attemptIndex + 1, tried);
				return;
			}

			if (result != null && result.isSuccessful()) {
				successfulBackendProtocols.put(target.getServerInfo().getName(), Integer.valueOf(protocol));
				plugin.logger().info("[compat] Managed connect success for " + player.getUsername() + " -> "
						+ target.getServerInfo().getName() + " after attempts=" + tried
						+ " (cached protocol " + protocol + ")");
				return;
			}

			Component reasonComponent = result != null ? result.getReasonComponent().orElse(null) : null;
			String reason = reasonComponent != null ? plugin.getComponentHelper().serializePlainText(reasonComponent)
					: "";
			ConnectionRequestBuilder.Status status = result != null ? result.getStatus()
					: ConnectionRequestBuilder.Status.SERVER_DISCONNECTED;

			plugin.logger().info("[compat] Managed attempt failed for " + player.getUsername() + " -> "
					+ target.getServerInfo().getName() + " protocol=" + protocol + " status=" + status + " reason='"
					+ reason + "'");

			if (attemptIndex + 1 <= MAX_PROTOCOL_RETRIES) {
				startManagedConnectAttempt(player, target, originalProtocol, plan, attemptIndex + 1, tried);
			} else {
				disconnectOrNotifyFailure(player, target, reasonComponent != null ? reasonComponent
						: Component.text("Unable to connect to " + target.getServerInfo().getName()));
				restoreManagedConnect(player);
			}
		});
	}

	private void disconnectOrNotifyFailure(Player player, RegisteredServer target, Component reasonComponent) {
		if (player.getCurrentServer().isPresent()) {
			player.sendMessage(reasonComponent);
		} else {
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
		managedConnectInProgress.remove(playerId);
		bypassManagedPreConnect.remove(playerId);
		backendProtocolOverrides.remove(playerId);
		if (plugin.isHeavyConnectionDebugEnabled()) {
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
