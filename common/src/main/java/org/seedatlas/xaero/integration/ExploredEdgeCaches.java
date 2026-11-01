package org.seedatlas.xaero.integration;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Xaero caches its zoomed-out textures on disk. Ones built before Seed Atlas kept unexplored texels out
 * of the average carry dark seams around explored terrain, often along region edges, where missing
 * quadrants used to be filled with opaque black. Each such cache is deleted the first time Xaero looks for
 * it, and Xaero rebuilds it from the regions below, as it does for a new map.
 */
public final class ExploredEdgeCaches {
    private static final Logger LOGGER = LoggerFactory.getLogger("seedatlas_xaero");
    /** Its age in a cache folder marks when that folder's textures started being built without seams. */
    private static final String MARKER = "seedatlas_explored_edges";
    private static final int MAX_VERIFIED_FILES = 4096;
    private static final ConcurrentHashMap<Path, Long> BUILT_CLEAN_SINCE = new ConcurrentHashMap<>();
    private static final Set<Path> VERIFIED_CLEAN = ConcurrentHashMap.newKeySet();

    private ExploredEdgeCaches() {
    }

    /** Called with the path of one cache file, in Xaero's cache/level folder, before Xaero reads or writes it. */
    public static void dropIfOutdated(File cache) {
        if (cache == null) return;
        Path cachePath = cache.toPath();
        if (VERIFIED_CLEAN.contains(cachePath)) return;
        Path levelFolder = cachePath.getParent();
        Path cacheFolder = levelFolder == null ? null : levelFolder.getParent();
        if (cacheFolder == null) return;
        long since = BUILT_CLEAN_SINCE.computeIfAbsent(cacheFolder, ExploredEdgeCaches::markedSince);
        try {
            if (Files.exists(cachePath)) {
                if (Files.getLastModifiedTime(cachePath).toMillis() < since) {
                    Files.delete(cachePath);
                } else {
                    rememberClean(cachePath);
                }
            } else {
                // Any cache written from this point on is built with Seed Atlas active.
                rememberClean(cachePath);
            }
        } catch (IOException exception) {
            LOGGER.debug("Could not drop outdated map cache {}", cache, exception);
        }
    }

    private static void rememberClean(Path cachePath) {
        if (VERIFIED_CLEAN.size() >= MAX_VERIFIED_FILES) {
            VERIFIED_CLEAN.clear();
        }
        VERIFIED_CLEAN.add(cachePath);
    }

    private static long markedSince(Path cacheFolder) {
        Path marker = cacheFolder.resolve(MARKER);
        try {
            try {
                Files.writeString(marker, "Seed Atlas rebuilds zoomed-out map textures cached before this file.\n",
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException ignored) {
                // Keep the original cutoff even if another instance created it concurrently.
            }
            return Files.getLastModifiedTime(marker).toMillis();
        } catch (IOException exception) {
            // Without a marker, nothing can tell old caches from new ones: keep them all.
            LOGGER.debug("Could not mark map cache folder {}", cacheFolder, exception);
            return Long.MIN_VALUE;
        }
    }
}
