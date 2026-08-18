package cofh.thermal.dynamics.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import cofh.thermal.dynamics.common.block.entity.duct.ItemDuctBlockEntity;
import cofh.thermal.dynamics.common.grid.item.TravelingItem;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;

public class ItemDuctRenderer implements BlockEntityRenderer<ItemDuctBlockEntity> {

    private final ItemRenderer itemRenderer;

    public ItemDuctRenderer(BlockEntityRendererProvider.Context context) {

        itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(ItemDuctBlockEntity duct, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight, int packedOverlay) {

        for (TravelingItem item : duct.getTravelingItems()) {
            if (item.stack().isEmpty()) {
                continue;
            }
            float travel = Math.min(1.0F, (item.progress() + item.speed() * partialTick) / TravelingItem.DUCT_LENGTH);
            float offset = travel - 0.5F;
            var direction = offset < 0.0F ? item.incomingDirection() : item.direction();
            poseStack.pushPose();
            poseStack.translate(0.5F + direction.getStepX() * offset,
                    0.5F + direction.getStepY() * offset,
                    0.5F + direction.getStepZ() * offset);
            poseStack.scale(0.28F, 0.28F, 0.28F);
            itemRenderer.renderStatic(item.stack(), ItemDisplayContext.FIXED, packedLight, OverlayTexture.NO_OVERLAY, poseStack, buffer, duct.getLevel(), 0);
            poseStack.popPose();
        }
    }

}
