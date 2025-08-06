package se.dpixie.democraticcommands;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.common.ForgeConfigSpec;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Mod("democraticcommands")
public class DemocraticCommandsMod {
    public static final String MOD_ID = "democraticcommands";
    private static final Map<UUID, ActiveVote> activeVotes = new ConcurrentHashMap<>();
    private static Set<String> opCommands = new HashSet<>();
    private static File auditLogFile;
    
    public DemocraticCommandsMod() {
        // Register config
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new VotingEventHandler());
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarting);
    }

    private void onServerStarting(net.minecraftforge.event.server.ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        File worldDir = server.getWorldPath(LevelResource.ROOT).toFile();

        // Initialize log file
        File modLogDir = new File(worldDir, "logs");
        if (!modLogDir.exists()) {
            modLogDir.mkdirs();
        }
        auditLogFile = new File(modLogDir, "audit.txt");
        
        // Load op commands from config
        opCommands = new HashSet<>(Config.OP_COMMANDS.get());
        System.out.println("[DemocraticCommands] Loaded " + opCommands.size() + " commands requiring votes");
        
        // Make OP commands visible to all players for tab completion
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        
        for (String opCommand : opCommands) {
            try {
                var commandNode = dispatcher.getRoot().getChild(opCommand);
                if (commandNode != null) {
                    try {
                        // Use reflection to modify the requirement field
                        var field = com.mojang.brigadier.tree.CommandNode.class.getDeclaredField("requirement");
                        field.setAccessible(true);
                        
                        // Set new requirement that allows tab completion for everyone
                        field.set(commandNode, (java.util.function.Predicate<CommandSourceStack>) (source) -> {
                            // Allow everyone to see the command for tab completion
                            return true;
                        });
                        
                        System.out.println("[DemocraticCommands] Made " + opCommand + " visible to all players");
                    } catch (Exception e) {
                        System.err.println("[DemocraticCommands] Failed to modify " + opCommand + ": " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                // Command doesn't exist, skip
            }
        }
    }
    
    public static class Config {
        public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
        
        public static final ForgeConfigSpec.IntValue VOTE_TIMEOUT = BUILDER
            .comment("Time in seconds before a vote times out")
            .defineInRange("voteTimeout", 30, 10, 300);
            
        public static final ForgeConfigSpec.IntValue MINIMUM_VOTERS = BUILDER
            .comment("Minimum number of players required online to initiate a vote")
            .defineInRange("minimumVoters", 2, 1, 100);
            
        public static final ForgeConfigSpec.IntValue MINIMUM_VOTES_REQUIRED = BUILDER
            .comment("Minimum number of actual votes (not abstentions) required for a vote to be valid")
            .defineInRange("minimumVotesRequired", 2, 1, 100);
            
        public static final ForgeConfigSpec.DoubleValue APPROVAL_THRESHOLD = BUILDER
            .comment("Percentage of YES votes required for a vote to pass (0.5 = 50%, 0.66 = 66%)")
            .defineInRange("approvalThreshold", 0.5, 0.0, 1.0);
            
        public static final ForgeConfigSpec.BooleanValue REQUIRE_MAJORITY_PARTICIPATION = BUILDER
            .comment("If true, requires more than 50% of online players to vote (not abstain) for the vote to be valid")
            .define("requireMajorityParticipation", true);
            
        public static final ForgeConfigSpec.BooleanValue LOG_VOTES = BUILDER
            .comment("Enable logging of all votes to file")
            .define("logVotes", true);
            
        public static final ForgeConfigSpec.BooleanValue COUNT_ABSTENTIONS_AS_NO = BUILDER
            .comment("If true, abstentions count as NO votes. If false, they are ignored")
            .define("countAbstentionsAsNo", false);
            
        public static final ForgeConfigSpec.ConfigValue<List<? extends String>> OP_COMMANDS = BUILDER
            .comment("List of commands that require voting when used by non-ops")
            .defineList("opCommands", 
                Arrays.asList(
                    "gamemode", "gamerule", "give", "tp", "teleport", "kill", "ban", "kick",
                    "op", "deop", "whitelist", "difficulty", "stop", "setblock", "fill", 
                    "summon", "effect", "enchant", "weather", "time", "spawnpoint", "setworldspawn"
                ),
                obj -> obj instanceof String);
            
        public static final ForgeConfigSpec SPEC = BUILDER.build();
    }
    
    public static class ActiveVote {
        public final String command;
        public final ServerPlayer initiator;
        public final Set<UUID> yesVotes = new HashSet<>();
        public final Set<UUID> noVotes = new HashSet<>();
        public final Set<UUID> abstained = new HashSet<>();
        public final Map<UUID, String> voterNames = new HashMap<>();
        public final Set<UUID> eligibleVoters = new HashSet<>();
        public final long startTime;
        
        public ActiveVote(String command, ServerPlayer initiator, Collection<ServerPlayer> players) {
            this.command = command;
            this.initiator = initiator;
            this.startTime = System.currentTimeMillis();
            for (ServerPlayer player : players) {
                eligibleVoters.add(player.getUUID());
                voterNames.put(player.getUUID(), player.getName().getString());
            }
        }
        
        public boolean hasVoted(UUID playerId) {
            return yesVotes.contains(playerId) || noVotes.contains(playerId);
        }
        
        public void vote(UUID playerId, boolean yes) {
            if (!eligibleVoters.contains(playerId)) return;
            
            yesVotes.remove(playerId);
            noVotes.remove(playerId);
            abstained.remove(playerId);
            
            if (yes) {
                yesVotes.add(playerId);
            } else {
                noVotes.add(playerId);
            }
        }
        
        public void markAbstained() {
            for (UUID voter : eligibleVoters) {
                if (!hasVoted(voter)) {
                    abstained.add(voter);
                }
            }
        }
        
        public boolean isPassed() {
            int totalVotes = yesVotes.size() + noVotes.size();
            
            // Check minimum votes requirement
            if (totalVotes < Config.MINIMUM_VOTES_REQUIRED.get()) {
                return false;
            }
            
            // Check majority participation if required
            if (Config.REQUIRE_MAJORITY_PARTICIPATION.get()) {
                double participationRate = (double) totalVotes / eligibleVoters.size();
                if (participationRate <= 0.5) {
                    return false;
                }
            }
            
            // Calculate approval based on config
            int effectiveNo = noVotes.size();
            if (Config.COUNT_ABSTENTIONS_AS_NO.get()) {
                effectiveNo += abstained.size();
            }
            
            int effectiveTotal = yesVotes.size() + effectiveNo;
            if (effectiveTotal == 0) return false;
            
            double approvalRate = (double) yesVotes.size() / effectiveTotal;
            return approvalRate >= Config.APPROVAL_THRESHOLD.get();
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() - startTime > (Config.VOTE_TIMEOUT.get() * 1000L);
        }
        
        public int getTimeRemaining() {
            return Math.max(0, Config.VOTE_TIMEOUT.get() - (int)((System.currentTimeMillis() - startTime) / 1000));
        }
        
        public String getVoteSummary() {
            StringBuilder summary = new StringBuilder();
            summary.append("Command: /").append(command).append("\n");
            summary.append("Initiator: ").append(initiator.getName().getString()).append("\n");
            summary.append("YES votes (").append(yesVotes.size()).append("): ");
            summary.append(yesVotes.stream().map(voterNames::get).collect(Collectors.joining(", "))).append("\n");
            summary.append("NO votes (").append(noVotes.size()).append("): ");
            summary.append(noVotes.stream().map(voterNames::get).collect(Collectors.joining(", "))).append("\n");
            summary.append("Abstained (").append(abstained.size()).append("): ");
            summary.append(abstained.stream().map(voterNames::get).collect(Collectors.joining(", "))).append("\n");
            summary.append("Result: ").append(isPassed() ? "PASSED" : "FAILED");
            return summary.toString();
        }
    }
    
    @Mod.EventBusSubscriber(modid = MOD_ID)
    public static class VotingEventHandler {
        
        @SubscribeEvent
        public static void onRegisterCommands(RegisterCommandsEvent event) {
            CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
            
            // Override suggestions for existing OP commands to make them visible to non-OPs
            for (String opCommand : opCommands) {
                // Find the existing command node
                var commandNode = dispatcher.getRoot().getChild(opCommand);
                if (commandNode != null) {
                    // Create a new command that mimics the original but with modified requirement
                    dispatcher.register(Commands.literal(opCommand)
                        .requires(source -> true)  // Allow everyone to see it
                        .redirect(commandNode));    // Redirect to original command logic
                }
            }
            
            // Register vote initiation command with autocomplete suggestions
            dispatcher.register(Commands.literal("vote")
                .requires(source -> true)  // Anyone can use this
                .then(Commands.argument("command", StringArgumentType.greedyString())
                    .suggests(OP_COMMAND_SUGGESTIONS)  // Add real command suggestions
                    .executes(context -> initiateVote(context))));
            
            // Register voting commands - NO PERMISSION REQUIRED
            dispatcher.register(Commands.literal("yes")
                .requires(source -> true)  // Anyone can vote
                .executes(context -> castVote(context, true)));
            
            dispatcher.register(Commands.literal("no")
                .requires(source -> true)  // Anyone can vote
                .executes(context -> castVote(context, false)));
            
            // Register vote status command - NO PERMISSION REQUIRED
            dispatcher.register(Commands.literal("votestatus")
                .requires(source -> true)  // Anyone can check status
                .executes(context -> showVoteStatus(context)));
        }
        
        @SubscribeEvent
        public static void onCommandEvent(CommandEvent event) {
            CommandSourceStack source = event.getParseResults().getContext().getSource();
            String fullCommand = event.getParseResults().getReader().getString();

            if (!(source.getEntity() instanceof ServerPlayer player)) {
                return;
            }

            // If this command is being run as a result of a passed vote, skip vote creation
            if (player.getPersistentData().getBoolean("democraticcommands_vote_executing")) {
                // Reset the flag so it doesn't persist
                player.getPersistentData().putBoolean("democraticcommands_vote_executing", false);
                return;
            }

            String commandName = fullCommand.split(" ")[0].replace("/", "");

            if (!opCommands.contains(commandName) || player.hasPermissions(2)) {
                return;
            }

            event.setCanceled(true); // Block execution

            createAndStartVote(fullCommand, player);
        }

        private static void createAndStartVote(String fullCommand, ServerPlayer initiator) {
            // Check if player already has an active vote
            if (activeVotes.containsKey(initiator.getUUID())) {
                initiator.sendSystemMessage(Component.literal("§cYou already have an active vote. Please wait for it to complete."));
                return;
            }

            // Get all online players
            MinecraftServer server = initiator.getServer();
            List<ServerPlayer> players = server.getPlayerList().getPlayers();

            // Check minimum voters requirement
            if (players.size() < Config.MINIMUM_VOTERS.get()) {
                initiator.sendSystemMessage(Component.literal(
                    "§cNot enough players online to vote. Minimum required: " + Config.MINIMUM_VOTERS.get() +
                    ", Currently online: " + players.size()));
                return;
            }

            // Create new vote
            ActiveVote activeVote = new ActiveVote(fullCommand, initiator, players);
            activeVotes.put(initiator.getUUID(), activeVote);

            // Tag this vote as not yet executed (used to prevent re-voting loop)
            initiator.getPersistentData().putBoolean("democraticcommands_vote_executing", false);

            // Create vote initiation message
            MutableComponent voteMessage = Component.literal("§6===== VOTE INITIATED =====\n")
                .append(Component.literal("§ePlayer §b" + initiator.getName().getString() + "§e wants to execute:\n"))
                .append(Component.literal("§c/" + fullCommand + "\n"))
                .append(Component.literal("§eVote using: "))
                .append(createClickableVote("§a[YES]", "/vote yes", "§aClick to vote YES"))
                .append(Component.literal(" "))
                .append(createClickableVote("§c[NO]", "/vote no", "§cClick to vote NO"))
                .append(Component.literal("\n§7Vote expires in " + Config.VOTE_TIMEOUT.get() + " seconds"))
                .append(Component.literal("\n§7Required: " +
                    (int)(Config.APPROVAL_THRESHOLD.get() * 100) + "% approval, " +
                    "minimum " + Config.MINIMUM_VOTES_REQUIRED.get() + " votes"));

            for (ServerPlayer player : players) {
                player.sendSystemMessage(voteMessage);
            }

            logVote("INITIATED", activeVote);
        }
        
        @SubscribeEvent
        public static void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            
            Iterator<Map.Entry<UUID, ActiveVote>> iterator = activeVotes.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, ActiveVote> entry = iterator.next();
                ActiveVote vote = entry.getValue();
                
                if (vote.isExpired()) {
                    vote.markAbstained();
                    concludeVote(entry.getKey(), vote);
                    iterator.remove();
                }
            }
        }
        
        private static int initiateVote(CommandContext<CommandSourceStack> context) {
            // This method is no longer used since we removed /vote <command>
            // Keeping it for backwards compatibility if needed
            return 0;
        }
        
        private static int castVote(CommandContext<CommandSourceStack> context, boolean yes) {
            CommandSourceStack source = context.getSource();
            
            if (!(source.getEntity() instanceof ServerPlayer)) {
                return 0;
            }
            
            ServerPlayer voter = (ServerPlayer) source.getEntity();
            
            // Find active vote for this player's server
            ActiveVote activeVote = null;
            UUID voteId = null;
            
            for (Map.Entry<UUID, ActiveVote> entry : activeVotes.entrySet()) {
                ActiveVote vote = entry.getValue();
                if (vote.eligibleVoters.contains(voter.getUUID())) {
                    activeVote = vote;
                    voteId = entry.getKey();
                    break;
                }
            }
            
            if (activeVote == null) {
                voter.sendSystemMessage(Component.literal("§cNo active vote found."));
                return 0;
            }
            
            if (activeVote.hasVoted(voter.getUUID())) {
                voter.sendSystemMessage(Component.literal("§eYou changed your vote to §" + (yes ? "aYES" : "cNO")));
            } else {
                voter.sendSystemMessage(Component.literal("§eYou voted §" + (yes ? "aYES" : "cNO")));
            }
            
            activeVote.vote(voter.getUUID(), yes);
            
            // Check if everyone has voted
            if (activeVote.yesVotes.size() + activeVote.noVotes.size() == activeVote.eligibleVoters.size()) {
                concludeVote(voteId, activeVote);
                activeVotes.remove(voteId);
            } else {
                // Send update to all players
                updateVoteStatus(activeVote);
            }
            
            return 1;
        }
        
        private static int showVoteStatus(CommandContext<CommandSourceStack> context) {
            CommandSourceStack source = context.getSource();
            
            if (activeVotes.isEmpty()) {
                source.sendSystemMessage(Component.literal("§7No active votes."));
                return 0;
            }
            
            for (ActiveVote vote : activeVotes.values()) {
                MutableComponent status = Component.literal("§6=== CURRENT VOTE STATUS ===\n")
                    .append(Component.literal("§eCommand: §c/" + vote.command + "\n"))
                    .append(Component.literal("§aYES: " + vote.yesVotes.size() + 
                        " §7| §cNO: " + vote.noVotes.size() + 
                        " §7| §8Not voted: " + (vote.eligibleVoters.size() - vote.yesVotes.size() - vote.noVotes.size()) + "\n"))
                    .append(Component.literal("§7Time remaining: " + vote.getTimeRemaining() + " seconds"));
                
                source.sendSystemMessage(status);
            }
            
            return 1;
        }
        
        private static void concludeVote(UUID voteId, ActiveVote vote) {
            MinecraftServer server = vote.initiator.getServer();
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            
            boolean passed = vote.isPassed();
            int totalVotes = vote.yesVotes.size() + vote.noVotes.size();
            
            MutableComponent resultMessage = Component.literal("§6===== VOTE CONCLUDED =====\n")
                .append(Component.literal("§eCommand: §c/" + vote.command + "\n"))
                .append(Component.literal("§aYES: " + vote.yesVotes.size() + 
                    " §7| §cNO: " + vote.noVotes.size() + 
                    " §7| §8Abstained: " + vote.abstained.size() + "\n"));
            
            // Add failure reason if applicable
            if (!passed) {
                if (totalVotes < Config.MINIMUM_VOTES_REQUIRED.get()) {
                    resultMessage.append(Component.literal("§cInsufficient votes: " + totalVotes + 
                        "/" + Config.MINIMUM_VOTES_REQUIRED.get() + " required\n"));
                } else if (Config.REQUIRE_MAJORITY_PARTICIPATION.get() && 
                          (double)totalVotes / vote.eligibleVoters.size() <= 0.5) {
                    resultMessage.append(Component.literal("§cInsufficient participation (>50% required)\n"));
                }
            }
            
            resultMessage.append(Component.literal(passed ? "§a✓ VOTE PASSED" : "§c✗ VOTE FAILED"));
            
            for (ServerPlayer player : players) {
                player.sendSystemMessage(resultMessage);
            }
            
            // Execute command if vote passed
            if (passed) {
                try {
                    // Mark the initiator so the command bypasses vote creation
                    vote.initiator.getPersistentData().putBoolean("democraticcommands_vote_executing", true);

                    // Create a command source using the initiator so output is sent to them
                    CommandSourceStack commandSource = vote.initiator.createCommandSourceStack()
                        .withPermission(4); // Give full permission for execution

                    // Execute and capture success value
                    int result = server.getCommands().performPrefixedCommand(commandSource, "/" + vote.command);

                    // Notify all players of success/failure
                    if (result > 0) {
                        for (ServerPlayer player : players) {
                            player.sendSystemMessage(Component.literal("§aCommand executed successfully!"));
                        }
                    } else {
                        vote.initiator.sendSystemMessage(Component.literal("§cCommand returned no success value. Possible syntax issue."));
                    }

                } catch (Exception e) {
                    vote.initiator.sendSystemMessage(Component.literal("§cError executing command: " + e.getMessage()));
                }
            }
            
            // Log vote conclusion
            logVote(passed ? "PASSED" : "FAILED", vote);
        }
        
        private static void updateVoteStatus(ActiveVote vote) {
            MinecraftServer server = vote.initiator.getServer();
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            
            int notVoted = vote.eligibleVoters.size() - vote.yesVotes.size() - vote.noVotes.size();
            
            MutableComponent statusMessage = Component.literal("")
                .append(Component.literal("§eVote status: §a" + vote.yesVotes.size() + 
                    " YES §7| §c" + vote.noVotes.size() + 
                    " NO §7| §8" + notVoted + " not voted"))
                .append(Component.literal(" §7(" + vote.getTimeRemaining() + "s remaining)"));
            
            for (ServerPlayer player : players) {
                player.sendSystemMessage(statusMessage);
            }
        }
        
        private static MutableComponent createClickableVote(String display, String command, String hover) {
            return Component.literal(display)
                .withStyle(style -> style
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
        }
        
        private static void logVote(String status, ActiveVote vote) {
            if (!Config.LOG_VOTES.get()) return;
            
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String timestamp = dateFormat.format(new Date());
            
            String logEntry = String.format(
                "\n[%s] Vote %s\n%s\n%s\n",
                timestamp,
                status,
                vote.getVoteSummary(),
                "----------------------------------------"
            );
            
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(auditLogFile, true))) {
                writer.write(logEntry);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
