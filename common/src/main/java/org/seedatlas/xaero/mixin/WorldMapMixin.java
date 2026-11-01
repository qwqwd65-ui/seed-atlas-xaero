package org.seedatlas.xaero.mixin;

import org.seedatlas.xaero.integration.biome.SeedAtlasBiomeOverlayRenderer;
import org.seedatlas.xaero.integration.marker.SeedAtlasStructureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.WorldMap;

/**
 * Adds the Seed Atlas map layers once Xaero has built the handler they are registered with.
 *
 * <p>Where that happens moved between releases. Up to 26.1.2, {@code WorldMapClientOnly}
 * {@code loadLaterClientRender} both created the map pose stack and assigned
 * {@code WorldMap.mapElementRenderHandler}, so injecting at its return was enough. On 1.21.11 that
 * method was split: {@code WorldMapClientOnly.postInit} only creates the pose stack, and the
 * handler is assigned afterwards by {@code WorldMap.loadLater}, which builds it from that pose
 * stack. Injecting at {@code postInit}'s return therefore ran while the field was still null and
 * threw. The injection point is now the assignment itself, so registration happens exactly when
 * the handler exists and is unaffected by the {@code loadLater} try/catch that also reaches
 * {@code return}.
 */
@Mixin(WorldMap.class)
abstract class WorldMapMixin {
    @Inject(method = "loadLater", at = @At(
        value = "FIELD",
        target = "Lxaero/map/WorldMap;mapElementRenderHandler:Lxaero/map/element/MapElementRenderHandler;",
        opcode = org.objectweb.asm.Opcodes.PUTSTATIC,
        shift = At.Shift.AFTER
    ))
    private void seedAtlas$registerRenderers(CallbackInfo ci) {
        WorldMap.mapElementRenderHandler.add(SeedAtlasBiomeOverlayRenderer.INSTANCE);
        WorldMap.mapElementRenderHandler.add(SeedAtlasStructureRenderer.INSTANCE);
    }
}
