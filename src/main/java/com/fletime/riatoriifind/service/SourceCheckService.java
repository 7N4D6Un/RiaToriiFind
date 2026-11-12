package com.fletime.riatoriifind.service;

import com.fletime.riatoriifind.RiaToriiFind;
import com.fletime.riatoriifind.config.ModConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

public final class SourceCheckService {

    public enum UpdateOutcome {
        // 未能读取远程版本号或下载失败
        FETCH_FAILED,
        // 本地版本不低于远程版本
        UP_TO_DATE,
        // 已下载并原子替换本地文件
        UPDATED,
        // 下载前发现源已切换, 未写入
        ABORTED
    }

    private static final Pattern VERSION_PATTERN = Pattern.compile("\"version\"\\s*:\\s*(\\d+)");

    private SourceCheckService() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static CompletableFuture<Boolean> check(String url, int maxRetries, long delayMillis) {
        return CompletableFuture.supplyAsync(() -> {
            for (int attempt = 0; attempt < maxRetries; attempt++) {
                if (tryFetch(url)) {
                    if (ModConfig.debugMode) RiaToriiFind.LOGGER.info("连通检查成功 (尝试 {}/{})", attempt + 1, maxRetries);
                    return true;
                }
                if (attempt < maxRetries - 1) {
                    if (ModConfig.debugMode) RiaToriiFind.LOGGER.warn("连通检查失败 (尝试 {}/{}), {}ms 后重试", attempt + 1, maxRetries, delayMillis);
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
            if (ModConfig.debugMode) RiaToriiFind.LOGGER.warn("连通检查全部失败 ({} 次)", maxRetries);
            return false;
        });
    }

    // shouldContinue 在读取版本号前后各校验一次, 源切走后不再发起下载
    public static CompletableFuture<UpdateOutcome> updateFromRemote(String url, Path localFile, BooleanSupplier shouldContinue) {
        return CompletableFuture.supplyAsync(() -> {
            if (!shouldContinue.getAsBoolean()) return UpdateOutcome.ABORTED;

            int remoteVer = fetchRemoteVersion(url);
            if (remoteVer < 0) {
                if (ModConfig.debugMode) RiaToriiFind.LOGGER.warn("获取远程版本号失败");
                return UpdateOutcome.FETCH_FAILED;
            }

            int localVer = readLocalVersion(localFile);
            if (localVer >= remoteVer) {
                if (ModConfig.debugMode) RiaToriiFind.LOGGER.info("本地已是最新 (本地 v{} >= 远程 v{})", localVer, remoteVer);
                return UpdateOutcome.UP_TO_DATE;
            }

            if (!shouldContinue.getAsBoolean()) {
                if (ModConfig.debugMode) RiaToriiFind.LOGGER.info("源已切换, 跳过下载");
                return UpdateOutcome.ABORTED;
            }

            if (ModConfig.debugMode) RiaToriiFind.LOGGER.info("远程 (v{}) 新于本地 (v{}), 开始下载", remoteVer, localVer);
            boolean downloaded = downloadFile(url, localFile);
            if (!downloaded) return UpdateOutcome.FETCH_FAILED;
            if (ModConfig.debugMode) RiaToriiFind.LOGGER.info("下载完成");
            return UpdateOutcome.UPDATED;
        });
    }

    private static HttpURLConnection openConnection(String url, int connectTimeout, int readTimeout) throws IOException {
        var conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setRequestProperty("User-Agent", "RiaToriiFind/3.0");
        return conn;
    }

    private static boolean tryFetch(String url) {
        try {
            return openConnection(url, 3000, 3000).getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        }
    }

    // 只取文件头 2KB 解析版本号
    private static int fetchRemoteVersion(String url) {
        try {
            var conn = openConnection(url, 5000, 5000);
            conn.setRequestProperty("Range", "bytes=0-2047");
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) return -1;

            try (InputStream in = conn.getInputStream()) {
                var buf = new byte[2048];
                int len = in.read(buf);
                if (len <= 0) return -1;
                var head = new String(buf, 0, len, StandardCharsets.UTF_8);
                var m = VERSION_PATTERN.matcher(head);
                return m.find() ? Integer.parseInt(m.group(1)) : -1;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    // 先写临时文件再原子替换
    private static boolean downloadFile(String url, Path target) {
        try {
            var conn = openConnection(url, 5000, 10000);
            try (InputStream in = conn.getInputStream()) {
                Files.createDirectories(target.getParent());
                var tmp = Files.createTempFile(target.getParent(), "toriidata", ".tmp");
                try {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                    try {
                        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return true;
                } finally {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException ignored) {
                    }
                }
            }
        } catch (IOException e) {
            return false;
        }
    }

    private static int readLocalVersion(Path file) {
        if (!Files.exists(file)) return -1;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            var el = com.google.gson.JsonParser.parseReader(reader);
            if (el == null || !el.isJsonObject()) return -1;
            var obj = el.getAsJsonObject();
            var ver = obj.get("version");
            if (ver == null || !ver.isJsonPrimitive() || !ver.getAsJsonPrimitive().isNumber()) return -1;
            return ver.getAsInt();
        } catch (Exception e) {
            return -1;
        }
    }
}
