package com.fletime.riatoriifind;

import com.fletime.riatoriifind.command.ToriiFindCommand;
import com.fletime.riatoriifind.config.ModConfig;
import com.fletime.riatoriifind.service.SourceSyncService;
import com.fletime.riatoriifind.service.ToriiDataService;

import net.fabricmc.api.ClientModInitializer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RiaToriiFind implements ClientModInitializer {
    public static final String MOD_ID = "riatoriifind";
    public static final Logger LOGGER = LoggerFactory.getLogger("RiaToriiFind");

    @Override
    public void onInitializeClient() {
        ModConfig.load();
        if (ModConfig.debugMode) LOGGER.info("配置已加载，当前源: {}", ModConfig.currentSource);
        ToriiDataService.preloadAsync();
        ToriiFindCommand.register();
        checkOnlineSource();
    }

    // 仅在当前源为远程时启动异步数据更新, 与配置界面的刷新共享同一条同步链
    private static void checkOnlineSource() {
        if (ModConfig.SOURCE_LOCAL.equals(ModConfig.currentSource)) return;

        if (ModConfig.debugMode) LOGGER.info("开始检查远程数据源更新: {}", ModConfig.currentSource);
        SourceSyncService.refreshRemote();
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
