package org.dawnoftime.onceuponatown.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.dawnoftime.onceuponatown.town.ConnectionPoint;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.Town;

public class TownCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                 CommandBuildContext context) {
        dispatcher.register(
            Commands.literal("ouat")
                .then(Commands.literal("town")
                    // /ouat town status: accessible by any player
                    .then(Commands.literal("status")
                        .executes(TownCommand::status))
                    // /ouat town autonomy: requires op level 2
                    .then(Commands.literal("autonomy")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("enable")
                            .executes(ctx -> autonomySet(ctx, true)))
                        .then(Commands.literal("disable")
                            .executes(ctx -> autonomySet(ctx, false)))
                        .then(Commands.literal("status")
                            .executes(TownCommand::autonomyStatus))))
        );
    }

    private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos pos = BlockPos.containing(ctx.getSource().getPosition());
        Town town = LevelTowns.get(level).getNearestTown(pos, 128).orElse(null);
        if (town == null) {
            ctx.getSource().sendFailure(Component.literal("[OUAT] No town within 128 blocks"));
            return 0;
        }

        int houses = 0, jobs = 0, gardens = 0, streets = 0;
        for (ConnectionPoint cp : town.getAvailableConnectionPoints()) {
            String pool = cp.targetName();
            if (pool.contains("houses"))        houses++;
            else if (pool.contains("jobs"))     jobs++;
            else if (pool.contains("gardens"))  gardens++;
            else if (pool.contains("streets"))  streets++;
        }

        String slot = " free slot";
        StringBuilder sb = new StringBuilder("[Village status]\n");
        sb.append("Houses  : ").append(houses).append(houses == 1 ? slot : slot + "s").append("\n");
        sb.append("Jobs    : ").append(jobs).append(jobs == 1 ? slot : slot + "s").append("\n");
        sb.append("Gardens : ").append(gardens).append(gardens == 1 ? slot : slot + "s").append("\n");
        sb.append("Streets : ").append(streets).append(streets == 1 ? slot : slot + "s");
        if (houses == 0 && jobs == 0 && gardens == 0 && streets == 0) {
            sb.append("\nNo connection points available -- the village cannot expand.");
        }

        String result = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    private static int autonomySet(CommandContext<CommandSourceStack> ctx, boolean enable) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos pos = BlockPos.containing(ctx.getSource().getPosition());
        Town town = LevelTowns.get(level).getNearestTown(pos, 128).orElse(null);
        if (town == null) {
            ctx.getSource().sendFailure(Component.literal("[OUAT] No town within 128 blocks"));
            return 0;
        }
        town.setAutonomyEnabled(enable);
        LevelTowns.get(level).markDirty();
        String state = enable ? "enabled" : "disabled";
        ctx.getSource().sendSuccess(() -> Component.literal("[OUAT] Village autonomy " + state), true);
        return 1;
    }

    private static int autonomyStatus(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos pos = BlockPos.containing(ctx.getSource().getPosition());
        Town town = LevelTowns.get(level).getNearestTown(pos, 128).orElse(null);
        if (town == null) {
            ctx.getSource().sendFailure(Component.literal("[OUAT] No town within 128 blocks"));
            return 0;
        }
        String enabled = town.isAutonomyEnabled() ? "enabled" : "disabled";
        String chosen = town.getAutonomyChosenTransitionId().isEmpty() ? "none" : town.getAutonomyChosenTransitionId();
        String msg = "[OUAT] Autonomy: " + enabled + " | chosen path: " + chosen;
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }
}
