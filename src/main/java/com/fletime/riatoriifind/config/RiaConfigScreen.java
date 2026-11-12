package com.fletime.riatoriifind.config;

import com.fletime.riatoriifind.compat.CompatAccessor;
import com.fletime.riatoriifind.service.SourceSyncService;
import com.fletime.riatoriifind.service.ToriiDataService;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

public final class RiaConfigScreen {

    private static CompletableFuture<SourceSyncService.Result> lastNotifiedTask;

    private RiaConfigScreen() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static Screen create(Screen parent) {
        var prevSource = ModConfig.currentSource;

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("riatoriifind.config.title"));

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();
        ConfigCategory general = builder.getOrCreateCategory(
                Component.translatable("riatoriifind.config.category.general"));

        general.addEntry(entryBuilder
                .startSelector(
                        Component.translatable("riatoriifind.config.source"),
                        ModConfig.SOURCES,
                        ModConfig.currentSource)
                .setDefaultValue(ModConfig.SOURCE_LOCAL)
                .setTooltip(Component.translatable("riatoriifind.config.source.tooltip"))
                .setNameProvider(str -> Component.translatable("riatoriifind.config.source." + str))
                .setSaveConsumer(str -> ModConfig.currentSource = str)
                .build());

        general.addEntry(entryBuilder
                .startBooleanToggle(
                        Component.translatable("riatoriifind.config.show_check_popups"),
                        ModConfig.showCheckPopups)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("riatoriifind.config.show_check_popups.tooltip"))
                .setSaveConsumer(v -> ModConfig.showCheckPopups = v)
                .build());

        general.addEntry(entryBuilder
                .startBooleanToggle(
                        Component.translatable("riatoriifind.config.refresh_source"),
                        ModConfig.refreshSource)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("riatoriifind.config.refresh_source.tooltip"))
                .setSaveConsumer(v -> ModConfig.refreshSource = v)
                .build());

        general.addEntry(entryBuilder
                .startBooleanToggle(
                        Component.translatable("riatoriifind.config.debug_mode"),
                        ModConfig.debugMode)
                .setDefaultValue(false)
                .setTooltip(Component.translatable("riatoriifind.config.debug_mode.tooltip"))
                .setSaveConsumer(v -> ModConfig.debugMode = v)
                .build());

        builder.setSavingRunnable(() -> {
            var needsRefresh = ModConfig.refreshSource;
            if (needsRefresh) {
                ModConfig.refreshSource = false;
            }

            ModConfig.save();
            if (needsRefresh) {
                refreshCurrentSource();
            } else if (!prevSource.equals(ModConfig.currentSource)) {
                performSourceSwitch();
            }
        });

        return builder.build();
    }

    private static void refreshCurrentSource() {
        if (ModConfig.SOURCE_LOCAL.equals(ModConfig.currentSource)) {
            SourceSyncService.abandonRemote();
            ModConfig.syncBuiltinData();
            ToriiDataService.reloadCache();
        } else {
            notifyOnce(SourceSyncService.refreshRemote());
        }
    }

    private static void notifyOnce(CompletableFuture<SourceSyncService.Result> task) {
        synchronized (RiaConfigScreen.class) {
            if (lastNotifiedTask == task) return;
            lastNotifiedTask = task;
        }
        task.thenAccept(result -> {
            if (result == SourceSyncService.Result.ABORTED || !ModConfig.showCheckPopups) return;
            boolean ok = result != SourceSyncService.Result.FAILED;
            Minecraft.getInstance().execute(() ->
                    showToast(ok
                                    ? Component.translatable("riatoriifind.source.check.success.title")
                                    : Component.translatable("riatoriifind.source.check.fallback.title"),
                            ok
                                    ? Component.translatable("riatoriifind.source.check.success.message")
                                    : Component.translatable("riatoriifind.source.check.fallback.message")));
        });
    }

    private static void performSourceSwitch() {
        refreshCurrentSource();
    }

    private static void showToast(Component title, Component message) {
        SystemToast.add(
                CompatAccessor.toastManager(Minecraft.getInstance()),
                new SystemToast.SystemToastId(),
                title, message);
    }
}
