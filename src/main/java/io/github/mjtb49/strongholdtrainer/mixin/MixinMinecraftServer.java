package io.github.mjtb49.strongholdtrainer.mixin;

import io.github.mjtb49.strongholdtrainer.StrongholdTrainer;
import io.github.mjtb49.strongholdtrainer.api.EntranceAccessor;
import io.github.mjtb49.strongholdtrainer.api.StartAccessor;
import io.github.mjtb49.strongholdtrainer.api.StrongholdTreeAccessor;
import io.github.mjtb49.strongholdtrainer.commands.NextMistakeCommand;
import io.github.mjtb49.strongholdtrainer.ml.StrongholdRoomClassifier;
import io.github.mjtb49.strongholdtrainer.render.Color;
import io.github.mjtb49.strongholdtrainer.render.Cuboid;
import io.github.mjtb49.strongholdtrainer.render.Line;
import io.github.mjtb49.strongholdtrainer.render.TextRenderer;
import io.github.mjtb49.strongholdtrainer.util.EntryNode;
import io.github.mjtb49.strongholdtrainer.util.PlayerPath;
import io.github.mjtb49.strongholdtrainer.util.StrongholdSearcher;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.ServerNetworkIo;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StrongholdGenerator;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.gen.feature.StructureFeature;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.text.DecimalFormat;
import java.util.*;
import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public class MixinMinecraftServer {

    @Shadow private PlayerManager playerManager;
    @Shadow @Final private ServerNetworkIo networkIo;
    private StructurePiece lastPiece = null;
    private StructurePiece mlChosen = null;
    private final Map<StructurePiece, Double> percents = new HashMap<>();
    DecimalFormat df = new DecimalFormat("0.00");
    private final List<StructureStart<?>> visitedNull = new ArrayList<>();
    private int ticksInStronghold = -1;
    private int currentRoomTime = 0;
    private Vec3d lastPlayerPosition;
    private PlayerPath playerPath;
    private StructureStart<?> lastStart;

    @Inject(method = "tick", at = @At("HEAD"))
    private void inject(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {

        for (ServerPlayerEntity player : this.playerManager.getPlayerList()) {

            tracePlayer(player);

            ServerWorld world = player.getServerWorld();

            StructureStart<?> start = world.getStructureAccessor().method_28388(player.getBlockPos(), true, StructureFeature.STRONGHOLD);

            if (start != StructureStart.DEFAULT) {

                //Lazy check if we've changed strongholds
                if (lastStart != start) {
                    lastStart = start;
                    ticksInStronghold = -1;
                    currentRoomTime = 0;
                }

                StrongholdGenerator.Start strongholdStart = ((StartAccessor)start).getStart();

                if (strongholdStart == null) {
                    if (!this.visitedNull.contains(start)) {
                        this.visitedNull.add(start);
                        player.sendMessage(new LiteralText("Please visit a new stronghold!").formatted(Formatting.RED), false);
                    }
                    continue;
                }

                for (StructurePiece piece : start.getChildren()) {
                    if (piece.getBoundingBox().contains(player.getBlockPos())) {
                        if (lastPiece != piece) {
                            if (
                                    lastPiece == null
                                    || !lastPiece.getBoundingBox().contains(player.getBlockPos())
                                    || (lastPiece instanceof StrongholdGenerator.SmallCorridor && !(piece instanceof StrongholdGenerator.SmallCorridor)))
                            {

                                onRoomUpdate(start, piece, player);
                                drawRoomsAndDoors(start, strongholdStart, piece, player);

                                currentRoomTime = 0;
                                lastPiece = piece;
                            }
                        }
                    }
                }

            }

            if (ticksInStronghold >= 0) {
                ticksInStronghold++;
                currentRoomTime++;
            }
        }
    }

    private void drawRoomsAndDoors(StructureStart<?> start, StrongholdGenerator.Start strongholdStart, StructurePiece piece, ServerPlayerEntity player) {
        int yOffset = ((StartAccessor)start).getYOffset();

        Cuboid cuboid = new Cuboid(piece.getBoundingBox(), Color.PURPLE);

        StrongholdTrainer.submitRoom(cuboid);
        // TODO: good idea to cache this, doesn't seem to hurt perf too much
        StructurePiece searchResult = StrongholdSearcher.search(((StrongholdTreeAccessor)strongholdStart).getTree(), piece);

        StrongholdTrainer.clearDoors();
        TextRenderer.clear();

        TextRenderer.add(cuboid.getVec(), "Depth: " + piece.getLength(), 0.01f);
        TextRenderer.add(cuboid.getVec().add(0, -0.2, 0), "Direction: " + piece.getFacing(), 0.01f);
        TextRenderer.add(cuboid.getVec().add(0, -0.4, 0), "Type: " + piece.getClass().getSimpleName(), 0.01f);

        for (EntryNode node : ((EntranceAccessor) piece).getEntrances()) {
            // Means we've reached a dead end- don't render forwards entries
            if (node.pointer == null && node.type == EntryNode.Type.FORWARDS) {
                continue;
            }

            BlockBox entrance = node.box;

            BlockBox newBox = new BlockBox(entrance.minX, entrance.minY + yOffset, entrance.minZ, entrance.maxX - 1, entrance.maxY + yOffset - 1, entrance.maxZ - 1);

            Color color = node.type == EntryNode.Type.FORWARDS ? Color.WHITE : Color.YELLOW;

            boolean isBlue = false;
            if (searchResult == null) {
                if (node.type == EntryNode.Type.BACKWARDS) {
                    color = Color.BLUE;
                    isBlue = true;
                }
            } else if (node.type == EntryNode.Type.FORWARDS && searchResult == node.pointer) {
                color = Color.BLUE;
                isBlue = true;
            }

            if (node.pointer != null && node.pointer == this.mlChosen) {
                if (isBlue) {

                    StrongholdTrainer.submitDoor(new Cuboid(Box.from(newBox).expand(0.05), Color.GREEN));
                } else {
                    color = Color.GREEN;
                }
            }

            Cuboid door = new Cuboid(newBox, color);
            StrongholdTrainer.submitDoor(door);

            if (node.pointer != null) {
                String text = df.format(this.percents.getOrDefault(node.pointer, 0.0) * 100) + "%";
                TextRenderer.add(door.getVec(), text);
            }

            if (node.type == EntryNode.Type.BACKWARDS) {
                // TODO: remove when we've implemented this
                TextRenderer.add(door.getVec(), "not supported", 0.01f);
            }
        }
    }

    private void onRoomUpdate(StructureStart<?> start, StructurePiece piece, ServerPlayerEntity player) {
        if (playerPath != null && !((StartAccessor) start).hasBeenRouted())
            playerPath.addPiece((StrongholdGenerator.Piece) lastPiece, currentRoomTime);
        updateStats(start, piece, player);
        updateMLChoice(start, piece, player);
    }

    private void updateStats(StructureStart<?> start, StructurePiece piece, ServerPlayerEntity player) {
        if (
                lastPiece instanceof StrongholdGenerator.Start
                        && ticksInStronghold < 0 && !((StartAccessor)start).hasBeenRouted()
                        && !lastPiece.getBoundingBox().contains(player.getBlockPos())
        ) {
            playerPath = new PlayerPath(start);
            ticksInStronghold = 0;
        }

        if (piece instanceof StrongholdGenerator.PortalRoom && ticksInStronghold >= 0) {
            player.sendMessage(new LiteralText("Time of " + ticksInStronghold / 20.0 + " seconds").formatted(Formatting.DARK_GREEN), false);
            ((StartAccessor)start).setHasBeenRouted(true);
            ticksInStronghold = -1;
            playerPath.addPiece((StrongholdGenerator.PortalRoom) piece, 0);
            playerPath.review();
            NextMistakeCommand.submitMistakesAndInaccuracies(playerPath.getMistakes(), playerPath.getInaccuracies());
            NextMistakeCommand.sendInitialMessage(player);
            playerPath = null;
        }
    }

    private void updateMLChoice(StructureStart<?> start, StructurePiece piece, ServerPlayerEntity player) {
        double[] policy = StrongholdRoomClassifier.getPredictions(((StartAccessor) start).getStart(), (StrongholdGenerator.Piece) piece);

        //StringBuilder s = new StringBuilder();
        //Arrays.stream(policy).forEach(e -> s.append(df.format(e)).append(" "));
        //player.sendMessage(new LiteralText(s.toString()).formatted(Formatting.YELLOW), false);

        List<StructurePiece> pieces = ((StrongholdTreeAccessor)((StartAccessor) start).getStart()).getTree().getOrDefault(piece, new ArrayList<>());

        this.percents.clear();
        int idx = -1;
        double min = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < policy.length; i++) {
            double p = policy[i];

            if (i < pieces.size()) {
                this.percents.put(pieces.get(i), p);
            }

            if (p > min) {
                idx = i;
                min = p;
            }
        }

        if (idx < pieces.size()) {
            this.mlChosen = pieces.get(idx);
        } else {
            this.mlChosen = null;
        }
    }

    private void tracePlayer(ServerPlayerEntity player) {
        if (ticksInStronghold >= 0) {
            if (!player.isSpectator() && !player.isCreative() && StrongholdTrainer.getOption("trace")) {
                if (lastPlayerPosition != null)
                    if (lastPlayerPosition.distanceTo(player.getPos()) < 10)
                        StrongholdTrainer.submitPlayerLine(new Line(lastPlayerPosition.add(0,0.01,0), player.getPos().add(0,0.01,0), Color.PINK));
                lastPlayerPosition = player.getPos();
            } else {
                lastPlayerPosition = null;
            }
        }
    }
}
