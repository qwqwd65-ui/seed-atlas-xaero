package org.seedatlas.xaero.integration.biome;

import java.lang.management.ManagementFactory;

public final class HardwareProfile {
    private static final long MB = 1024L * 1024L;
    private static final int LOGICAL_PROCESSORS = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static final int AUTO_WORKER_THREADS = Math.clamp(LOGICAL_PROCESSORS / 2, 1, 8);
    private static final long MAX_HEAP_MB = Math.max(256L, Runtime.getRuntime().maxMemory() / MB);
    private static final long PHYSICAL_RAM_MB = detectPhysicalRamMb();

    private HardwareProfile() {
    }

    public static int logicalProcessors() {
        return LOGICAL_PROCESSORS;
    }

    public static int autoWorkerThreads() {
        return AUTO_WORKER_THREADS;
    }

    public static long maxHeapMb() {
        return MAX_HEAP_MB;
    }

    public static long physicalRamMb() {
        return PHYSICAL_RAM_MB;
    }

    public static boolean isConstrainedMemory() {
        return MAX_HEAP_MB <= 4600L || (PHYSICAL_RAM_MB > 0L && PHYSICAL_RAM_MB <= 10240L);
    }

    public static boolean isMediumMemory() {
        return !isConstrainedMemory()
            && (MAX_HEAP_MB <= 7000L || (PHYSICAL_RAM_MB > 0L && PHYSICAL_RAM_MB <= 18432L));
    }

    public static int overworldCachedTiles() {
        if (isConstrainedMemory()) {
            return 1024;
        }
        return isMediumMemory() ? 2048 : 4096;
    }

    public static int gpuTileCacheLimit() {
        if (isConstrainedMemory()) {
            return 72;
        }
        return isMediumMemory() ? 112 : 160;
    }

    public static int idleGpuTileCacheLimit() {
        if (isConstrainedMemory()) {
            return 16;
        }
        return isMediumMemory() ? 48 : 96;
    }

    private static long detectPhysicalRamMb() {
        try {
            var bean = ManagementFactory.getOperatingSystemMXBean();
            Class<?> sunBean = Class.forName("com.sun.management.OperatingSystemMXBean");
            if (sunBean.isInstance(bean)) {
                for (String methodName : new String[]{"getTotalMemorySize", "getTotalPhysicalMemorySize"}) {
                    try {
                        Object value = sunBean.getMethod(methodName).invoke(bean);
                        if (value instanceof Number number && number.longValue() > 0L) {
                            return number.longValue() / MB;
                        }
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }
}
