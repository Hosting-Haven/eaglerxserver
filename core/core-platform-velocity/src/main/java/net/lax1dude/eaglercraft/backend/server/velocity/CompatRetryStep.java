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

import net.lax1dude.eaglercraft.backend.server.base.compat.CompatFailure;

/**
 * What to do after one failed backend attempt.
 * 
 * Keeping this decision in one place means the retry rules are exercised by
 * tests directly instead of being rebuilt (and drifting) inside them.
 */
final class CompatRetryStep {

	/** Whether another attempt will be made. */
	final boolean retry;

	/** Index into the protocol plan for the next attempt. */
	final int attemptIndex;

	/** How many same-protocol attempts have already been spent. */
	final int transientRetries;

	private CompatRetryStep(boolean retry, int attemptIndex, int transientRetries) {
		this.retry = retry;
		this.attemptIndex = attemptIndex;
		this.transientRetries = transientRetries;
	}

	static CompatRetryStep stop() {
		return new CompatRetryStep(false, -1, 0);
	}

	/**
	 * Decide the next step.
	 * 
	 * @param failure          classification of the attempt that just failed
	 * @param attemptIndex     index of the attempt that just failed
	 * @param transientRetries same-protocol retries already spent
	 * @param planLength       number of protocol versions available
	 * @param maxProtocolRetries maximum number of protocol advances
	 * @param maxTransientRetries maximum number of same-protocol re-attempts
	 */
	static CompatRetryStep next(CompatFailure failure, int attemptIndex, int transientRetries, int planLength,
			int maxProtocolRetries, int maxTransientRetries) {
		if (!failure.isRetryable()) {
			return stop();
		}

		if (failure.advancesProtocol()) {
			int next = attemptIndex + 1;

			if (next >= planLength || next > maxProtocolRetries) {
				return stop();
			}

			return new CompatRetryStep(true, next, 0);
		}

		// A transient transport failure is re-attempted on the same protocol.
		if (transientRetries >= maxTransientRetries) {
			return stop();
		}

		return new CompatRetryStep(true, attemptIndex, transientRetries + 1);
	}

	@Override
	public String toString() {
		return retry ? "retry(attemptIndex=" + attemptIndex + ", transient=" + transientRetries + ")" : "stop";
	}

}
