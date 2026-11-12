package com.fletime.riatoriifind.service;

import com.fletime.riatoriifind.RiaToriiFind;
import com.fletime.riatoriifind.config.ModConfig;

import java.util.concurrent.CompletableFuture;

public final class SourceSyncService {

    public enum Result {
        // 已下载并替换本地数据
        UPDATED,
        // 已连通, 本地已是最新 (版本号读取失败)
        UP_TO_DATE,
        // 连接失败, 已回退到本地源
        FALLBACK_LOCAL,
        // 期间源已切换或本次同步已被作废
        ABORTED
    }

    private static final Object LOCK = new Object();

    private static CompletableFuture<Result> inFlight;
    private static long generation;

    private SourceSyncService() {
        throw new UnsupportedOperationException("Utility class");
    }

    // 同一个时刻只允许一条同步链
    public static CompletableFuture<Result> refreshRemote() {
        synchronized (LOCK) {
            if (inFlight != null) return inFlight;

            long taskGeneration = ++generation;
            CompletableFuture<Result> task = createChain(taskGeneration);
            inFlight = task;
            task.whenComplete((result, error) -> {
                synchronized (LOCK) {
                    if (generation == taskGeneration) inFlight = null;
                }
            });
            return task;
        }
    }

    // 离开远程源时立即让出入口, 之后的刷新才能新建任务
    public static void abandonRemote() {
        synchronized (LOCK) {
            generation++;
            inFlight = null;
        }
    }

    private static CompletableFuture<Result> createChain(long taskGeneration) {
        return SourceCheckService.check(ModConfig.RIA_WIKI_URL, 5, 3000L)
                .thenCompose(connected -> {
                    if (!wantsRemote(taskGeneration)) {
                        if (ModConfig.debugMode) RiaToriiFind.LOGGER.info("源已切换, 放弃本次远程检查");
                        return CompletableFuture.completedFuture(Result.ABORTED);
                    }

                    if (!connected) {
                        if (!wantsRemote(taskGeneration)) {
                            return CompletableFuture.completedFuture(Result.ABORTED);
                        }
                        ModConfig.currentSource = ModConfig.SOURCE_LOCAL;
                        ModConfig.syncBuiltinData();
                        ModConfig.save();
                        ToriiDataService.reloadCache();
                        if (ModConfig.debugMode) RiaToriiFind.LOGGER.warn("远程源连接失败, 已回退到本地源");
                        return CompletableFuture.completedFuture(Result.FALLBACK_LOCAL);
                    }

                    return SourceCheckService.updateFromRemote(
                                    ModConfig.RIA_WIKI_URL,
                                    ModConfig.getLocalDataFile(),
                                    () -> wantsRemote(taskGeneration))
                            .thenApply(outcome -> switch (outcome) {
                                case UPDATED -> {
                                    ToriiDataService.reloadCache();
                                    if (ModConfig.debugMode) RiaToriiFind.LOGGER.info("远程数据已更新");
                                    yield Result.UPDATED;
                                }
                                case UP_TO_DATE, FETCH_FAILED -> Result.UP_TO_DATE;
                                case ABORTED -> Result.ABORTED;
                            });
                })
                .exceptionally(error -> {
                    if (ModConfig.debugMode) RiaToriiFind.LOGGER.warn("远程数据同步异常: {}", error.getMessage());
                    return Result.ABORTED;
                });
    }

    private static boolean wantsRemote(long taskGeneration) {
        if (!ModConfig.SOURCE_RIA_WIKI.equals(ModConfig.currentSource)) return false;
        synchronized (LOCK) {
            return generation == taskGeneration;
        }
    }
}
