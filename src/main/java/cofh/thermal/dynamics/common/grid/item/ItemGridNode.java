package cofh.thermal.dynamics.common.grid.item;

import cofh.thermal.dynamics.api.grid.IDuct;
import cofh.thermal.dynamics.api.grid.ITickableGridNode;
import cofh.thermal.dynamics.common.attachment.IAttachment;
import cofh.thermal.dynamics.common.grid.GridNode;
import net.minecraft.core.Direction;

import static cofh.lib.util.Constants.DIRECTIONS;

public class ItemGridNode extends GridNode<ItemGrid> implements ITickableGridNode {

    protected ItemGridNode(ItemGrid grid) {

        super(grid);
    }

    @Override
    public void attachmentTick() {

        IDuct<?, ?> duct = gridHost();
        if (duct == null) {
            return;
        }
        for (Direction direction : DIRECTIONS) {
            IAttachment attachment = duct.getAttachment(direction);
            if (attachment.needsTick()) {
                attachment.tick();
            }
        }
    }

    public boolean needsAttachmentTick() {

        IDuct<?, ?> duct = gridHost();
        if (duct == null) {
            return false;
        }
        for (Direction direction : DIRECTIONS) {
            if (duct.getAttachment(direction).needsTick()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void distributionTick() {

    }

}
