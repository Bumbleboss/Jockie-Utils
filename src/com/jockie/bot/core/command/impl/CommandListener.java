package com.jockie.bot.core.command.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.jockie.bot.core.await.AwaitManager;
import com.jockie.bot.core.command.ICommand;
import com.jockie.bot.core.command.argument.IArgument;
import com.jockie.bot.core.command.argument.IEndlessArgument;
import com.jockie.bot.core.command.argument.VerifiedArgument;
import com.jockie.bot.core.paged.impl.PagedManager;
import com.jockie.bot.core.utility.TriFunction;

import net.dv8tion.jda.core.EmbedBuilder;
import net.dv8tion.jda.core.MessageBuilder;
import net.dv8tion.jda.core.MessageBuilder.Formatting;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.Member;
import net.dv8tion.jda.core.entities.MessageChannel;
import net.dv8tion.jda.core.events.Event;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;
import net.dv8tion.jda.core.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.core.hooks.EventListener;
import net.dv8tion.jda.core.utils.tuple.Pair;

public class CommandListener implements EventListener {
	
	private static final Comparator<Pair<String, ICommand>> COMMAND_COMPARATOR = new Comparator<>() {
		public int compare(Pair<String, ICommand> pair, Pair<String, ICommand> pair2) {
			ICommand command = pair.getRight(), command2 = pair2.getRight();
			
			if(pair.getLeft().length() > pair2.getLeft().length()) {
				return -1;
			}else if(pair.getLeft().length() < pair2.getLeft().length()) {
				return 1;
			}
			
			int arguments = command.getArguments().length, arguments2 = command2.getArguments().length;
			
			if(arguments > 0 && arguments2 > 0) {
				IArgument<?> lastArgument = command.getArguments()[arguments - 1], lastArgument2 = command2.getArguments()[arguments2 - 1];
				
				boolean endless = false, endless2 = false;
				boolean endlessArguments = false, endlessArguments2 = false;
				
				if(lastArgument.isEndless()) {
					if(lastArgument instanceof IEndlessArgument<?>) {
						int max = ((IEndlessArgument<?>) lastArgument).getMaxArguments();
						
						if(max != -1) {
							arguments += (max - 1);
						}else{
							endlessArguments = true;
						}
					}
					
					endless = true;
				}
				
				if(lastArgument2.isEndless()) {
					if(lastArgument2 instanceof IEndlessArgument<?>) {
						int max = ((IEndlessArgument<?>) lastArgument2).getMaxArguments();
						
						if(max != -1) {
							arguments2 += (max - 1);
						}else{
							endlessArguments2 = true;
						}
					}
					
					endless2 = true;
				}
				
				if(!endlessArguments && endlessArguments2) {
					return -1;
				}else if(endlessArguments && !endlessArguments2) {
					return 1;
				}
				
				if(arguments > arguments2) {
					return -1;
				}else if(arguments < arguments2) {
					return 1;
				}
				
				if(!endless && endless2) {
					return -1;
				}else if(endless && !endless2) {
					return 1;
				}
			}else if(arguments == 0 && arguments2 > 0) {
				return 1;
			}else if(arguments > 0 && arguments2 == 0) {
				return -1;
			}
			
			return 0;
		}
	};
	
	private Permission[] genericPermissions = {};
	
	private String[] defaultPrefixes = {"!"};
	
	private Function<MessageReceivedEvent, String[]> prefixFunction;
	
	private TriFunction<MessageReceivedEvent, CommandEvent, List<ICommand>, MessageBuilder> helperFunction;
	
	private boolean helpEnabled = true;
	
	private List<Long> developers = new ArrayList<>();
	
	private List<CommandStore> commandStores = new ArrayList<>();
	
	private List<CommandEventListener> commandEventListeners = new ArrayList<>();
	
	private ExecutorService commandExecutor = Executors.newCachedThreadPool();
	
	public CommandListener addCommandEventListener(CommandEventListener... commandEventListeners) {
		for(CommandEventListener commandEventListener : commandEventListeners) {
			if(!this.commandEventListeners.contains(commandEventListener)) {
				this.commandEventListeners.add(commandEventListener);
			}
		}
		
		return this;
	}
	
	public CommandListener removeCommandEventListener(CommandEventListener... commandEventListeners) {
		for(CommandEventListener commandEventListener : commandEventListeners) {
			this.commandEventListeners.remove(commandEventListener);
		}
		
		return this;
	}
	
	public List<CommandEventListener> getCommandEventListeners() {
		return Collections.unmodifiableList(this.commandEventListeners);
	}
	
	/**
	 * See {@link #getCommandStores()}
	 */
	public CommandListener addCommandStore(CommandStore... commandStores) {
		for(CommandStore commandStore : commandStores) {
			if(!this.commandStores.contains(commandStore)) {
				this.commandStores.add(commandStore);
			}
		}
		
		return this;
	}
	
	/**
	 * See {@link #getCommandStores()}
	 */
	public CommandListener removeCommandStore(CommandStore... commandStores) {
		for(CommandStore commandStore : commandStores) {
			this.commandStores.remove(commandStore);
		}
		
		return this;
	}
	
	/**
	 * @return a list of CommandStores which are basically like command containers holding all the commands
	 */
	public List<CommandStore> getCommandStores() {
		return Collections.unmodifiableList(this.commandStores);
	}
	
	/**
	 * See {@link #getDefaultPrefixes()}
	 */
	public CommandListener setDefaultPrefixes(String... prefixes) {
		/* 
		 * From the longest prefix to the shortest so that if the bot for instance has two prefixes one being "hello" 
		 * and the other being "hello there" it would recognize that the prefix is "hello there" instead of it thinking that
		 * "hello" is the prefix and "there" being the command.
		 */
		Arrays.sort(prefixes, (a, b) -> Integer.compare(b.length(), a.length()));
		
		this.defaultPrefixes = prefixes;
		
		return this;
	}
	
	/**
	 * @return a set of default prefixes which will be checked for when the bot receives a MessageReceivedEvent, 
	 * additionally the mention of the bot is a hard-coded prefix which can not be removed
	 */
	public String[] getDefaultPrefixes() {
		return this.defaultPrefixes;
	}
	
	/**
	 * See {@link #getGenericPermissions()}
	 */
	public CommandListener setGenericPermissions(Permission... permissions) {
		this.genericPermissions = permissions;
		
		return this;
	}
	
	/**
	 * @return a set of permissions which will always be checked for no matter the properties of the command. If the bot does not have these permissions the commands will not work
	 */
	public Permission[] getGenericPermissions() {
		return this.genericPermissions;
	}
	
	/**
	 * See {@link #getDevelopers()}
	 */
	public CommandListener addDeveloper(long id) {
		this.developers.add(id);
		
		return this;
	}
	
	/**
	 * See {@link #getDevelopers()}
	 */
	public CommandListener removeDeveloper(long id) {
		this.developers.remove(id);
		
		return this;
	}
	
	/**
	 * @return the developers which should be checked for in {@link ICommand#verify(MessageReceivedEvent, CommandListener)} if the command has {@link ICommand#isDeveloperCommand()}
	 */
	public List<Long> getDevelopers() {
		return Collections.unmodifiableList(this.developers);
	}
	
	/**
	 * See {@link #getPrefixes(MessageReceivedEvent)}
	 * 
	 * @param function the function which will return a set amount of prefixes for the specific context,
	 * for instance you can return guild or user specific prefixes
	 */
	public CommandListener setPrefixesFunction(Function<MessageReceivedEvent, String[]> function) {
		this.prefixFunction = function;
		
		return this;
	}
	
	/**
	 * @param event the context of the message
	 * 
	 * @return this will return a set of prefixes for the specific context,
	 * if a function was not set through {@link #setPrefixesFunction(Function)}
	 * the default function, {@link #getDefaultPrefixes()}, will instead be used
	 */
	public String[] getPrefixes(MessageReceivedEvent event) {
		if(this.prefixFunction != null) {
			String[] prefixes = this.prefixFunction.apply(event);
			
			/* 
			 * Should we also check if the length of the array is greater than 0 or
			 * can we justify giving the user the freedom of not returning any prefixes at all? 
			 * After all the mention prefix is hard-coded 
			 */
			if(prefixes != null /* && prefixes.length > 0 */) {
				/* 
				 * From the longest prefix to the shortest so that if the bot for instance has two prefixes one being "hello" 
				 * and the other being "hello there" it would recognize that the prefix is "hello there" instead of it thinking that
				 * "hello" is the prefix and "there" being the command.
				 */
				Arrays.sort(prefixes, (a, b) -> Integer.compare(b.length(), a.length()));
				
				return prefixes;
			}else{
				System.err.println("The prefix function returned a null object, I will return the default prefixes instead");
			}
		}
		
		return this.getDefaultPrefixes();
	}
	
	/**
	 * See {@link #isHelpEnabled()}
	 */
	public CommandListener setHelpEnabled(boolean enabled) {
		this.helpEnabled = enabled;
		
		return this;
	}
	
	/**
	 * Whether or not the bot should return a message when the wrong arguments were given
	 */
	public boolean isHelpEnabled() {
		return this.helpEnabled;
	}
	
	/**
	 * @param function the function that will be called when a command had the wrong arguments. 
	 * </br></br>Parameters for the function:
	 * </br><b>MessageReceivedEvent</b> - The event that triggered this
	 * </br><b>CommandEvent</b> - Information about the command and context
	 * </br><b>List&#60;ICommand&#62;</b> - The possible commands which the message could be referring to
	 */
	public CommandListener setHelpFunction(TriFunction<MessageReceivedEvent, CommandEvent, List<ICommand>, MessageBuilder> function) {
		this.helperFunction = function;
		
		return this;
	}
	
	public MessageBuilder getHelp(MessageReceivedEvent event, CommandEvent commandEvent, List<ICommand> commands) {
		if(this.helperFunction != null) {
			MessageBuilder builder = this.helperFunction.apply(event, commandEvent, commands);
			
			if(builder != null) {
				return builder;
			}else{
				System.err.println("The help function returned a null object, I will return the default help instead");
			}
		}
		
		StringBuilder description = new StringBuilder();
		for(int i = 0; i < commands.size(); i++) {
			ICommand command = commands.get(i);
			
			description.append(command.getCommandTrigger())
				.append(" ")
				.append(command.getArgumentInfo());
			
			if(i < commands.size() - 1) {
				description.append("\n");
			}
		}
		
		return new MessageBuilder().setEmbed(new EmbedBuilder().setDescription(description.toString())
			.setFooter("* means required. [] means multiple arguments of that type.", null)
			.setAuthor("Help", null, event.getJDA().getSelfUser().getEffectiveAvatarUrl()).build());
	}
	
	public void onEvent(Event event) {
		if(event instanceof MessageReceivedEvent) {
			this.onMessageReceived((MessageReceivedEvent) event);
		}
		
		AwaitManager.handleAwait(event);
	}
	
	/* Would it be possible to split this event in to different steps, opinions? */
	public void onMessageReceived(MessageReceivedEvent event) {
		if(event.getChannelType().isGuild()) {
			if(PagedManager.handlePagedResults(event)) {
				return;
			}
		}
		
		String[] prefixes = this.getPrefixes(event);
		
		String message = event.getMessage().getContentRaw(), prefix = null;
		
		/* Needs to work for both non-nicked mention and nicked mention */
		if(message.startsWith("<@" + event.getJDA().getSelfUser().getId() + "> ") || message.startsWith("<@!" + event.getJDA().getSelfUser().getId() + "> ")) {
			/* I want every bot to have this feature therefore it will be a hard coded one, arguments against it? */
			prefix = message.substring(0, message.indexOf(" ") + 1);
			
			if(message.equals(prefix + "prefix") || message.equals(prefix + "prefixes")) {
				String allPrefixes = Arrays.deepToString(prefixes);
				allPrefixes = allPrefixes.substring(1, allPrefixes.length() - 1);
				
				event.getChannel().sendMessage(new MessageBuilder()
					.append("My prefix")
					.append(prefixes.length > 1 ? "es are " : " is ")
					.append(allPrefixes, Formatting.BOLD)
					.build()).queue();
				
				return;
			}
		}else{
			for(String p : prefixes) {
				if(message.startsWith(p)) {
					prefix = p;
					
					break;
				}
			}
		}
		
		if(prefix != null) {
			long commandStarted = System.nanoTime();
			
			message = message.substring(prefix.length());
			
			Set<ICommand> possibleCommands = new HashSet<>();
			
			/* This is probably not the best but it works */
			List<Pair<ICommand, List<?>>> allCommands = this.getCommandStores().stream()
				.map(store -> store.getCommands())
				.flatMap(List::stream)
				.map(command -> command.getAllCommandsRecursive(""))
				.flatMap(List::stream)
				.filter(pair -> pair.getLeft().verify(event, this))
				.filter(pair -> !pair.getLeft().isPassive())
				.collect(Collectors.toList());
			
			List<Pair<String, ICommand>> commands = new ArrayList<>();
			for(Pair<ICommand, List<?>> pair : allCommands) {
				for(Object obj : pair.getRight()) {
					if(obj instanceof String) {
						commands.add(Pair.of((String) obj, pair.getLeft()));
					}else if(obj instanceof Pair) {
						@SuppressWarnings("unchecked")
						Pair<ICommand, List<String>> pairs = (Pair<ICommand, List<String>>) obj;
						
						for(String trigger : pairs.getRight()) {
							commands.add(Pair.of(trigger, pairs.getLeft()));
						}
					}
				}
			}
			
			commands.sort(COMMAND_COMPARATOR);
			
			for(Pair<String, ICommand> pair : commands) {
				System.out.println(pair.getRight().getUsage());
			}
			
			COMMANDS :
			for(Pair<String, ICommand> pair : commands) {
				ICommand command = pair.getRight();
				
				String msg = message, cmd = pair.getLeft();
				
				if(!command.isCaseSensitive()) {
					msg = msg.toLowerCase();
					cmd = cmd.toLowerCase();
				}
				
				if(!msg.startsWith(cmd)) {
					continue COMMANDS;
				}
				
				msg = message.substring(cmd.length());
				
				if(msg.length() > 0 && msg.charAt(0) != ' ') {
					/* Can it even get to this? */
					
					continue COMMANDS;
				}
				
				int argumentCount = 0;
				
				Object[] arguments = new Object[command.getArguments().length];
				
				IArgument<?>[] args = command.getArguments();
				
				ARGUMENTS:
				for(int i = 0; i < arguments.length; i++) {
					if(msg.length() > 0) {
						if(msg.startsWith(" ")) {
							msg = msg.substring(1);
						}else{
							/* When does it get here? */
							
							continue COMMANDS;
						}
					}
					
					IArgument<?> argument = args[i];
					
					VerifiedArgument<?> verified;
					if(argument.isEndless()) {
						if(msg.length() == 0 && !argument.acceptEmpty()) {
							possibleCommands.add((command instanceof DummyCommand) ? command.getParent() : command);
							
							continue COMMANDS;
						}
						
						verified = argument.verify(event, msg);
						msg = "";
					}else{
						String content = null;
						if(msg.length() > 0) {
							/* Is this even worth having? Not quite sure if I like the implementation */
							if(argument instanceof IEndlessArgument) {
								if(msg.charAt(0) == '[') {
									int endBracket = 0;
									while((endBracket = msg.indexOf(']', endBracket + 1)) != -1 && msg.charAt(endBracket - 1) == '\\');
									
									if(endBracket != -1) {
										content = msg.substring(1, endBracket);
										
										msg = msg.substring(content.length() + 2);
										
										content = content.replace("\\[", "[").replace("\\]", "]");
									}
								}
							}else if(argument.acceptQuote()) {
								if(msg.charAt(0) == '"') {
									int nextQuote = 0;
									while((nextQuote = msg.indexOf('"', nextQuote + 1)) != -1 && msg.charAt(nextQuote - 1) == '\\');
									
									if(nextQuote != -1) {
										content = msg.substring(1, nextQuote);
										
										msg = msg.substring(content.length() + 2);
										
										content = content.replace("\\\"", "\"");
									}
								}
							}
							
							if(content == null) {
								content = msg.substring(0, (msg.contains(" ")) ? msg.indexOf(" ") : msg.length());
								msg = msg.substring(content.length());
							}
						}else{
							content = "";
						}
						
						if(content.length() == 0 && !argument.acceptEmpty()) {
							possibleCommands.add((command instanceof DummyCommand) ? command.getParent() : command);
							
							continue COMMANDS;
						}
						
						verified = argument.verify(event, content);
					}
					
					switch(verified.getVerifiedType()) {
						case INVALID: {
							String reason = argument.getError();
							if(reason == null) {
								reason = verified.getError();
								if(reason == null) {
									reason = "is invalid";
								}
							}
							
							possibleCommands.add((command instanceof DummyCommand) ? command.getParent() : command);
							
							continue COMMANDS;
						}
						case VALID: {
							arguments[argumentCount++] = verified.getObject();
							
							break;
						}
						case VALID_END_NOW: {
							arguments[argumentCount++] = verified.getObject();
							
							break ARGUMENTS;
						}
					}
				}
				
				/* There is more content than the arguments handled */
				if(msg.length() > 0) {
					continue COMMANDS;
				}
				
				/* Not the correct amount of arguments for the command */
				if(command.getArguments().length != argumentCount) {
					continue COMMANDS;
				}
				
				CommandEvent commandEvent = new CommandEvent(event, this, prefix, cmd, pair.getLeft());
				if(command.isExecuteAsync()) {
					this.commandExecutor.submit(() -> {
						this.executeCommand(command, event, commandEvent, commandStarted, arguments);
					});
				}else{
					this.executeCommand(command, event, commandEvent, commandStarted, arguments);
				}
				
				return;
			}
			
			if(this.helpEnabled && possibleCommands.size() > 0) {
				if(event.getChannelType().isGuild()) {
					Member bot = event.getGuild().getSelfMember();
					
					if(!bot.hasPermission(Permission.MESSAGE_WRITE)) {
						event.getAuthor().openPrivateChannel().queue(channel -> {
							channel.sendMessage("Missing permission **" + Permission.MESSAGE_WRITE.getName() + "** in " + event.getChannel().getName() + ", " + event.getGuild().getName()).queue();
						});
						
						return;
					}else if(!bot.hasPermission(Permission.MESSAGE_EMBED_LINKS)) {
						event.getChannel().sendMessage("Missing permission **" + Permission.MESSAGE_EMBED_LINKS.getName() + "** in " + event.getChannel().getName() + ", " + event.getGuild().getName()).queue();
						
						return;
					}
				}
				
				/* The alias for the CommandEvent is just everything after the prefix since there is no way to do it other than having a list of CommandEvent or aliases */
				event.getChannel().sendMessage(this.getHelp(event, new CommandEvent(event, this, prefix, message, null), new ArrayList<>(possibleCommands)).build()).queue();
			}
		}
	}
	
	private boolean checkPermissions(MessageReceivedEvent event, CommandEvent commandEvent, ICommand command) {
		if(event.getChannelType().isGuild()) {
			Member bot = event.getGuild().getMember(event.getJDA().getSelfUser());
			
			long permissionsNeeded = Permission.getRaw(this.genericPermissions) | Permission.getRaw(command.getBotDiscordPermissionsNeeded());
			
			StringBuilder missingPermissions = new StringBuilder();
			for(Permission permission : Permission.getPermissions(permissionsNeeded)) {
				if(!bot.hasPermission(event.getTextChannel(), permission)) {
					missingPermissions.append(permission.getName() + "\n");
				}
			}
			
			if(missingPermissions.length() > 0) {
				StringBuilder message = new StringBuilder()
					.append("Missing permission" + (missingPermissions.length() > 1 ? "s" : "") + " to execute **")
					.append(commandEvent.getCommandTrigger()).append("** in ")
					.append(event.getChannel().getName())
					.append(", ")
					.append(event.getGuild().getName())
					.append("\n```")
					.append(missingPermissions)
					.append("```");
				
				MessageChannel channel;
				if(!bot.hasPermission(event.getTextChannel(), Permission.MESSAGE_WRITE)) {
					channel = event.getAuthor().openPrivateChannel().complete();
				}else{
					channel = event.getChannel();
				}
				
				channel.sendMessage(message).queue();
				
				return false;
			}
		}
		
		return true;
	}
	
	@Deprecated
	private void executeCommand(ICommand command, MessageReceivedEvent event, CommandEvent commandEvent, long timeStarted, Object... arguments) {
		if(this.checkPermissions(event, commandEvent, command)) {
			try {
				/* Allow for a custom cooldown implementation? */
				/* Simple cooldown feature, not sure how scalable it is */
				if(command.getCooldownDuration() > 0) {
					/* Should a new manager be used for this or not? */
					long remaining = CooldownManager.getTimeRemaining(command, event.getAuthor().getIdLong());
					
					if(remaining == 0) {
						/* Add the cooldown before the command has executed so that in case the command has a long execution time it will not get there */
						CooldownManager.addCooldown(command, event.getAuthor().getIdLong());
						
						command.execute(event, commandEvent, arguments);
					}else{
						event.getChannel().sendMessage("This command has a cooldown, please try again in " + ((double) remaining/1000) + " seconds").queue();
					}
				}else{
					command.execute(event, commandEvent, arguments);
				}
				
				for(CommandEventListener listener : this.commandEventListeners) {
					/* Wrapped in a try catch because we don't want the execution of this to fail just because we couldn't rely on an event handler not to throw an exception */
					try {
						listener.onCommandExecuted(command, event, commandEvent);
					}catch(Exception e) {
						e.printStackTrace();
					}
				}
			}catch(Exception e) {
				if(command.getCooldownDuration() > 0) {
					/* If the command execution fails then no cooldown should be added therefore this */
					CooldownManager.removeCooldown(command, event.getAuthor().getIdLong());
				}
				
				if(e instanceof InsufficientPermissionException) {
					System.out.println("Attempted to execute command (" + commandEvent.getCommandTrigger() + ") with arguments " + Arrays.deepToString(arguments) + 
						", though it failed due to missing permissions, time elapsed " + (System.nanoTime() - timeStarted) + 
						", error message (" + e.getMessage() + ")");
					
					/* Should we filter out the missing permission(s) from the exception and get its readable format and send it back to the user? */
					event.getChannel().sendMessage("Missing permissions").queue();
					
					return;
				}
				
				for(CommandEventListener listener : this.commandEventListeners) {
					/* Wrapped in a try catch because we don't want the execution of this to fail just because we couldn't rely on an event handler not to throw an exception */
					try {
						listener.onCommandExecutionException(command, event, commandEvent, e);
					}catch(Exception e1) {
						e1.printStackTrace();
					}
				}
				
				/* Should this still be thrown even if it sends to the listener? */
				try {
					@Deprecated /* This doesn't always work, look in to it */
					Exception exception = e.getClass().getConstructor(String.class).newInstance("Attempted to execute command (" + commandEvent.getCommandTrigger() + ") with the arguments " +
						Arrays.deepToString(arguments) + " but it failed" + 
						((e.getMessage() != null) ? " with the message \"" + e.getMessage() + "\""  : ""));
					
					exception.setStackTrace(e.getStackTrace());
					exception.printStackTrace();
				}catch(Exception e2) {
					e2.printStackTrace();
				}
			}
			
			System.out.println("Executed command (" + commandEvent.getCommandTrigger() + ") with the arguments " + Arrays.deepToString(arguments) + ", time elapsed " + (System.nanoTime() - timeStarted));
		}
	}
}