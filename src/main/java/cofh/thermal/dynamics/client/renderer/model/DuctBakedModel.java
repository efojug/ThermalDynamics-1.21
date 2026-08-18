package cofh.thermal.dynamics.client.renderer.model;

import cofh.core.util.helpers.RenderHelper;
import cofh.lib.client.renderer.block.model.BackfaceBakedQuad;
import cofh.lib.client.renderer.block.model.RetexturedBakedQuad;
import cofh.thermal.dynamics.client.model.data.DuctModelData;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.IDynamicBakedModel;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static cofh.lib.util.Constants.DIRECTIONS;
import static cofh.thermal.core.client.ThermalTextures.BLANK_TEXTURE;
import static cofh.thermal.dynamics.client.model.data.DuctModelData.DUCT_MODEL_DATA;

public class DuctBakedModel implements IDynamicBakedModel {

    private static final boolean DEBUG = Boolean.getBoolean("DuctModel.debug");
    private static final int COLOR_OFFSET = findColorOffset();

    private static final DuctModelData INV_DATA = Util.make(new DuctModelData(), data -> {
        data.setInternalConnection(Direction.UP, true);
        data.setInternalConnection(Direction.DOWN, true);
    });

    private final IGeometryBakingContext context;
    private final TextureAtlasSprite particle;
    private final Map<Direction, List<BakedQuad>> centerModel;
    private final Map<Direction, List<BakedQuad>> centerFill;
    private final Map<Direction, List<BakedQuad>> sides;
    private final Map<Direction, List<BakedQuad>> fill;
    private final Map<Direction, List<BakedQuad>> connections;
    private final boolean isInventory;
    private volatile CacheState cacheState = new CacheState();

    public DuctBakedModel(IGeometryBakingContext context, TextureAtlasSprite particle, EnumMap<Direction, List<BakedQuad>> centerModel, EnumMap<Direction, List<BakedQuad>> centerFill, EnumMap<Direction, List<BakedQuad>> sides, EnumMap<Direction, List<BakedQuad>> fill, EnumMap<Direction, List<BakedQuad>> connections, boolean isInventory) {

        this.context = context;
        this.particle = particle;
        this.centerModel = ImmutableMap.copyOf(centerModel);
        this.centerFill = ImmutableMap.copyOf(centerFill);
        this.sides = ImmutableMap.copyOf(sides);
        this.fill = ImmutableMap.copyOf(fill);
        this.connections = ImmutableMap.copyOf(connections);
        this.isInventory = isInventory;
    }

    public void clearCache() {

        // In-flight section compilers retain the old state and cannot repopulate the new cache with stale atlas data.
        cacheState = new CacheState();
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand, @NotNull ModelData extraData, @Nullable RenderType renderType) {

        if (side != null) {
            return Collections.emptyList();
        }
        if (isInventory) {
            extraData = ModelData.builder()
                    .with(DUCT_MODEL_DATA, INV_DATA)
                    .build();
        }
        if (!(extraData.has(DUCT_MODEL_DATA))) {
            return ImmutableList.of();
        }
        DuctModelData modelData = extraData.get(DUCT_MODEL_DATA);
        if (renderType == RenderType.translucent()) {
            return modelData.isFillTranslucent() ? getFillModelFor(modelData) : ImmutableList.of();
        }
        if (renderType == RenderType.cutout() && modelData.isFillTranslucent()) {
            return getShellModelFor(modelData);
        }
        return getModelFor(modelData);
    }

    private List<BakedQuad> getModelFor(DuctModelData modelData) {

        while (true) {
            CacheState state = cacheState;
            List<BakedQuad> modelQuads = DEBUG
                    ? bakeModel(state, modelData, true, true)
                    : state.modelCache.computeIfAbsent(modelData, key -> bakeModel(state, key, true, true));
            if (state == cacheState) {
                return modelQuads;
            }
        }
    }

    private List<BakedQuad> getShellModelFor(DuctModelData modelData) {

        while (true) {
            CacheState state = cacheState;
            List<BakedQuad> modelQuads = DEBUG
                    ? bakeModel(state, modelData, true, false)
                    : state.shellCache.computeIfAbsent(modelData, key -> bakeModel(state, key, true, false));
            if (state == cacheState) {
                return modelQuads;
            }
        }
    }

    private List<BakedQuad> getFillModelFor(DuctModelData modelData) {

        while (true) {
            CacheState state = cacheState;
            List<BakedQuad> modelQuads = DEBUG
                    ? bakeModel(state, modelData, false, true)
                    : state.fillModelCache.computeIfAbsent(modelData, key -> bakeModel(state, key, false, true));
            if (state == cacheState) {
                return modelQuads;
            }
        }
    }

    private List<BakedQuad> bakeModel(CacheState state, DuctModelData modelData, boolean includeShell, boolean includeFill) {

        ImmutableList.Builder<BakedQuad> quads = ImmutableList.builder();
        for (Direction dir : DIRECTIONS) {
            boolean internal = modelData.hasInternalConnection(dir);
            boolean external = modelData.hasExternalConnection(dir);
            ResourceLocation attachment = modelData.getAttachment(dir);

            if (!internal && !external) {
                if (includeShell) {
                    quads.addAll(filterBlank(centerModel.get(dir), false));
                }
                if (includeFill) {
                    List<BakedQuad> fillQuads = rebakeFill(state.centerFillCache, centerFill, modelData.getFill(), modelData.getFillColor(), modelData.isFillLuminous(), dir);
                    quads.addAll(filterBlank(fillQuads, false));
                }
            } else {
                if (includeShell) {
                    quads.addAll(filterBlank(sides.get(dir), modelData.getFill() != null));
                }
                if (includeFill) {
                    List<BakedQuad> fillQuads = rebakeFill(state.fillCache, fill, modelData.getFill(), modelData.getFillColor(), modelData.isFillLuminous(), dir);
                    quads.addAll(filterBlank(fillQuads, false));
                }
                if (includeShell && external) {
                    quads.addAll(filterBlank(rebakeAttachment(state.attachmentCache, connections, attachment, dir), true));
                }
            }
        }
        return quads.build();
    }

    private List<BakedQuad> filterBlank(List<BakedQuad> quads, boolean cullBack) {

        List<BakedQuad> newQuads = new ArrayList<>(quads.size());
        for (BakedQuad quad : quads) {
            if (cullBack && quad instanceof BackfaceBakedQuad || quad.getSprite().contents().name().equals(BLANK_TEXTURE)) {
                // do nothing
            } else {
                newQuads.add(quad);
            }
        }
        return newQuads;
    }

    private List<BakedQuad> rebakeFill(ConcurrentMap<FillCacheKey, List<BakedQuad>> cache, Map<Direction, List<BakedQuad>> raw, @Nullable ResourceLocation texture, int color, boolean luminous, Direction dir) {

        // Easy bail if there are no quads.
        List<BakedQuad> fillQuads = raw.get(dir);
        if (fillQuads.isEmpty()) {
            return ImmutableList.of();
        }
        // Again if there is no texture.
        if (texture == null) {
            return fillQuads;
        }
        return cache.computeIfAbsent(new FillCacheKey(texture, color, luminous, dir), key -> {
            // Grab the sprite
            TextureAtlasSprite sprite = Minecraft.getInstance()
                    .getModelManager()
                    .getAtlas(InventoryMenu.BLOCK_ATLAS)
                    .getSprite(key.texture());

            // Retexture
            List<BakedQuad> newQuads = new ArrayList<>(fillQuads.size());
            for (BakedQuad quad : fillQuads) {
                BakedQuad tintedQuad = RenderHelper.mulColor(quad, key.color());
                BakedQuad retexturedQuad = new RetexturedBakedQuad(applyAlpha(tintedQuad, key.color() >>> 24), sprite);
                if (key.luminous()) {
                    retexturedQuad = net.neoforged.neoforge.client.model.QuadTransformers.settingMaxEmissivity().process(retexturedQuad);
                }
                newQuads.add(retexturedQuad);
            }
            return ImmutableList.copyOf(newQuads);
        });
    }

    private static int findColorOffset() {

        for (int i = 0; i < DefaultVertexFormat.BLOCK.getElements().size(); ++i) {
            if (DefaultVertexFormat.BLOCK.getElements().get(i).usage() == com.mojang.blaze3d.vertex.VertexFormatElement.Usage.COLOR) {
                return DefaultVertexFormat.BLOCK.getOffset(DefaultVertexFormat.BLOCK.getElements().get(i)) / Integer.BYTES;
            }
        }
        return -1;
    }

    private static BakedQuad applyAlpha(BakedQuad quad, int alpha) {

        if (alpha == 0xFF || COLOR_OFFSET < 0) {
            return quad;
        }
        int[] vertices = quad.getVertices().clone();
        int stride = DefaultVertexFormat.BLOCK.getVertexSize() / Integer.BYTES;
        for (int vertex = 0; vertex < 4; ++vertex) {
            int index = vertex * stride + COLOR_OFFSET;
            int existingAlpha = vertices[index] >>> 24;
            int combinedAlpha = existingAlpha * alpha / 0xFF;
            vertices[index] = vertices[index] & 0x00FFFFFF | combinedAlpha << 24;
        }
        return new BakedQuad(vertices, quad.getTintIndex(), quad.getDirection(), quad.getSprite(), quad.isShade());
    }

    private List<BakedQuad> rebakeAttachment(ConcurrentMap<AttachmentCacheKey, List<BakedQuad>> cache, Map<Direction, List<BakedQuad>> raw, @Nullable ResourceLocation texture, Direction dir) {

        // Easy bail if there are no quads.
        List<BakedQuad> connQuads = raw.get(dir);
        if (connQuads.isEmpty()) {
            return ImmutableList.of();
        }
        // Again if there is no texture.
        if (texture == null) {
            return connQuads;
        }
        return cache.computeIfAbsent(new AttachmentCacheKey(texture, dir), key -> {
            // Grab the sprite
            TextureAtlasSprite sprite = Minecraft.getInstance()
                    .getModelManager()
                    .getAtlas(InventoryMenu.BLOCK_ATLAS)
                    .getSprite(key.texture());

            // Retexture
            List<BakedQuad> newQuads = new ArrayList<>(connQuads.size());
            for (BakedQuad quad : connQuads) {
                newQuads.add(new RetexturedBakedQuad(quad, sprite));
            }
            return ImmutableList.copyOf(newQuads);
        });
    }

    private static class CacheState {

        private final ConcurrentMap<DuctModelData, List<BakedQuad>> modelCache = new ConcurrentHashMap<>();
        private final ConcurrentMap<DuctModelData, List<BakedQuad>> shellCache = new ConcurrentHashMap<>();
        private final ConcurrentMap<DuctModelData, List<BakedQuad>> fillModelCache = new ConcurrentHashMap<>();
        private final ConcurrentMap<FillCacheKey, List<BakedQuad>> centerFillCache = new ConcurrentHashMap<>();
        private final ConcurrentMap<FillCacheKey, List<BakedQuad>> fillCache = new ConcurrentHashMap<>();
        private final ConcurrentMap<AttachmentCacheKey, List<BakedQuad>> attachmentCache = new ConcurrentHashMap<>();
    }

    private record FillCacheKey(ResourceLocation texture, int color, boolean luminous, Direction direction) {
    }

    private record AttachmentCacheKey(ResourceLocation texture, Direction direction) {
    }

    //@formatter:off
    @Override public boolean useAmbientOcclusion() { return context.useAmbientOcclusion(); }
    @Override public boolean isGui3d() { return context.isGui3d(); }
    @Override public boolean usesBlockLight() { return context.useBlockLight(); }
    @Override public ItemTransforms getTransforms() { return context.getTransforms(); }
    @Override public boolean isCustomRenderer() { return false; }
    @Override public TextureAtlasSprite getParticleIcon() { return particle; }
    @Override public ItemOverrides getOverrides() { return ItemOverrides.EMPTY; }
    //@formatter:on
}
