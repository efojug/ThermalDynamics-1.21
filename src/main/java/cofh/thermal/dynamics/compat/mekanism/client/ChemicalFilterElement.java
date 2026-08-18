package cofh.thermal.dynamics.compat.mekanism.client;

import cofh.core.client.gui.IGuiAccess;
import cofh.core.client.gui.element.ElementBase;
import cofh.core.util.helpers.RenderHelper;
import com.mojang.blaze3d.systems.RenderSystem;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Supplier;

import org.lwjgl.opengl.GL11;

final class ChemicalFilterElement extends ElementBase {

    private Supplier<ChemicalStack> chemicalSupplier;

    ChemicalFilterElement(IGuiAccess gui, int posX, int posY) {

        super(gui, posX, posY);
    }

    ChemicalFilterElement setChemical(Supplier<ChemicalStack> chemicalSupplier) {

        this.chemicalSupplier = chemicalSupplier;
        return this;
    }

    @Override
    public void drawBackground(GuiGraphics guiGraphics, int mouseX, int mouseY) {

        ChemicalStack chemical = chemicalSupplier.get();
        if (chemical.isEmpty()) {
            return;
        }
        TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(chemical.getChemical().getIcon());
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        RenderHelper.setPosTexShader();
        RenderHelper.setBlockTextureSheet();
        RenderHelper.setShaderColorFromInt(chemical.getChemicalTint());
        // ContainerScreenCoFH already translates the element render matrix to the GUI origin.
        RenderHelper.drawTiledTexture(guiGraphics, posX(), posY(), sprite, width, height);
        RenderHelper.resetShaderColor();
    }

    @Override
    public void addTooltip(List<Component> tooltipList, int mouseX, int mouseY) {

        ChemicalStack chemical = chemicalSupplier.get();
        if (!chemical.isEmpty()) {
            tooltipList.add(Component.translatable(chemical.getTranslationKey()));
        }
        super.addTooltip(tooltipList, mouseX, mouseY);
    }

}
