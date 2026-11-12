package com.fletime.riatoriifind.service;

import com.fletime.riatoriifind.RiaToriiFind;
import com.fletime.riatoriifind.config.ModConfig;

import java.util.concurrent.CompletableFuture;

public final class SourceSyncService {

    public enum Result {
        // 远程版本更高, 已下载并替换本地数据
        UPDATED,
        // 远程版本不高于本地, 保留本地数据
        UP_TO_DATE,
        // 连接或下载失败, 未改动本地数据, 也不切换数据源
        FAILED,
        // 期间数据源已切换或本次同步已被作废, 未改动任何状态
        ABORTED
    }

    private static final Object LOCK = new Object();

    private static CompletableFuture<Result> inFlight;
    private static String inFlightSource;
    private static long generation;

    private SourceSyncService() {
        throw new UnsupportedOperationException("Utility class");
    }

    // 同一个源同一时刻只允许一条同步链; 已有任务在跑时直接共享它的结果
    public static CompletableFuture<Result> refreshRemote() {
        String source = ModConfig.currentSource;
        String url = ModConfig.getSourceUrl(source);
        if (url == null) return CompletableFuture.completedFuture(Result.ABORTED);

        synchronized (LOCK) {
            if (inFlight != null && source.equals(inFlightSource)) return inFlight;

            // 源已变化: 旧任务在下一个守卫点自行放弃, 入口立刻交给新源
            long taskGeneration = ++generation;
            inFlight = null;
            inFlightSource = null;

            CompletableFuture<Result> task = createChain(taskGeneration, source, url);
            inFlight = task;
            inFlightSource = source;
            task.whenComplete((result, error) -> {
                synchronized (LOCK) {
                    // 只清理自己这一代, 避免旧链把后来新建的任务从入口摘掉
                    if (generation == taskGeneration) {
                        inFlight = null;
                        inFlightSource = null;
                    }
                }
            });
            return task;
        }
    }

    // 离开远程源时立即让出入口; 旧链会在下一个守卫点自行放弃
    public static void abandonRemote() {
        synchronized (LOCK) {
            generation++;
            inFlight = null;
            inFlightSource = null;
        }
    }

    private static CompletableFuture<Result> createChain(long taskGeneration, String source, String url) {
        return SourceCheckService.check(url, 5, 3000L)
                .thenCompose(connected -> {
                    if (!wantsSource(taskGeneration, source)) {
                        if (ModConfig.debugMode) RiaToriiFind.LOGGER.info("数据源已切换, 放弃本次远程检查");
                        return CompletableFuture.completedFuture(Result.ABORTED);
                    }

                    if (!connected) {
                        if (ModConfig.debugMode) RiaToriiFind.LOGGER.warn("远程源 {} 连接失败, 保留本地数据", source);
                        return CompletableFuture.completedFuture(Result.FAILED);
                    }

                    return SourceCheckService.updateFromRemote(
                                    url,
                                    ModConfig.getLocalDataFile(),
                                    () -> wantsSource(taskGeneration, source))
                            .thenApply(outcome -> switch (outcome) {
                                case UPDATED -> {
                                    ToriiDataService.reloadCache();
                                    if (ModConfig.debugMode) RiaToriiFind.LOGGER.info("远程数据已更新");
                                    yield Result.UPDATED;
                                }
                                case UP_TO_DATE -> Result.UP_TO_DATE;
                                case FETCH_FAILED -> Result.FAILED;
                                case ABORTED -> Result.ABORTED;
                            });
                })
                .exceptionally(error -> {
                    if (ModConfig.debugMode) RiaToriiFind.LOGGER.warn("远程数据同步异常: {}", error.getMessage());
                    return Result.FAILED;
                });
    }

    private static boolean wantsSource(long taskGeneration, String source) {
        if (!source.equals(ModConfig.currentSource)) return false;
        synchronized (LOCK) {
            return generation == taskGeneration;
        }
    }
}
