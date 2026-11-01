package org.seedatlas.xaero.integration.biome;

import java.lang.ref.SoftReference;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;

final class VanillaLookup {
    private static SoftReference<HolderLookup.Provider> cachedLookup = new SoftReference<>(null);

    private VanillaLookup() {
    }

    static synchronized HolderLookup.Provider get() {
        HolderLookup.Provider lookup = cachedLookup.get();
        if (lookup == null) {
            lookup = VanillaRegistries.createLookup();
            cachedLookup = new SoftReference<>(lookup);
        }
        return lookup;
    }
}
