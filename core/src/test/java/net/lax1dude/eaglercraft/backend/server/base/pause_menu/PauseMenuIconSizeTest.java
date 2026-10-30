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

package net.lax1dude.eaglercraft.backend.server.base.pause_menu;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import net.lax1dude.eaglercraft.backend.server.base.PacketImageLoader;
import net.lax1dude.eaglercraft.v1_8.socket.protocol.util.PacketImageData;

/**
 * Custom pause menu images used to be loaded at 255x255 and then handed to a
 * setter that only accepts 64x64, so any oversized file aborted plugin startup
 * with "Invalid image dimensions". The loader and the builder have to agree.
 */
class PauseMenuIconSizeTest {

	private static BufferedImage image(int w, int h) {
		return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
	}

	@Test
	void theBuilderRejectsAnythingAboveTheIconLimit() {
		PacketImageData tooBig = new PacketImageData(65, 65, new int[65 * 65]);

		assertThrows(IllegalArgumentException.class, () -> new PauseMenuBuilder().setMenuIcon("icon", tooBig));
	}

	/** The exact file size that crashed startup: 255x191. */
	@Test
	void anOversizedCustomImageBecomesAnAcceptableIcon() {
		BufferedImage source = image(255, 191);

		PacketImageData icon = PacketImageLoader.loadPacketImageData(source, PauseMenuBuilder.MAX_ICON_DIMENSION,
				PauseMenuBuilder.MAX_ICON_DIMENSION);

		assertTrue(icon.width <= PauseMenuBuilder.MAX_ICON_DIMENSION);
		assertTrue(icon.height <= PauseMenuBuilder.MAX_ICON_DIMENSION);
		assertEquals(64, icon.width);
		assertEquals(48, icon.height);

		assertDoesNotThrow(() -> new PauseMenuBuilder().setMenuIcon("icon", icon));
	}

	@Test
	void aSquareCustomImageBecomesASquareIcon() {
		PacketImageData icon = PacketImageLoader.loadPacketImageData(image(512, 512),
				PauseMenuBuilder.MAX_ICON_DIMENSION, PauseMenuBuilder.MAX_ICON_DIMENSION);

		assertEquals(64, icon.width);
		assertEquals(64, icon.height);
		assertDoesNotThrow(() -> new PauseMenuBuilder().setMenuIcon("icon", icon));
	}

	@Test
	void anImageAlreadySmallEnoughIsPassedThrough() {
		PacketImageData icon = PacketImageLoader.loadPacketImageData(image(32, 32),
				PauseMenuBuilder.MAX_ICON_DIMENSION, PauseMenuBuilder.MAX_ICON_DIMENSION);

		assertEquals(32, icon.width);
		assertEquals(32, icon.height);
		assertDoesNotThrow(() -> new PauseMenuBuilder().setMenuIcon("icon", icon));
	}

}
