package cofh.thermal.dynamics.compat.mekanism.client.jei;

import cofh.core.client.gui.ContainerScreenCoFH;
import cofh.thermal.dynamics.compat.mekanism.inventory.ChemicalServoAttachmentMenu;
import mekanism.api.chemical.ChemicalStack;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.List;

/**
 * Allows Mekanism's JEI ChemicalStack ingredients to populate Chemical duct filter slots.
 */
public final class ChemicalFilterGhostHandler implements IGhostIngredientHandler<ContainerScreenCoFH> {

    @Override
    public <I> List<Target<I>> getTargetsTyped(ContainerScreenCoFH gui, ITypedIngredient<I> ingredient, boolean doStart) {

        if (!(gui.getMenu() instanceof ChemicalServoAttachmentMenu menu) || !(ingredient.getIngredient() instanceof ChemicalStack chemical) || chemical.isEmpty()) {
            return List.of();
        }
        List<Target<I>> targets = new ArrayList<>();
        for (int i = 0; i < menu.getFilterSize(); ++i) {
            Slot slot = menu.getSlot(i);
            int filterSlot = i;
            targets.add(new Target<>() {
                private final Rect2i area = new Rect2i(gui.getGuiLeft() + slot.x, gui.getGuiTop() + slot.y, 16, 16);

                @Override
                public Rect2i getArea() {

                    return area;
                }

                @Override
                public void accept(I ignored) {

                    menu.setFilterChemical(filterSlot, chemical);
                }
            });
        }
        return targets;
    }

    @Override
    public void onComplete() {

    }

}
