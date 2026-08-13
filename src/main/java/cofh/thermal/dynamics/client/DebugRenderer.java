package cofh.thermal.dynamics.client;

import cofh.lib.util.helpers.BlockHelper;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.*;

/**
 * Created by covers1624 on 12/12/21.
 */
public class DebugRenderer {

    private static final AABB smolBox = new AABB(0.25, 0.25, 0.25, 0.75, 0.75, 0.75);

    public static Map<UUID, Map<BlockPos, List<BlockPos>>> grids = new HashMap<>();

    public static void register() {

        NeoForge.EVENT_BUS.addListener(DebugRenderer::renderWorldLast);
    }

    private static void renderWorldLast(RenderLevelStageEvent event) {

        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        PoseStack pStack = event.getPoseStack();
        pStack.pushPose();

        Vec3 projectedView = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        pStack.translate(-projectedView.x, -projectedView.y, -projectedView.z);

        Random random = new Random();
        for (Map.Entry<UUID, Map<BlockPos, List<BlockPos>>> gridEntry : grids.entrySet()) {
            UUID uuid = gridEntry.getKey();
            random.setSeed(uuid.getLeastSignificantBits() ^ uuid.getMostSignificantBits());
            float r = random.nextFloat();
            float g = random.nextFloat();
            float b = random.nextFloat();
            for (Map.Entry<BlockPos, List<BlockPos>> entry : gridEntry.getValue().entrySet()) {
                BlockPos pos = entry.getKey();

                VertexConsumer builder = buffers.getBuffer(RenderType.debugFilledBox());

                pStack.pushPose();
                pStack.translate(pos.getX(), pos.getY(), pos.getZ());
                bufferCuboidSolid(builder, pStack.last().pose(), smolBox, r, g, b, 0.25F);
                pStack.popPose();


                VertexConsumer vb = buffers.getBuffer(RenderType.lines());

                for (BlockPos edge : entry.getValue()) {
                    BlockPos offset = edge.subtract(pos);
                    Direction side = BlockHelper.getSide(offset);
                    Vector3f sub = new Vector3f();
                    if (side != null) {
                        Vec3i norm = side.getNormal();
                        sub = new Vector3f(norm.getX(), norm.getY(), norm.getZ());
                        sub.mul((1F / 16F) * 4);
                    }

                    Vector3f start = new Vector3f(pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F);
                    start.add(sub);
                    Vector3f end = new Vector3f(edge.getX() + 0.5F, edge.getY() + 0.5F, edge.getZ() + 0.5F);
                    end.sub(sub);

                    vb.addVertex(pStack.last().pose(), start.x(), start.y(), start.z()).setColor(1F, 0F, 0F, 0.25F);
                    vb.addVertex(pStack.last().pose(), end.x(), end.y(), end.z()).setColor(1F, 0F, 0F, 0.25F);
                }
            }
        }

        buffers.endBatch(RenderType.lines());
        buffers.endBatch(RenderType.debugFilledBox());
        pStack.popPose();
    }

    // region HELPERS
    private static void bufferCuboidSolid(VertexConsumer builder, Matrix4f matrix, AABB c, float r, float g, float b, float a) {

        builder.addVertex(matrix, (float) c.minX, (float) c.maxY, (float) c.minZ).setColor(r, g, b, a);
        builder.addVertex(matrix, (float) c.maxX, (float) c.maxY, (float) c.minZ).setColor(r, g, b, a);
        builder.addVertex(matrix, (float) c.maxX, (float) c.minY, (float) c.minZ).setColor(r, g, b, a);
        builder.addVertex(matrix, (float) c.minX, (float) c.minY, (float) c.minZ).setColor(r, g, b, a);

        builder.addVertex(matrix, (float) c.minX, (float) c.minY, (float) c.maxZ).setColor(r, g, b, a);
        builder.addVertex(matrix, (float) c.maxX, (float) c.minY, (float) c.maxZ).setColor(r, g, b, a);
        builder.addVertex(matrix, (float) c.maxX, (float) c.maxY, (float) c.maxZ).setColor(r, g, b, a);
        builder.addVertex(matrix, (float) c.minX, (float) c.maxY, (float) c.maxZ).setColor(r, g, b, a);

        builder.addVertex(matrix, (float) c.minX, (float) c.minY, (float) c.minZ).setColor(r, g, b, a);
        builder.addVertex(matrix, (float) c.maxX, (float) c.minY, (float) c.minZ).setColor(r, g, b, a);
        builder.addVertex(matrix, (float) c.maxX, (float) c.minY, (float) c.maxZ).setColor(r, g, b, a);
        builder.addVertex(matrix, (float) c.minX, (float) c.minY, (float) c.maxZ).setColor(r, g, b, a);

        builder.addVertex(matrix, (float) c.minX, (float) c.maxY, (float) c.maxZ).setColor(r, g, b, a);
        builder.addVertex(matrix, (float) c.maxX, (float) c.maxY, (float) c.maxZ).setColor(r, g, b, a);
        builder.addVertex(matrix, (float) c.maxX, (float) c.maxY, (float) c.minZ).setColor(r, g, b, a);
        builder.addVertex(matrix, (float) c.minX, (float) c.maxY, (float) c.minZ).setColor(r, g, b, a);

        builder.addVertex(matrix, (float) c.minX, (float) c.minY, (float) c.maxZ).setColor(r, g, b, a);
        builder.addVertex(matrix, (float) c.minX, (float) c.maxY, (float) c.maxZ).setColor(r, g, b, a);
        builder.addVertex(matrix, (float) c.minX, (float) c.maxY, (float) c.minZ).setColor(r, g, b, a);
        builder.addVertex(matrix, (float) c.minX, (float) c.minY, (float) c.minZ).setColor(r, g, b, a);

        builder.addVertex(matrix, (float) c.maxX, (float) c.minY, (float) c.minZ).setColor(r, g, b, a);
        builder.addVertex(matrix, (float) c.maxX, (float) c.maxY, (float) c.minZ).setColor(r, g, b, a);
        builder.addVertex(matrix, (float) c.maxX, (float) c.maxY, (float) c.maxZ).setColor(r, g, b, a);
        builder.addVertex(matrix, (float) c.maxX, (float) c.minY, (float) c.maxZ).setColor(r, g, b, a);
    }
    // endregion
}
