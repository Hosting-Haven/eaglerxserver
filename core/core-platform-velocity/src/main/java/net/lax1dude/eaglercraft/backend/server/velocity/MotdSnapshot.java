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

/**
 * A snapshot of a Java server's status response, in the shape an Eaglercraft
 * MOTD response needs.
 *
 * Immutable on purpose: it is produced off-thread by {@link MotdPing} and read
 * concurrently by the MOTD handler, so it must never be mutated after
 * publication.
 */
final class MotdSnapshot {

        private final List<String> motd;
        private final byte[] icon;
        private final int online;
        private final int max;
        private final List<String> playerList;

        MotdSnapshot(List<String> motd, byte[] icon, int online, int max, List<String> playerList) {
                this.motd = motd == null ? Collections.emptyList() : Collections.unmodifiableList(motd);
                this.icon = icon;
                this.online = online;
                this.max = max;
                this.playerList = playerList == null ? Collections.emptyList()
                                : Collections.unmodifiableList(playerList);
        }

        List<String> getMotd() {
                return motd;
        }

        /** Raw RGBA8 64x64 icon pixels, or null when the Java server has no favicon. */
        byte[] getIcon() {
                return icon;
        }

        int getOnline() {
                return online;
        }

        int getMax() {
                return max;
        }

        List<String> getPlayerList() {
                return playerList;
        }

        boolean hasMotd() {
                return !motd.isEmpty();
        }

}
