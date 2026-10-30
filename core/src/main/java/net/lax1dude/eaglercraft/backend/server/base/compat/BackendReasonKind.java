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

package net.lax1dude.eaglercraft.backend.server.base.compat;

/**
 * Who authored the disconnect reason that came back from a backend attempt.
 * 
 * This distinction is the single most useful signal when deciding whether
 * retrying with another protocol version could possibly help:
 * 
 * <ul>
 * <li>{@link #BACKEND_AUTHORED} means the backend deliberately kicked the player
 * and told them why (whitelist, ban, full, plugin message). Cycling protocol
 * versions cannot change that answer, and doing so generates duplicate join
 * attempts. The message is the player's answer and must be shown unchanged.</li>
 * <li>{@link #TRANSPORT_GENERIC} means the platform supplied its own
 * connection-failure message because the backend never got far enough to author
 * one. A protocol mismatch is the classic cause, so this is the only
 * disconnected-with-a-reason case that may be retried.</li>
 * <li>{@link #NONE} means the backend disconnected without any reason at
 * all.</li>
 * </ul>
 */
public enum BackendReasonKind {

	/** No reason component was supplied. */
	NONE,

	/** The backend itself authored the message the player was given. */
	BACKEND_AUTHORED,

	/** The platform generated a generic "the backend connection failed" message. */
	TRANSPORT_GENERIC

}
