package mod.advancedmobs.command;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;

/**
 * Command to force-spawn the Mob Warlord in the next raid.
 */
public class RaidBossSpawnCommand {
    
    private static boolean forceNextRaidBoss = false;
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess, Commands.RegistrationEnvironment environment) {
        dispatcher.register(Commands.literal("advancedmobs")
            .then(Commands.literal("raid")
                .requires(source -> source.hasPermissionLevel(2))
                .then(Commands.literal("forceboss")
                    .executes(RaidBossSpawnCommand::executeForceBoss))
            )
        );
    }
    
    private static int executeForceBoss(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getDirectEntity();
        
        forceNextRaidBoss = true;
        
        source.sendFeedback(() -> 
            Component.literal("[SUCCESS] Next raid will GUARANTEE a Mob Warlord spawn on the final wave!")
                .withStyle(style -> style.withColor(ChatFormatting.GREEN).withBold(true)), 
            true
        );
        
        source.sendFeedback(() ->
            Component.literal("  Start a raid to summon the boss!")
                .withStyle(style -> style.withColor(ChatFormatting.GRAY)),
            false
        );
        
        return 1;
    }
    
    /**
     * Arms the force-spawn flag for the next raid.
     */
    public static void forceNextRaidBoss() {
        forceNextRaidBoss = true;
    }

    /**
     * Checks if the next raid should force-spawn the boss.
     */
    public static boolean shouldForceSpawn() {
        if (forceNextRaidBoss) {
            forceNextRaidBoss = false; // Reset after use
            return true;
        }
        return false;
    }
}

