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

package net.lax1dude.eaglercraft.backend.server.base;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Maintains the ordered list of Minecraft release protocol versions used for
 * backend handshake retry/fallback negotiation.
 * 
 * The historical portion of the ladder (through 1.16.5, protocol 754) is
 * hardcoded because those releases were not consecutive, leaving gaps of
 * snapshot-only protocol numbers. From protocol 755 (1.17) onward, Java Edition
 * release protocol versions are consecutive integers, so that portion is
 * generated dynamically up to the highest protocol version the server has ever
 * observed from a connecting client. New Minecraft releases are therefore
 * supported automatically without requiring code changes.
 */
public final class ProtocolLadder {

	private static final int[] BASE_LADDER = new int[] { 47, 107, 109, 110, 210, 315, 316, 335, 338, 340, 393,
			401, 404, 477, 480, 485, 490, 498, 573, 575, 578, 735, 736, 751, 753, 754 };

	private static final int CONSECUTIVE_START = 755;

	// Generous upper bound (~40+ years of releases at the current cadence) so a
	// malformed or absurd client protocol number can never cause unbounded
	// allocation.
	private static final int MAX_AUTO_EXTEND = 512;

	private static final AtomicInteger HIGHEST_SEEN = new AtomicInteger(CONSECUTIVE_START - 1);

	private ProtocolLadder() {
	}

	public static void notifyHighestSeen(int protocol) {
		if (protocol < CONSECUTIVE_START) {
			return;
		}
		int capped = Math.min(protocol, CONSECUTIVE_START + MAX_AUTO_EXTEND);
		HIGHEST_SEEN.accumulateAndGet(capped, Math::max);
	}

	public static int[] build(int maxProtocol) {
		int cappedMax = Math.min(Math.max(maxProtocol, CONSECUTIVE_START - 1), CONSECUTIVE_START + MAX_AUTO_EXTEND);
		int extra = Math.max(cappedMax - CONSECUTIVE_START + 1, 0);
		int[] ret = new int[BASE_LADDER.length + extra];
		System.arraycopy(BASE_LADDER, 0, ret, 0, BASE_LADDER.length);
		for (int i = 0; i < extra; ++i) {
			ret[BASE_LADDER.length + i] = CONSECUTIVE_START + i;
		}
		return ret;
	}

	public static int[] buildForConnection(int clientProtocol) {
		return build(Math.max(clientProtocol, HIGHEST_SEEN.get()));
	}

}
