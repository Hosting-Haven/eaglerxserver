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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Map;
import java.util.function.Consumer;

import com.google.common.collect.ForwardingMap;
import com.google.common.collect.ForwardingMultimap;
import com.google.common.collect.Multimap;
import com.velocitypowered.api.network.ListenerType;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.util.VelocityInboundConnection;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import net.kyori.adventure.text.Component;
import net.lax1dude.eaglercraft.backend.server.adapter.IEaglerXServerListener;
import net.lax1dude.eaglercraft.backend.server.adapter.IPlatformLogger;
import net.lax1dude.eaglercraft.backend.server.util.ClassProxy;
import net.lax1dude.eaglercraft.backend.server.util.ListenerInitList;
import net.lax1dude.eaglercraft.backend.server.util.Util;

public class VelocityUnsafe {

	private static final Class<?> class_LoginInboundConnection;
	private static final Method method_LoginInboundConnection_delegatedConnection;
	private static final Class<MinecraftConnection> class_MinecraftConnection;
	private static final Constructor<MinecraftConnection> ctor_MinecraftConnection;
	private static final ClassProxy<MinecraftConnection> classProxy_MinecraftConnection;
	private static final Method method_MinecraftConnection_getState;
	private static final Method method_MinecraftConnection_getAssociation;
	private static final Method method_MinecraftConnection_setProtocolVersion;
	private static final Field field_MinecraftConnection_protocolVersion;
	private static final Field field_MinecraftConnection_activeSessionHandler;
	private static final Field field_MinecraftConnection_remoteAddress;
	private static final Class<?> class_AuthSessionHandler;
	private static final Field field_AuthSessionHandler_mcConnection;
	private static final Class<?> class_DisconnectPacket;
	private static final Class<?> class_StateRegistry;
	private static final Method method_DisconnectPacket_create;
	private static final Class<?> class_VelocityServer;
	private static final Field field_VelocityServer_cm;
	private static final Class<?> class_ConnectionManager;
	private static final Method method_ConnectionManager_getServerChannelInitializer;
	private static final Method method_ConnectionManager_getBackendChannelInitializer;
	private static final Method method_ConnectionManager_bind;
	private static final Field field_ConnectionManager_endpoints;
	private static final Field field_ConnectionManager_bossGroup;
	private static final Field field_ConnectionManager_workerGroup;
	private static final Field field_ConnectionManager_transportType;
	private static final Class<?> class_TransportType;
	private static final Field field_TransportType_socketChannelFactory;
	private static final Field field_TransportType_serverSocketChannelFactory;
	private static final Class<?> class_Endpoint;
	private static final Method method_Endpoint_getChannel;
	private static final Method method_Endpoint_getType;
	private static final Class<?> class_ServerChannelInitializerHolder;
	private static final Method method_ServerChannelInitializerHolder_get;
	private static final Method method_ServerChannelInitializerHolder_set;
	private static final Class<?> class_BackendChannelInitializerHolder;
	private static final Method method_BackendChannelInitializerHolder_get;
	private static final Method method_BackendChannelInitializerHolder_set;
	private static final Method method_ChannelInitializer_initChannel;
	private static final Method method_VelocityServerConnection_getPlayer;
	private static final Method method_VelocityServerConnection_getServerInfo;

	static {
		try {
			class_VelocityServer = Class.forName("com.velocitypowered.proxy.VelocityServer");
			class_LoginInboundConnection = Class
					.forName("com.velocitypowered.proxy.connection.client.LoginInboundConnection");
			method_LoginInboundConnection_delegatedConnection = class_LoginInboundConnection
					.getDeclaredMethod("delegatedConnection");
			method_LoginInboundConnection_delegatedConnection.setAccessible(true);
			class_MinecraftConnection = MinecraftConnection.class;
			ctor_MinecraftConnection = class_MinecraftConnection.getConstructor(Channel.class, class_VelocityServer);
			classProxy_MinecraftConnection = ClassProxy.bindProxy(VelocityUnsafe.class.getClassLoader(),
					class_MinecraftConnection);
			method_MinecraftConnection_getState = class_MinecraftConnection.getMethod("getState");
			method_MinecraftConnection_getAssociation = class_MinecraftConnection.getMethod("getAssociation");
			method_MinecraftConnection_setProtocolVersion = class_MinecraftConnection.getMethod("setProtocolVersion",
					ProtocolVersion.class);
			field_MinecraftConnection_protocolVersion = class_MinecraftConnection.getDeclaredField("protocolVersion");
			field_MinecraftConnection_protocolVersion.setAccessible(true);
			field_MinecraftConnection_activeSessionHandler = class_MinecraftConnection
					.getDeclaredField("activeSessionHandler");
			field_MinecraftConnection_activeSessionHandler.setAccessible(true);
			field_MinecraftConnection_remoteAddress = class_MinecraftConnection.getDeclaredField("remoteAddress");
			field_MinecraftConnection_remoteAddress.setAccessible(true);
			class_AuthSessionHandler = Class.forName("com.velocitypowered.proxy.connection.client.AuthSessionHandler");
			field_AuthSessionHandler_mcConnection = class_AuthSessionHandler.getDeclaredField("mcConnection");
			field_AuthSessionHandler_mcConnection.setAccessible(true);
			class_DisconnectPacket = Class.forName("com.velocitypowered.proxy.protocol.packet.DisconnectPacket");
			class_StateRegistry = Class.forName("com.velocitypowered.proxy.protocol.StateRegistry");
			method_DisconnectPacket_create = class_DisconnectPacket.getMethod("create", Component.class,
					ProtocolVersion.class, class_StateRegistry);
			field_VelocityServer_cm = class_VelocityServer.getDeclaredField("cm");
			field_VelocityServer_cm.setAccessible(true);
			class_ConnectionManager = Class.forName("com.velocitypowered.proxy.network.ConnectionManager");
			method_ConnectionManager_getServerChannelInitializer = class_ConnectionManager
					.getMethod("getServerChannelInitializer");
			method_ConnectionManager_getBackendChannelInitializer = class_ConnectionManager
					.getMethod("getBackendChannelInitializer");
			method_ConnectionManager_bind = class_ConnectionManager.getMethod("bind", InetSocketAddress.class);
			field_ConnectionManager_endpoints = class_ConnectionManager.getDeclaredField("endpoints");
			field_ConnectionManager_endpoints.setAccessible(true);
			field_ConnectionManager_bossGroup = class_ConnectionManager.getDeclaredField("bossGroup");
			field_ConnectionManager_bossGroup.setAccessible(true);
			field_ConnectionManager_workerGroup = class_ConnectionManager.getDeclaredField("workerGroup");
			field_ConnectionManager_workerGroup.setAccessible(true);
			field_ConnectionManager_transportType = class_ConnectionManager.getDeclaredField("transportType");
			field_ConnectionManager_transportType.setAccessible(true);
			class_TransportType = Class.forName("com.velocitypowered.proxy.network.TransportType");
			field_TransportType_socketChannelFactory = class_TransportType.getDeclaredField("socketChannelFactory");
			field_TransportType_socketChannelFactory.setAccessible(true);
			field_TransportType_serverSocketChannelFactory = class_TransportType
					.getDeclaredField("serverSocketChannelFactory");
			field_TransportType_serverSocketChannelFactory.setAccessible(true);
			method_VelocityServerConnection_getPlayer = VelocityServerConnection.class.getMethod("getPlayer");
			method_VelocityServerConnection_getServerInfo = VelocityServerConnection.class.getMethod("getServerInfo");
			class_Endpoint = Class.forName("com.velocitypowered.proxy.network.Endpoint");
			method_Endpoint_getChannel = class_Endpoint.getMethod("getChannel");
			method_Endpoint_getType = class_Endpoint.getMethod("getType");
			class_ServerChannelInitializerHolder = Class
					.forName("com.velocitypowered.proxy.network.ServerChannelInitializerHolder");
			method_ServerChannelInitializerHolder_get = class_ServerChannelInitializerHolder.getMethod("get");
			method_ServerChannelInitializerHolder_set = class_ServerChannelInitializerHolder.getMethod("set",
					ChannelInitializer.class);
			class_BackendChannelInitializerHolder = Class
					.forName("com.velocitypowered.proxy.network.BackendChannelInitializerHolder");
			method_BackendChannelInitializerHolder_get = class_BackendChannelInitializerHolder.getMethod("get");
			method_BackendChannelInitializerHolder_set = class_BackendChannelInitializerHolder.getMethod("set",
					ChannelInitializer.class);
			method_ChannelInitializer_initChannel = ChannelInitializer.class.getDeclaredMethod("initChannel",
					Channel.class);
			method_ChannelInitializer_initChannel.setAccessible(true);
		} catch (ReflectiveOperationException ex) {
			throw new ExceptionInInitializerError(ex);
		}
	}

	private static MinecraftConnection getMinecraftConnection(InboundConnection connection) {
		if (connection instanceof VelocityInboundConnection conn) {
			return conn.getConnection();
		} else if (class_LoginInboundConnection.isAssignableFrom(connection.getClass())) {
			try {
				return (MinecraftConnection) method_LoginInboundConnection_delegatedConnection.invoke(connection);
			} catch (ReflectiveOperationException e) {
				throw Util.propagateReflectThrowable(e);
			}
		} else {
			throw new RuntimeException("Unknown InboundConnection type: " + connection.getClass().getName());
		}
	}

	private static MinecraftConnection getBackendConnection(ServerConnection connection) {
		if (connection instanceof VelocityServerConnection conn) {
			return conn.getConnection();
		} else {
			throw new RuntimeException("Unknown ServerConnection type: " + connection.getClass().getName());
		}
	}

	public static void disconnectInbound(InboundConnection connection) {
		disconnectMinecraftConnection(getMinecraftConnection(connection));
	}

	public static void disconnectInbound(InboundConnection connection, Component kickMessage) {
		disconnectMinecraftConnection(getMinecraftConnection(connection), kickMessage);
	}

	public static void disconnectPlayerQuiet(Player connection) {
		disconnectMinecraftConnection(getMinecraftConnection(connection));
	}

	private static void disconnectMinecraftConnection(Object minecraftConnection) {
		((MinecraftConnection) minecraftConnection).close();
	}

	private static void disconnectMinecraftConnection(Object minecraftConnection, Component kickMessage) {
		MinecraftConnection conn = (MinecraftConnection) minecraftConnection;
		try {
			conn.closeWith(method_DisconnectPacket_create.invoke(null, kickMessage, conn.getProtocolVersion(),
					method_MinecraftConnection_getState.invoke(minecraftConnection)));
		} catch (ReflectiveOperationException ex) {
			throw Util.propagateReflectThrowable(ex);
		}
	}

	public static Channel getInboundChannel(InboundConnection connection) {
		return getMinecraftConnection(connection).getChannel();
	}

	public static int getInboundProtocolVersion(Player player) {
		return getMinecraftConnection(player).getProtocolVersion().getProtocol();
	}

	public static boolean setInboundProtocolVersion(Player player, int protocol) {
		ProtocolVersion version = ProtocolVersion.getProtocolVersion(protocol);
		if (!version.isSupported()) {
			return false;
		}
		try {
			field_MinecraftConnection_protocolVersion.set(getMinecraftConnection(player), version);
			return true;
		} catch (IllegalArgumentException | IllegalAccessException e) {
			throw Util.propagateReflectThrowable(e);
		}
	}

	public static boolean setViaBackendProtocol(ProxyServer server, String serverName, int clientProtocol,
			int backendProtocol, IPlatformLogger logger) {
		PluginContainer container = server.getPluginManager().getPlugin("viaversion").orElse(null);
		if (container == null) {
			return false;
		}
		Object plugin = container.getInstance().orElse(null);
		if (plugin == null) {
			return false;
		}
		try {
			if (clientProtocol != backendProtocol) {
				ClassLoader loader = plugin.getClass().getClassLoader();
				Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via", true, loader);
				Object manager = viaClass.getMethod("getManager").invoke(null);
				Object protocolManager = manager.getClass().getMethod("getProtocolManager").invoke(manager);
				Class<?> protocolVersionClass = Class.forName(
						"com.viaversion.viaversion.api.protocol.version.ProtocolVersion", true, loader);
				Method getProtocol = protocolVersionClass.getMethod("getProtocol", int.class);
				Object clientVersion = getProtocol.invoke(null, Integer.valueOf(clientProtocol));
				Object backendVersion = getProtocol.invoke(null, Integer.valueOf(backendProtocol));
				Object path = protocolManager.getClass().getMethod("getProtocolPath", protocolVersionClass,
						protocolVersionClass).invoke(protocolManager, clientVersion, backendVersion);
				if (path == null) {
					logger.warn("[compat] ViaVersion has no translation path from " + clientProtocol + " to "
							+ backendProtocol + " for " + serverName);
					return false;
				}
			}
			Object detector = plugin.getClass().getMethod("protocolDetectorService").invoke(plugin);
			detector.getClass().getMethod("setProtocolVersion", String.class, int.class)
					.invoke(detector, serverName, Integer.valueOf(backendProtocol));
			return true;
		} catch (ReflectiveOperationException | LinkageError ex) {
			logger.warn("[compat] Failed to update ViaVersion protocol detector for " + serverName, ex);
			return false;
		}
	}

	public static void updateRealAddress(Object o, SocketAddress addr) {
		if (o instanceof MinecraftConnection) {
			try {
				field_MinecraftConnection_remoteAddress.set(o, addr);
			} catch (IllegalArgumentException | IllegalAccessException e) {
				throw Util.propagateReflectThrowable(e);
			}
		}
	}

	public interface IListenerInitHandler {
		void init(IEaglerXServerListener listener, Channel channel);
	}

	private static final AttributeKey<IEaglerXServerListener> EAGLER_LISTENER = AttributeKey.valueOf("eagler$3");

	private static class VelocityEaglerChannelInitializer extends ChannelInitializer<Channel> {

		protected Consumer<Channel> impl;

		protected VelocityEaglerChannelInitializer(Consumer<Channel> impl) {
			this.impl = impl;
		}

		@Override
		protected void initChannel(Channel var1) throws Exception {
			impl.accept(var1);
		}

	}

	public interface IBackendProtocolResolver {
		Integer getBackendProtocolOverride(Player player, String serverName);
	}

	private static final String EAGLER_BACKEND_COMPAT = "eagler$backendCompat";

	private static class VelocityBackendCompatHandler extends ChannelDuplexHandler {

		private final IBackendProtocolResolver resolver;
		private final IPlatformLogger logger;
		private final boolean debugConnections;

		private VelocityBackendCompatHandler(IBackendProtocolResolver resolver, IPlatformLogger logger,
				boolean debugConnections) {
			this.resolver = resolver;
			this.logger = logger;
			this.debugConnections = debugConnections;
		}

		private void trace(String msg) {
			if (debugConnections && logger != null) {
				logger.info("[trace] " + msg);
			}
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
			trace("backend read packet=" + msg.getClass().getName() + " channel=" + ctx.channel());
			super.channelRead(ctx, msg);
		}

		@Override
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
			trace("backend write packet=" + msg.getClass().getName() + " channel=" + ctx.channel());
			if (isBackendHandshakeOrLoginPacket(msg)) {
				MinecraftConnection mc = getBackendMinecraftConnection(ctx.channel());
				if (mc != null) {
					Object assoc = method_MinecraftConnection_getAssociation.invoke(mc);
					if (assoc instanceof VelocityServerConnection serverConn) {
						Player player = (Player) method_VelocityServerConnection_getPlayer.invoke(serverConn);
						Object serverInfo = method_VelocityServerConnection_getServerInfo.invoke(serverConn);
						String serverName = (String) serverInfo.getClass().getMethod("getName").invoke(serverInfo);
						Integer override = resolver.getBackendProtocolOverride(player, serverName);
						if (override != null) {
							ProtocolVersion version = ProtocolVersion.getProtocolVersion(override.intValue());
							if (version.isSupported()) {
									trace("backend handshake override server=" + serverName + " protocol=" + version.getProtocol()
										+ " packet=" + msg.getClass().getName());
								if (isVelocityClass(msg, "com.velocitypowered.proxy.protocol.packet.HandshakePacket")) {
									msg.getClass().getMethod("setProtocolVersion", ProtocolVersion.class).invoke(msg, version);
								}
								if (mc.getProtocolVersion() != version) {
									method_MinecraftConnection_setProtocolVersion.invoke(mc, version);
								}
							}
						}
					}
				}
			}
			super.write(ctx, msg, promise);
		}

	}

	private static MinecraftConnection getBackendMinecraftConnection(Channel channel) {
		ChannelHandler handler = channel.pipeline().get("handler");
		return handler instanceof MinecraftConnection mc ? mc : null;
	}

	private static boolean isBackendHandshakeOrLoginPacket(Object msg) {
		return isVelocityClass(msg, "com.velocitypowered.proxy.protocol.packet.HandshakePacket")
				|| isVelocityClass(msg, "com.velocitypowered.proxy.protocol.packet.ServerLoginPacket");
	}

	private static boolean isVelocityClass(Object msg, String className) {
		return msg != null && msg.getClass().getName().equals(className);
	}

	@SuppressWarnings("unchecked")
	public static Runnable injectChannelInitializer(ProxyServer server,
			Collection<IEaglerXServerListener> listenersList, IListenerInitHandler initHandler) {
		try {
			Object cm = field_VelocityServer_cm.get(server);
			Object holder = method_ConnectionManager_getServerChannelInitializer.invoke(cm);
			ChannelInitializer<Channel> parent = (ChannelInitializer<Channel>) method_ServerChannelInitializerHolder_get
					.invoke(holder);
			VelocityEaglerChannelInitializer impl = new VelocityEaglerChannelInitializer((ch) -> {
				try {
					method_ChannelInitializer_initChannel.invoke(parent, ch);
				} catch (ReflectiveOperationException e) {
					throw Util.propagateReflectThrowable(e);
				}
				Channel pc = ch.parent();
				if (pc != null) {
					IEaglerXServerListener listener = pc.attr(EAGLER_LISTENER).get();
					if (listener != null) {
						initHandler.init(listener, ch);
					}
				}
			});
			method_ServerChannelInitializerHolder_set.invoke(holder, impl);
			injectListenerAttrs(cm, new ListenerInitList(listenersList));
			return () -> {
				impl.impl = (ch) -> {
					try {
						method_ChannelInitializer_initChannel.invoke(parent, ch);
					} catch (ReflectiveOperationException e) {
						throw Util.propagateReflectThrowable(e);
					}
				};
				try {
					ChannelInitializer<Channel> self = (ChannelInitializer<Channel>) method_ServerChannelInitializerHolder_get
							.invoke(holder);
					if (self == impl) {
						method_ServerChannelInitializerHolder_set.invoke(holder, parent);
					}
				} catch (ReflectiveOperationException e) {
					throw Util.propagateReflectThrowable(e);
				}
			};
		} catch (ReflectiveOperationException e) {
			throw Util.propagateReflectThrowable(e);
		}
	}

	@SuppressWarnings("unchecked")
	public static Runnable injectBackendChannelInitializer(ProxyServer server, IBackendProtocolResolver resolver,
			boolean debugConnections, IPlatformLogger logger) {
		try {
			Object cm = field_VelocityServer_cm.get(server);
			Object holder = method_ConnectionManager_getBackendChannelInitializer.invoke(cm);
			ChannelInitializer<Channel> parent = (ChannelInitializer<Channel>) method_BackendChannelInitializerHolder_get
					.invoke(holder);
			VelocityEaglerChannelInitializer impl = new VelocityEaglerChannelInitializer((ch) -> {
				try {
					method_ChannelInitializer_initChannel.invoke(parent, ch);
				} catch (ReflectiveOperationException e) {
					throw Util.propagateReflectThrowable(e);
				}
				if (ch.pipeline().get(EAGLER_BACKEND_COMPAT) == null) {
					if (ch.pipeline().get("handler") != null) {
						ch.pipeline().addBefore("handler", EAGLER_BACKEND_COMPAT,
								new VelocityBackendCompatHandler(resolver, logger, debugConnections));
					} else {
						ch.pipeline().addLast(EAGLER_BACKEND_COMPAT,
								new VelocityBackendCompatHandler(resolver, logger, debugConnections));
					}
				}
			});
			method_BackendChannelInitializerHolder_set.invoke(holder, impl);
			return () -> {
				impl.impl = (ch) -> {
					try {
						method_ChannelInitializer_initChannel.invoke(parent, ch);
					} catch (ReflectiveOperationException e) {
						throw Util.propagateReflectThrowable(e);
					}
				};
				try {
					ChannelInitializer<Channel> self = (ChannelInitializer<Channel>) method_BackendChannelInitializerHolder_get
							.invoke(holder);
					if (self == impl) {
						method_BackendChannelInitializerHolder_set.invoke(holder, parent);
					}
				} catch (ReflectiveOperationException e) {
					throw Util.propagateReflectThrowable(e);
				}
			};
		} catch (ReflectiveOperationException e) {
			throw Util.propagateReflectThrowable(e);
		}
	}

	@SuppressWarnings("rawtypes")
	public static void injectListenerAttrs(Object cm, ListenerInitList initList) throws ReflectiveOperationException {
		Object obj = field_ConnectionManager_endpoints.get(cm);
		if (obj instanceof Multimap) {
			Multimap impl = (Multimap) obj;
			for (Object endpoint : impl.values()) {
				injectListenerAttr(endpoint, initList);
			}
			obj = new ForwardingMultimap() {
				@Override
				protected Multimap delegate() {
					return impl;
				}
				@Override
				@SuppressWarnings("unchecked")
				public boolean put(Object key, Object value) {
					if (super.put(key, value)) {
						try {
							injectListenerAttr(value, initList);
						} catch (ReflectiveOperationException e) {
							throw Util.propagateReflectThrowable(e);
						}
						return true;
					} else {
						return false;
					}
				}
			};
		} else {
			Map impl = (Map) obj;
			for (Object endpoint : impl.values()) {
				injectListenerAttr(endpoint, initList);
			}
			obj = new ForwardingMap() {
				@Override
				protected Map delegate() {
					return impl;
				}
				@Override
				@SuppressWarnings("unchecked")
				public Object put(Object key, Object value) {
					Object r = super.put(key, value);
					if (r != value) {
						try {
							injectListenerAttr(value, initList);
						} catch (ReflectiveOperationException e) {
							throw Util.propagateReflectThrowable(e);
						}
					}
					return r;
				}
			};
		}
		field_ConnectionManager_endpoints.set(cm, obj);
	}

	private static void injectListenerAttr(Object endpoint, ListenerInitList listenersToInit)
			throws ReflectiveOperationException {
		ListenerType type = (ListenerType) method_Endpoint_getType.invoke(endpoint);
		if (type == ListenerType.MINECRAFT) {
			Channel ch = (Channel) method_Endpoint_getChannel.invoke(endpoint);
			IEaglerXServerListener listener = listenersToInit.offer(ch.localAddress());
			if (listener != null) {
				Attribute<IEaglerXServerListener> attr = ch.attr(EAGLER_LISTENER);
				if (attr.getAndSet(listener) != listener) {
					listener.reportVelocityInjected(ch);
				}
			}
		}
	}

	public static void cloneListener(ProxyServer server, SocketAddress cloneListenerAddress) {
		try {
			method_ConnectionManager_bind.invoke(field_VelocityServer_cm.get(server), cloneListenerAddress);
		} catch (ReflectiveOperationException ex) {
			throw Util.propagateReflectThrowable(ex);
		}
	}

	public static void injectCompressionDisable(ProxyServer server, Player player) {
		// Note: This does not affect the MinecraftConnection in the pipeline or player
		// object therefore performance is not a concern
		try {
			Object o = field_MinecraftConnection_activeSessionHandler.get(getMinecraftConnection(player));
			if (class_AuthSessionHandler.isAssignableFrom(o.getClass())) {
				final MinecraftConnection parent = (MinecraftConnection) field_AuthSessionHandler_mcConnection.get(o);
				field_AuthSessionHandler_mcConnection.set(o, classProxy_MinecraftConnection.createProxy(
						ctor_MinecraftConnection, new Object[] { parent.getChannel(), server }, (obj, meth, args) -> {
							if ("setCompressionThreshold".equals(meth.getName())) {
								// FUCK YOU!
								return null;
							}
							return meth.invoke(parent, args);
						}));
			} else {
				throw new RuntimeException("Unexpected session handler type: " + o.getClass().getName());
			}
		} catch (ReflectiveOperationException e) {
			throw Util.propagateReflectThrowable(e);
		}
	}

	public static void sendDataClient(InboundConnection connection, String channel, byte[] data) {
		getMinecraftConnection(connection).write(new PluginMessagePacket(channel, Unpooled.wrappedBuffer(data)));
	}

	public static void sendDataBackend(ServerConnection connection, String channel, byte[] data) {
		getBackendConnection(connection).write(new PluginMessagePacket(channel, Unpooled.wrappedBuffer(data)));
	}

	public static EventLoopGroup getBossEventLoopGroup(ProxyServer proxyIn) {
		try {
			return (EventLoopGroup) field_ConnectionManager_bossGroup.get(field_VelocityServer_cm.get(proxyIn));
		} catch (ReflectiveOperationException e) {
			throw Util.propagateReflectThrowable(e);
		}
	}

	public static EventLoopGroup getWorkerEventLoopGroup(ProxyServer proxyIn) {
		try {
			return (EventLoopGroup) field_ConnectionManager_workerGroup.get(field_VelocityServer_cm.get(proxyIn));
		} catch (ReflectiveOperationException e) {
			throw Util.propagateReflectThrowable(e);
		}
	}

	public static ChannelFactory<? extends Channel> getChannelFactory(ProxyServer proxyIn) {
		try {
			return (ChannelFactory<? extends Channel>) field_TransportType_socketChannelFactory
					.get(field_ConnectionManager_transportType.get(field_VelocityServer_cm.get(proxyIn)));
		} catch (ReflectiveOperationException e) {
			throw Util.propagateReflectThrowable(e);
		}
	}

	public static ChannelFactory<? extends Channel> getUnixChannelFactory(ProxyServer proxyIn) {
		return null;
	}

	public static ChannelFactory<? extends ServerChannel> getServerChannelFactory(ProxyServer proxyIn) {
		try {
			return (ChannelFactory<? extends ServerChannel>) field_TransportType_serverSocketChannelFactory
					.get(field_ConnectionManager_transportType.get(field_VelocityServer_cm.get(proxyIn)));
		} catch (ReflectiveOperationException e) {
			throw Util.propagateReflectThrowable(e);
		}
	}

	public static ChannelFactory<? extends ServerChannel> getServerUnixChannelFactory(ProxyServer proxyIn) {
		return null;
	}

}
