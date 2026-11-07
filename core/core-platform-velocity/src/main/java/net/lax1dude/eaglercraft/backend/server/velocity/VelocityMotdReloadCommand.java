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

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Applies the persisted MOTD passthrough eligibility without a restart.
 *
 * <p>
 * This is the control surface FoxPanel drives: it is a normal console command, so
 * it arrives over the authenticated Wings command channel that Pterodactyl-style
 * panels already have. No new port is opened and no new credential exists.
 * </p>
 *
 * <p>
 * Deliberately narrow. Only the two eligibility properties are reloadable; ports,
 * bindings, the passthrough target and everything else still require a restart,
 * and this command says so rather than implying otherwise.
 * </p>
 *
 * <p>
 * Runs on the command thread, which is allowed to touch the filesystem. The MOTD
 * path reads the published snapshot and never calls into here.
 * </p>
 */
class VelocityMotdReloadCommand implements SimpleCommand {

	static final String NAME = "eaglerxserver-motd";

	static final String PERMISSION = "eaglercraft.command.motreload";

	private final MotdReloadService service;

	VelocityMotdReloadCommand(MotdReloadService service) {
		this.service = service;
	}

	CommandMeta register(PlatformPluginVelocity plugin) {
		CommandManager manager = plugin.proxy().getCommandManager();
		CommandMeta meta = manager.metaBuilder(NAME).plugin(plugin).build();
		manager.register(meta, this);

		return meta;
	}

	@Override
	public void execute(Invocation invocation) {
		String[] args = invocation.arguments();

		// Optional first argument: the revision the caller believed was active. Sending
		// it turns a concurrent change into a visible rejection instead of a silent
		// overwrite of somebody else's newer policy.
		Long expected = null;

		if (args.length > 0) {
			try {
				expected = Long.valueOf(args[0].trim());
			} catch (NumberFormatException ex) {
				invocation.source().sendMessage(Component
						.text("[EaglerXServer] usage: /" + NAME + " [expectedRevision]")
						.color(NamedTextColor.RED));
				return;
			}
		}

		MotdReloadService.ReloadOutcome outcome = service.reload(expected);

		if (outcome.ok()) {
			invocation.source().sendMessage(Component
					.text("[EaglerXServer] MOTD passthrough policy reloaded: revision " + outcome.revision() + " active across "
							+ outcome.listeners().size() + " listener(s).")
					.color(NamedTextColor.GREEN));
			return;
		}

		invocation.source().sendMessage(Component
				.text("[EaglerXServer] MOTD passthrough reload rejected, revision " + outcome.revision()
						+ " is still active: " + outcome.error())
				.color(NamedTextColor.RED));
	}

	@Override
	public boolean hasPermission(Invocation invocation) {
		// The console is the channel FoxPanel drives, so it is always allowed. A player
		// needs the explicit permission.
		return !(invocation.source() instanceof Player) || invocation.source().hasPermission(PERMISSION);
	}

}
