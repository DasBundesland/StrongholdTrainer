package io.github.mjtb49.strongholdtrainer.commands;

import com.mojang.brigadier.CommandDispatcher;
import io.github.mjtb49.strongholdtrainer.StrongholdTrainer;
import net.minecraft.server.command.ServerCommandSource;

import static com.mojang.brigadier.arguments.BoolArgumentType.bool;
import static com.mojang.brigadier.arguments.BoolArgumentType.getBool;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

@Deprecated
public class HintsCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                literal("hints").then(
                        argument("renderHints", bool()).executes(
                                c -> {
                                    StrongholdTrainer.setOption("hints", getBool(c, "renderHints"));
                                    return 1;
                                }
                        )
                ).executes(
                        c -> {
                            StrongholdTrainer.setOption("hints", !StrongholdTrainer.getOption("hints"));
                            return 1;
                        }
                )
        );
    }
}
