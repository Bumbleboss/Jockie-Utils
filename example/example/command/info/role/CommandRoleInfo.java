package example.command.info.role;

import com.jockie.bot.core.command.argument.Argument;
import com.jockie.bot.core.command.impl.CommandImpl;
import com.jockie.bot.core.paged.impl.PagedManager;
import com.jockie.bot.core.paged.impl.PagedResult;

import example.Main;
import net.dv8tion.jda.core.EmbedBuilder;
import net.dv8tion.jda.core.entities.MessageEmbed;
import net.dv8tion.jda.core.entities.Role;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;

public class CommandRoleInfo extends CommandImpl {
	
	public CommandRoleInfo() {
		super("role info");
		
		super.setAliases("roleinfo", "rolei", "ri");
		super.setDescription("Get information about a role");
	}
	
	public void onCommand(MessageReceivedEvent event, @Argument(name="Role", nullDefault=true) Role role) {
		if(role != null) {
			event.getChannel().sendMessage(getRoleInfo(role)).queue();
		}else{
			PagedResult<Role> paged = new PagedResult<Role>(event.getGuild().getRoles(), Role::getAsMention, selectEvent -> {
				event.getChannel().sendMessage(getRoleInfo(selectEvent.entry)).queue();
			});
			
			paged.setListIndexesContinuously(true);
			paged.setDeleteOnTimeout(true);
			
			PagedManager.addPagedResult(event, paged);
		}
	}
	
	private static MessageEmbed getRoleInfo(Role role) {
		EmbedBuilder builder = new EmbedBuilder();
		builder.setColor(role.getColor());
		builder.addField("Name", role.getName(), true);
		builder.addField("Id", role.getName(), true);
		builder.addBlankField(true);
		builder.addField("Mention", role.getAsMention(), true);
		builder.addField("Raw Permissions", String.valueOf(role.getPermissionsRaw()), true);
		builder.addBlankField(true);
		builder.addField("Created", Main.FORMATTER.format(role.getCreationTime()), true);
		builder.addBlankField(true);
		builder.addBlankField(true);
		
		return builder.build();
	}
}