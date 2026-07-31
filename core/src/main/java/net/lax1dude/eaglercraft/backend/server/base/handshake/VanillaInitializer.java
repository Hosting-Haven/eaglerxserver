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

package net.lax1dude.eaglercraft.backend.server.base.handshake;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.lax1dude.eaglercraft.backend.server.base.EaglerXServer;
import net.lax1dude.eaglercraft.backend.server.base.NettyPipelineData;
import net.lax1dude.eaglercraft.backend.server.base.pipeline.BufferUtils;
import net.lax1dude.eaglercraft.backend.server.base.pipeline.WebSocketEaglerInitialHandler;

public class VanillaInitializer {

	protected final EaglerXServer<?> server;
	protected final NettyPipelineData pipelineData;
	protected final WebSocketEaglerInitialHandler inboundHandler;
	protected final List<ByteBuf> bufferedPackets;

	private static final int STATE_PRE = 0;
	private static final int STATE_SENT_LOGIN = 1;
	private static final int STATE_STALLING = 2;
	private static final int STATE_COMPLETE = 3;
	private static final int MAX_PROTOCOL_RETRIES = 4;
	private static final int[] PROTOCOL_LADDER = new int[] { 47, 107, 109, 110, 210, 315, 316, 335, 338, 340, 393,
			401, 404, 477, 480, 485, 490, 498, 573, 575, 578, 735, 736, 751, 753, 754, 755, 756, 757, 758,
			759, 760, 761, 762, 763, 764, 765, 766, 767, 768, 769 };
	private static final int[] COMPATIBILITY_FALLBACKS = new int[] { 754, 340, 47 };

	private int connectionState = STATE_PRE;
	private int backendMinecraftProtocol = -1;
	private int backendProtocolOverride = Integer.MIN_VALUE;
	private int retryCount = 0;
	private final List<Integer> triedProtocols = new ArrayList<>(6);

	public VanillaInitializer(EaglerXServer<?> server, NettyPipelineData pipelineData,
			WebSocketEaglerInitialHandler inboundHandler) {
		this.server = server;
		this.pipelineData = pipelineData;
		this.inboundHandler = inboundHandler;
		this.bufferedPackets = new LinkedList<>();
	}

	public void init(ChannelHandlerContext ctx) {
		if (backendProtocolOverride == Integer.MIN_VALUE) {
			backendProtocolOverride = getConfiguredBackendMinecraftProtocolOverride();
		}
		if (backendMinecraftProtocol <= 0) {
			backendMinecraftProtocol = getEffectiveBackendMinecraftProtocol();
		}
		markTriedProtocol(backendMinecraftProtocol);

		// C00Handshake
		ByteBuf buffer = ctx.alloc().buffer();
		try {
			BufferUtils.writeVarInt(buffer, 0x00);
			BufferUtils.writeVarInt(buffer, backendMinecraftProtocol);
			String ip = pipelineData.headerHost;
			int port = 65535;
			if (ip == null) {
				ip = "127.0.0.1";
			} else {
				int i = ip.lastIndexOf(':');
				if (i != -1 && i < ip.length() - 1) {
					try {
						port = Integer.parseInt(ip.substring(i + 1));
						ip = ip.substring(0, i);
					} catch (NumberFormatException ex) {
					}
				}
				if (ip.length() > 255) {
					ip = ip.substring(0, 255);
				}
			}
			BufferUtils.writeMCString(buffer, ip, 255);
			buffer.writeShort(port);
			BufferUtils.writeVarInt(buffer, 2);
			ctx.fireChannelRead(buffer.retain());
		} finally {
			buffer.release();
		}

		if (inboundHandler.terminated || !ctx.channel().isActive()) {
			return;
		}

		connectionState = STATE_SENT_LOGIN;

		// C00PacketLoginStart
		buffer = ctx.alloc().buffer();
		try {
			BufferUtils.writeVarInt(buffer, 0x00);
			BufferUtils.writeMCString(buffer, pipelineData.username, 16);
			if (backendMinecraftProtocol >= 764) {
				buffer.writeLong(pipelineData.uuid.getMostSignificantBits());
				buffer.writeLong(pipelineData.uuid.getLeastSignificantBits());
			}
			ctx.fireChannelRead(buffer.retain());
		} finally {
			buffer.release();
		}

	}

	public void handleInbound(ChannelHandlerContext ctx, ByteBuf msg) {
		try {
			msg.markReaderIndex();
			int pktId = BufferUtils.readVarInt(msg, 3);
			if (connectionState == STATE_PRE) {
				if (pktId == 0x00) {
					// S00PacketDisconnect
					handleKickPacket(ctx, msg);
				} else if (pktId == 0x01) {
					// S01PacketEncryptionRequest
					inboundHandler.terminateErrorCode(ctx, pipelineData.handshakeProtocol,
							HandshakePacketTypes.SERVER_ERROR_CUSTOM_MESSAGE, HandshakePacketTypes.MSG_ONLINE_MODE);
				} else {
					inboundHandler.terminateInternalError(ctx, pipelineData.handshakeProtocol);
					pipelineData.connectionLogger.error("Disconnecting, server sent unexpected packet " + pktId);
				}
			} else if (connectionState == STATE_SENT_LOGIN) {
				switch (pktId) {
				case 0x00:
					// S00PacketDisconnect
					handleKickPacket(ctx, msg);
					break;
				case 0x01:
					// S01PacketEncryptionRequest
					inboundHandler.terminateErrorCode(ctx, pipelineData.handshakeProtocol,
							HandshakePacketTypes.SERVER_ERROR_CUSTOM_MESSAGE, HandshakePacketTypes.MSG_ONLINE_MODE);
					break;
				case 0x02:
					connectionState = STATE_STALLING;
					// S02PacketLoginSuccess
					UUID playerUUID;
					String usernameStr;
					int mcProto = backendMinecraftProtocol;
					msg.markReaderIndex();
					try {
						if (mcProto >= 735) {
							playerUUID = new UUID(msg.readLong(), msg.readLong());
							usernameStr = BufferUtils.readMCString(msg, 16);
							if (mcProto >= 759) {
								int propCount = BufferUtils.readVarInt(msg, 5);
								for (int j = 0; j < propCount; ++j) {
									msg.skipBytes(BufferUtils.readVarInt(msg, 5));
									msg.skipBytes(BufferUtils.readVarInt(msg, 5));
									if (msg.readBoolean()) {
										msg.skipBytes(BufferUtils.readVarInt(msg, 5));
									}
								}
							}
							if (mcProto >= 766 && msg.isReadable()) {
								msg.readBoolean();
							}
						} else {
							String uuidStr = BufferUtils.readMCString(msg, 36);
							playerUUID = UUID.fromString(uuidStr);
							usernameStr = BufferUtils.readMCString(msg, 16);
						}
					} catch (IndexOutOfBoundsException | IllegalArgumentException ex) {
						msg.resetReaderIndex();
						String uuidStr = BufferUtils.readMCString(msg, 64);
						playerUUID = UUID.fromString(uuidStr);
						usernameStr = BufferUtils.readMCString(msg, 16);
					}
					if (retryCount > 0) {
						pipelineData.connectionLogger.info("Backend protocol fallback succeeded with protocol "
								+ backendMinecraftProtocol + " after trying " + formatTriedProtocols());
					}
					inboundHandler.handleBackendHandshakeSuccess(ctx, usernameStr, playerUUID);
					break;
				case 0x03:
					// S03PacketEnableCompression
					break;
				case 0x3F:
					// S3FPacketCustomPayload
					msg.resetReaderIndex();
					bufferedPackets.add(msg.retain());
					break;
				default:
					inboundHandler.terminateInternalError(ctx, pipelineData.handshakeProtocol);
					pipelineData.connectionLogger
							.error("Disconnecting, server sent unknown packet " + pktId + " while handshaking");
					break;
				}
			} else if (connectionState == STATE_STALLING) {
				if (pktId == 0x40) {
					// S40PacketDisconnect
					handleKickPacket(ctx, msg);
				} else {
					msg.resetReaderIndex();
					bufferedPackets.add(msg.retain());
				}
			} else {
				pipelineData.connectionLogger
						.error("Disconnecting, server sent unexpected packet " + pktId + " in unknown state");
				inboundHandler.terminateInternalError(ctx, pipelineData.handshakeProtocol);
			}
		} catch (IndexOutOfBoundsException ex) {
			ex.printStackTrace();
			inboundHandler.terminateInternalError(ctx, pipelineData.handshakeProtocol);
		}
	}

	public int getBackendMinecraftProtocol() {
		return backendMinecraftProtocol > 0 ? backendMinecraftProtocol : pipelineData.minecraftProtocol;
	}

	private int getConfiguredBackendMinecraftProtocolOverride() {
		if (pipelineData.listenerInfo == null) {
			return -1;
		}
		return pipelineData.listenerInfo.getConfigData().getBackendMinecraftProtocolOverride();
	}

	private int getEffectiveBackendMinecraftProtocol() {
		if (backendProtocolOverride > 0) {
			return backendProtocolOverride;
		}
		return pipelineData.minecraftProtocol;
	}

	private void handleKickPacket(ChannelHandlerContext ctx, ByteBuf data) {
		String pkt = BufferUtils.readMCString(data, 32767);
		if (tryRetryWithDifferentProtocol(ctx, pkt)) {
			return;
		}
		if (retryCount > 0) {
			pipelineData.connectionLogger.warn("Backend protocol fallback failed after trying "
					+ formatTriedProtocols() + ", final disconnect: " + pkt);
		}
		inboundHandler.terminateErrorCode(ctx, pipelineData.handshakeProtocol,
				HandshakePacketTypes.SERVER_ERROR_CUSTOM_MESSAGE, pkt);
		connectionState = STATE_COMPLETE;
	}

	private boolean tryRetryWithDifferentProtocol(ChannelHandlerContext ctx, String kickMessage) {
		if (!canAutoRetryProtocol()) {
			return false;
		}
		int nextProtocol = selectNextRetryProtocol(kickMessage);
		if (nextProtocol <= 0) {
			return false;
		}
		++retryCount;
		backendMinecraftProtocol = nextProtocol;
		connectionState = STATE_PRE;
		pipelineData.connectionLogger
				.info("Backend rejected protocol " + getLastTriedProtocol() + ", retrying handshake with protocol "
						+ nextProtocol + " (attempt " + retryCount + " of " + MAX_PROTOCOL_RETRIES + ")");
		init(ctx);
		return true;
	}

	private boolean canAutoRetryProtocol() {
		return connectionState == STATE_SENT_LOGIN && backendProtocolOverride <= 0 && retryCount < MAX_PROTOCOL_RETRIES;
	}

	private int selectNextRetryProtocol(String kickMessage) {
		int current = getBackendMinecraftProtocol();
		String msgLower = kickMessage == null ? "" : kickMessage.toLowerCase();
		boolean likelyNewerThanServer = msgLower.contains("outdated server") || msgLower.contains("newer version")
				|| msgLower.contains("too new") || msgLower.contains("{0}");
		boolean likelyOlderThanServer = msgLower.contains("outdated client") || msgLower.contains("older version")
				|| msgLower.contains("too old");
		boolean likelyVersionMismatch = msgLower.contains("outdated") || msgLower.contains("incompatible")
				|| msgLower.contains("version") || msgLower.contains("protocol") || msgLower.contains("{0}");

		if (!likelyVersionMismatch) {
			return -1;
		}

		if (likelyNewerThanServer && !likelyOlderThanServer) {
			int lower = findLowerUntestedProtocol(current);
			if (lower > 0) {
				return lower;
			}
		}

		if (likelyOlderThanServer && !likelyNewerThanServer) {
			int higher = findHigherUntestedProtocol(current);
			if (higher > 0) {
				return higher;
			}
		}

		int lower = findLowerUntestedProtocol(current);
		if (lower > 0) {
			return lower;
		}
		int higher = findHigherUntestedProtocol(current);
		if (higher > 0) {
			return higher;
		}
		for (int fallback : COMPATIBILITY_FALLBACKS) {
			if (!isProtocolTried(fallback) && fallback != current) {
				return fallback;
			}
		}
		return -1;
	}

	private int findLowerUntestedProtocol(int current) {
		for (int i = PROTOCOL_LADDER.length - 1; i >= 0; --i) {
			int candidate = PROTOCOL_LADDER[i];
			if (candidate < current && !isProtocolTried(candidate)) {
				return candidate;
			}
		}
		return -1;
	}

	private int findHigherUntestedProtocol(int current) {
		for (int i = 0; i < PROTOCOL_LADDER.length; ++i) {
			int candidate = PROTOCOL_LADDER[i];
			if (candidate > current && !isProtocolTried(candidate)) {
				return candidate;
			}
		}
		return -1;
	}

	private void markTriedProtocol(int protocol) {
		if (!triedProtocols.contains(protocol)) {
			triedProtocols.add(protocol);
		}
	}

	private boolean isProtocolTried(int protocol) {
		return triedProtocols.contains(protocol);
	}

	private int getLastTriedProtocol() {
		if (triedProtocols.isEmpty()) {
			return getBackendMinecraftProtocol();
		}
		return triedProtocols.get(triedProtocols.size() - 1);
	}

	private String formatTriedProtocols() {
		if (triedProtocols.isEmpty()) {
			return "[]";
		}
		StringBuilder sb = new StringBuilder(32);
		sb.append('[');
		for (int i = 0; i < triedProtocols.size(); ++i) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(triedProtocols.get(i));
		}
		sb.append(']');
		return sb.toString();
	}

	public void flushBufferedPackets(ChannelHandlerContext ctx) {
		if (!bufferedPackets.isEmpty()) {
			try {
				for (ByteBuf buf : bufferedPackets) {
					ctx.write(buf.retain());
				}
				ctx.flush();
			} finally {
				release();
			}
		}
	}

	public void release() {
		if (!bufferedPackets.isEmpty()) {
			for (ByteBuf buf : bufferedPackets) {
				buf.release();
			}
			bufferedPackets.clear();
		}
	}

}
