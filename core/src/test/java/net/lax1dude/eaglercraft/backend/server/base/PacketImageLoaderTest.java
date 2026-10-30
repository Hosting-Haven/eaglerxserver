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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import net.lax1dude.eaglercraft.v1_8.socket.protocol.util.PacketImageData;

/**
 * Custom pause menu images are loaded through the 255x255 path. Anything bigger
 * used to be resized and then read back at the original size, which threw
 * ArrayIndexOutOfBoundsException ("Coordinate out of bounds!") and aborted plugin
 * startup in the constructor. These tests pin the resize behaviour.
 */
class PacketImageLoaderTest {

	private static BufferedImage image(int w, int h) {
		return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
	}

	@Test
	void anImageWithinTheLimitIsLeftAlone() {
		PacketImageData data = PacketImageLoader.loadPacketImageData(image(64, 64), 255, 255);

		assertEquals(64, data.width);
		assertEquals(64, data.height);
		assertEquals(64 * 64, data.rgba.length);
	}

	@Test
	void anImageExactlyAtTheLimitIsLeftAlone() {
		PacketImageData data = PacketImageLoader.loadPacketImageData(image(255, 255), 255, 255);

		assertEquals(255, data.width);
		assertEquals(255, data.height);
		assertEquals(255 * 255, data.rgba.length);
	}

	/** The exact case that crashed startup. */
	@Test
	void anOversizedImageIsScaledDownInsteadOfThrowing() {
		PacketImageData data = PacketImageLoader.loadPacketImageData(image(300, 300), 255, 255);

		assertEquals(255, data.width);
		assertEquals(255, data.height);
		assertEquals(255 * 255, data.rgba.length);
	}

	@Test
	void aWideImageKeepsItsAspectRatioAndFits() {
		PacketImageData data = PacketImageLoader.loadPacketImageData(image(800, 200), 255, 255);

		assertEquals(255, data.width);
		assertEquals(64, data.height);
		assertTrue(data.width <= 255 && data.height <= 255);
		assertEquals(800.0 / 200.0, (double) data.width / data.height, 0.05);
	}

	@Test
	void aTallImageKeepsItsAspectRatioAndFits() {
		PacketImageData data = PacketImageLoader.loadPacketImageData(image(200, 800), 255, 255);

		assertEquals(64, data.width);
		assertEquals(255, data.height);
		assertTrue(data.width <= 255 && data.height <= 255);
		assertEquals(200.0 / 800.0, (double) data.width / data.height, 0.05);
	}

	@Test
	void aVeryTallImageStillGetsAtLeastOnePixelWide() {
		PacketImageData data = PacketImageLoader.loadPacketImageData(image(1, 5000), 255, 255);

		assertEquals(255, data.height);
		assertTrue(data.width >= 1, "width must not round down to zero");
	}

	@Test
	void anUndecodableImageSaysSo() {
		IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
				() -> PacketImageLoader.loadPacketImageData((BufferedImage) null, 255, 255));

		assertTrue(thrown.getMessage().contains("could not be decoded"));
	}

}
