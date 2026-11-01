package org.seedatlas.xaero.integration;

import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import xaero.lib.client.graphics.XaeroRenderType;
import xaero.lib.client.graphics.shader.BuiltInCustomUniforms;
import xaero.lib.client.graphics.shader.LibShaders;
import xaero.map.WorldMap;
import xaero.map.graphics.CustomRenderTypes;

/**
 * Xaero's render types for explored terrain and its zoomed-out textures, with fragment shaders that
 * leave unexplored texels out of filtering. Xaero's own blend them in as black, which only shows once
 * the seed layer, not black, surrounds the explored terrain.
 *
 * <p>1.21.11 folds blend and depth state directly into {@link RenderPipeline}, and its builder
 * defaults to {@code LEQUAL_DEPTH_TEST} with depth writes enabled: {@link #map()} keeps those
 * defaults while preserving destination alpha like {@code XaeroRenderType.RP_MAP}, whereas
 * {@link #branch()} explicitly disables blending, depth testing and depth writes to match
 * {@code XaeroRenderType.RP_MAP_BRANCH}.
 */
public final class ExploredTerrainRenderTypes {
    private static final Supplier<GpuSampler> NEAREST = () -> RenderSystem.getDevice()
        .createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.of(0.0));

    /** Xaero's map writes its colour and keeps the target's alpha. */
    private static final BlendFunction KEEP_DEST_ALPHA =
        new BlendFunction(SourceFactor.ONE, DestFactor.ZERO, SourceFactor.ZERO, DestFactor.ONE);

    private static RenderType map;
    private static RenderType branch;

    private ExploredTerrainRenderTypes() {
    }

    /** Used in place of Xaero's type; any other type passes through. */
    public static RenderType replace(RenderType type) {
        if (type == CustomRenderTypes.MAP) return map();
        if (type == CustomRenderTypes.MAP_BRANCH) return branch();
        return type;
    }

    private static RenderType map() {
        if (map == null) {
            RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("seedatlas_xaero", "pipeline/explored_map"))
                .withVertexShader(LibShaders.WORLD_MAP)
                .withFragmentShader(Identifier.fromNamespaceAndPath("seedatlas_xaero", "core/explored_map"))
                .withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS)
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withSampler("Sampler0")
                .withUniform(BuiltInCustomUniforms.BRIGHTNESS.name(), BuiltInCustomUniforms.BRIGHTNESS.type())
                .withUniform(BuiltInCustomUniforms.WITH_LIGHT.name(), BuiltInCustomUniforms.WITH_LIGHT.type())
                .withBlend(KEEP_DEST_ALPHA)
                .withCull(false)
                .build();
            map = XaeroRenderType.createRenderType("seedatlas_explored_map",
                RenderSetup.builder(pipeline).withTexture("Sampler0", WorldMap.guiTextures, NEAREST));
        }
        return map;
    }

    private static RenderType branch() {
        if (branch == null) {
            RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("seedatlas_xaero", "pipeline/explored_map_branch"))
                .withVertexShader(LibShaders.WORLD_MAP_BRANCH)
                .withFragmentShader(Identifier.fromNamespaceAndPath("seedatlas_xaero", "core/explored_map_branch"))
                .withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS)
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withSampler("Sampler0")
                .withoutBlend()
                .withCull(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .build();
            branch = XaeroRenderType.createRenderType("seedatlas_explored_map_branch",
                RenderSetup.builder(pipeline).withTexture("Sampler0", WorldMap.guiTextures, NEAREST));
        }
        return branch;
    }
}
