package com.fletime.riatoriifind.config;

import com.fletime.riatoriifind.RiaToriiFind;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

public final class ModConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = FabricLoader.getInstance()
            .getConfigDir().resolve("riatoriifind");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");
    private static final Path LOCAL_DATA_FILE = CONFIG_DIR.resolve("toriidata.json");

    private static final String BUILTIN_LOCAL_PATH = "/assets/" + RiaToriiFind.MOD_ID + "/toriidata.json";

    public static final String SOURCE_LOCAL = "local";
    public static final String SOURCE_RIA_WIKI = "ria-wiki";
    public static final String SOURCE_GITHUB = "github";
    public static final String SOURCE_GITEE = "gitee";

    public static final String RIA_WIKI_URL = "https://wiki.ria.red/wiki/%E7%94%A8%E6%88%B7:FleTime/toriifind.json?action=raw";
    public static final String GITHUB_URL = "https://cdn.jsdelivr.net/gh/7N4D6Un/RiaToriiFind@main/src/main/resources/assets/riatoriifind/toriidata.json";
    public static final String GITEE_URL = "https://raw.giteeusercontent.com/XBbT284/RiaToriiFind/raw/main/src/main/resources/assets/riatoriifind/toriidata.json";

    public static final String[] SOURCES = {SOURCE_LOCAL, SOURCE_RIA_WIKI, SOURCE_GITHUB, SOURCE_GITEE};

    public static volatile String currentSource = "local";
    public static boolean showCheckPopups = true;
    public static boolean debugMode = false;

    // 一次性刷新开关，保存后自动关闭并刷新当前源
    public static boolean refreshSource = false;

    private ModConfig() {
        throw new UnsupportedOperationException("Static config accessor");
    }

    public static void load() {
        try {
            Files.createDirectories(CONFIG_DIR);
        } catch (IOException e) {
            RiaToriiFind.LOGGER.warn("无法创建配置目录 {}: {}", CONFIG_DIR, e.getMessage());
        }
        loadConfigFile();
        if (SOURCE_LOCAL.equals(currentSource)) {
            ensureLocalDataFile();
        }
        if (debugMode) RiaToriiFind.LOGGER.info("配置加载完成，currentSource={}", currentSource);
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            try (Writer writer = Files.newBufferedWriter(CONFIG_FILE, StandardCharsets.UTF_8)) {
                Data data = new Data();
                data.currentSource = currentSource;
                data.showCheckPopups = showCheckPopups;
                data.debugMode = debugMode;
                data.refreshSource = refreshSource;
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            RiaToriiFind.LOGGER.warn("保存配置失败: {}", e.getMessage());
        }
    }

    private static void loadConfigFile() {
        if (!Files.exists(CONFIG_FILE)) {
            currentSource = "local";
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(CONFIG_FILE, StandardCharsets.UTF_8)) {
            Data data = GSON.fromJson(reader, Data.class);
            if (data != null) {
                currentSource = data.currentSource;
                showCheckPopups = data.showCheckPopups;
                debugMode = data.debugMode;
                refreshSource = data.refreshSource;
            }
        } catch (Exception e) {
            currentSource = "local";
            save();
        }
    }

    // 内置版本大于本地时覆盖
    private static void ensureLocalDataFile() {
        if (!Files.exists(LOCAL_DATA_FILE)) {
            if (debugMode) RiaToriiFind.LOGGER.info("本地数据文件不存在，从内置复制");
            copyBuiltinLocalJson();
            return;
        }

        int builtinVer = readBuiltinDataVersion();
        if (builtinVer < 0) {
            if (debugMode) RiaToriiFind.LOGGER.warn("无法读取内置数据版本");
            return;
        }

        int localVer = readLocalValidatedVersion(LOCAL_DATA_FILE);
        if (localVer < 0) {
            if (debugMode) RiaToriiFind.LOGGER.warn("本地数据文件格式无效，将从内置覆盖");
            copyBuiltinLocalJson();
            return;
        }

        if (localVer >= builtinVer) {
            if (debugMode) RiaToriiFind.LOGGER.info("本地数据已是最新 (v{})", localVer);
            return;
        }

        if (debugMode) RiaToriiFind.LOGGER.info("内置数据 (v{}) 新于本地 (v{})，覆盖", builtinVer, localVer);
        copyBuiltinLocalJson();
    }

    private static void copyBuiltinLocalJson() {
        try (InputStream in = ModConfig.class.getResourceAsStream(BUILTIN_LOCAL_PATH)) {
            if (in == null) {
                if (debugMode) RiaToriiFind.LOGGER.warn("内置数据资源不存在: {}", BUILTIN_LOCAL_PATH);
                return;
            }
            Files.createDirectories(CONFIG_DIR);
            Files.copy(in, LOCAL_DATA_FILE, StandardCopyOption.REPLACE_EXISTING);
            if (debugMode) RiaToriiFind.LOGGER.info("已复制内置数据到 {}", LOCAL_DATA_FILE);
        } catch (IOException e) {
            if (debugMode) RiaToriiFind.LOGGER.warn("复制内置数据失败: {}", e.getMessage());
        }
    }

    private static final Pattern VERSION_PATTERN = Pattern.compile("\"version\"\\s*:\\s*(\\d+)");

    private static int readBuiltinDataVersion() {
        try (InputStream in = ModConfig.class.getResourceAsStream(BUILTIN_LOCAL_PATH)) {
            if (in == null) return -1;
            var buf = new byte[2048];
            int len = in.read(buf);
            if (len <= 0) return -1;
            var head = new String(buf, 0, len, StandardCharsets.UTF_8);
            var m = VERSION_PATTERN.matcher(head);
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    // 校验 JSON 结构完整后返回版本号
    private static int readLocalValidatedVersion(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            var el = JsonParser.parseReader(reader);
            if (el == null || !el.isJsonObject()) return -1;

            JsonObject obj = el.getAsJsonObject();

            var ver = obj.get("version");
            if (ver == null || !ver.isJsonPrimitive() || !ver.getAsJsonPrimitive().isNumber()) return -1;
            if (!obj.has("zeroth") || !obj.get("zeroth").isJsonArray()) return -1;
            if (!obj.has("houtu") || !obj.get("houtu").isJsonArray()) return -1;

            return ver.getAsInt();
        } catch (IOException | JsonSyntaxException e) {
            return -1;
        }
    }

    // 返回数据源的远程地址; 本地源或未知源返回 null
    public static String getSourceUrl(String source) {
        if (SOURCE_RIA_WIKI.equals(source)) return RIA_WIKI_URL;
        if (SOURCE_GITHUB.equals(source)) return GITHUB_URL;
        if (SOURCE_GITEE.equals(source)) return GITEE_URL;
        return null;
    }

    public static Path getConfigDir() {
        return CONFIG_DIR;
    }

    public static Path getLocalDataFile() {
        return LOCAL_DATA_FILE;
    }

    public static int getLocalDataVersion() {
        return readLocalValidatedVersion(LOCAL_DATA_FILE);
    }

    public static void syncBuiltinData() {
        ensureLocalDataFile();
    }

    private static class Data {
        String currentSource = "local";
        boolean showCheckPopups = true;
        boolean debugMode = false;
        boolean refreshSource = false;
    }
}
