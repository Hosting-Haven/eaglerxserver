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
 * The classification of one failed backend connection attempt.
 * 
 * {@link #getDetail()} carries technical context for the server log. It
 * intentionally includes things an operator needs (exception class, message) and
 * must never be sent to a player.
 */
public final class CompatFailure {

	private final CompatFailureCategory category;
	private final String detail;

	private CompatFailure(CompatFailureCategory category, String detail) {
		this.category = category;
		this.detail = detail == null ? "" : detail;
	}

	public static CompatFailure of(CompatFailureCategory category) {
		return new CompatFailure(category, "");
	}

	public static CompatFailure of(CompatFailureCategory category, String detail) {
		return new CompatFailure(category, detail);
	}

	public CompatFailureCategory getCategory() {
		return category;
	}

	/**
	 * Technical context for logging. Never shown to a player.
	 */
	public String getDetail() {
		return detail;
	}

	public boolean isRetryable() {
		return category.isRetryable();
	}

	public boolean advancesProtocol() {
		return category.advancesProtocol();
	}

	@Override
	public String toString() {
		return detail.isEmpty() ? category.name() : category.name() + " (" + detail + ")";
	}

}
