package cofh.thermal.dynamics.common.grid.item;

import cofh.thermal.dynamics.api.grid.ITickableGridNode;
import cofh.thermal.dynamics.common.grid.GridNode;

public class ItemGridNode extends GridNode<ItemGrid> implements ITickableGridNode {

    protected ItemGridNode(ItemGrid grid) {

        super(grid);
    }

    @Override
    public void distributionTick() {

    }

}
