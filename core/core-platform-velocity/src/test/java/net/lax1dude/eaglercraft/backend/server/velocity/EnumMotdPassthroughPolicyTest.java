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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.lax1dude.eaglercraft.backend.server.api.EnumMotdPassthroughPolicy;

/**
 * The listeners.toml surface of the eligibility setting.
 *
 * Parsing lives in the enum rather than in the loader so the accepted tokens, and
 * the behaviour on a bad value, can be pinned without loading a configuration
 * file. Lives in this module because it is the one that already runs JUnit.
 */
class EnumMotdPassthroughPolicyTest {

        @Test
        void recognisesEveryDocumentedToken() {
                assertEquals(EnumMotdPassthroughPolicy.ALL, EnumMotdPassthroughPolicy.parse("all"));
                assertEquals(EnumMotdPassthroughPolicy.SELECTED, EnumMotdPassthroughPolicy.parse("selected"));
                assertEquals(EnumMotdPassthroughPolicy.NONE, EnumMotdPassthroughPolicy.parse("none"));
        }

        @Test
        void ignoresCaseAndSurroundingSpace() {
                assertEquals(EnumMotdPassthroughPolicy.SELECTED, EnumMotdPassthroughPolicy.parse("  SELECTED  "));
        }

        /**
         * A bad value returns null so the caller can warn and fall back to "all",
         * which is the behaviour every other enum-like setting has.
         */
        @Test
        void rejectsUnknownAndEmptyValues() {
                assertNull(EnumMotdPassthroughPolicy.parse("some"));
                assertNull(EnumMotdPassthroughPolicy.parse(""));
                assertNull(EnumMotdPassthroughPolicy.parse("   "));
                assertNull(EnumMotdPassthroughPolicy.parse(null));
        }

        @Test
        void configNamesRoundTrip() {
                for (EnumMotdPassthroughPolicy policy : EnumMotdPassthroughPolicy.values()) {
                        assertEquals(policy, EnumMotdPassthroughPolicy.parse(policy.configName()));
                }
        }

        @Test
        void diagnosticListsEveryAcceptedToken() {
                String valid = EnumMotdPassthroughPolicy.describeValidValues();

                assertNotNull(valid);
                assertTrue(valid.contains("all"), valid);
                assertTrue(valid.contains("selected"), valid);
                assertTrue(valid.contains("none"), valid);
        }

}
