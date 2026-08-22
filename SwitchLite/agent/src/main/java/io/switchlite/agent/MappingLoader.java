package io.switchlite.agent;

import java.io.*;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Mapping file loader for cross-version compatibility
 * Loads JSON mapping files based on platform and version
 *
 * Two loading modes:
 *   - Embedded (customPath == null): reads from classpath via ClassLoader.getResourceAsStream()
 *   - Filesystem (customPath != null): reads from disk via new File()
 *
 * Mapping file naming convention: v{version_with_underscores}.json
 *   e.g. version "1.8.9" → "v1_8_9.json"
 */
public class MappingLoader {

    private static final String MAPPINGS_DIR = "./mappings";

    /**
     * Build the resource path for a mapping file.
     * Format: mappings/{platform}/v{version}.json
     *   e.g. "mappings/forge/v1_8_9.json"
     */
    private static String buildResourcePath(String platformDir, String version) {
        return "mappings/" + platformDir + "/v" + version.replace(".", "_") + ".json";
    }

    /**
     * Build the filesystem path for a mapping file.
     * Format: {basePath}/{platform}/v{version}.json
     *   e.g. "./mappings/forge/v1_8_9.json"
     */
    private static String buildFilePath(String basePath, String platformDir, String version) {
        return basePath + "/" + platformDir + "/v" + version.replace(".", "_") + ".json";
    }

    /**
     * Load mappings for specified platform and version
     * @param platform "Forge" or "Fabric"
     * @param version Minecraft version (e.g., "1.8.9", "1.20.1")
     * @param customPath Optional custom mappings path (null = use embedded classpath resources)
     */
    public static void loadMappings(String platform, String version, String customPath) throws IOException {
        System.out.println("[MappingLoader] Loading mappings for " + platform + " " + version);

        String platformDir = platform.toLowerCase();
        boolean embedded = (customPath == null);

        Map<String, Object> mappings;

        if (embedded) {
            // ── Embedded mode: load from classpath (inside jar) ──
            String resourcePath = buildResourcePath(platformDir, version);
            System.out.println("[MappingLoader] Embedded mode, resource: " + resourcePath);

            InputStream is = MappingLoader.class.getClassLoader().getResourceAsStream(resourcePath);
            if (is == null) {
                // Fallback: tolerate version tokens that a launcher truncated/spoofed. The only
                // 1.8-family SRG layout we ship is 1.8.9, and all Forge 1.8.x runtimes share it,
                // so if the requested file is missing, try v1_8_9.json before giving up.
                String fallback = "";
                if (version.startsWith("1.8") && !version.equals("1.8.9")) {
                    fallback = "mappings/" + platformDir + "/v1_8_9.json";
                    System.out.println("[MappingLoader] '" + version + "' not found, falling back to: " + fallback);
                    is = MappingLoader.class.getClassLoader().getResourceAsStream(fallback);
                }

                if (is == null) {
                    System.err.println("[MappingLoader] Embedded mapping not found: " + resourcePath);
                    throw new FileNotFoundException("Base mapping not found (embedded): " + resourcePath);
                }

                // Log which resource actually resolved.
                System.out.println("[MappingLoader] Resolved from fallback resource: " + fallback);
            }

            try {
                mappings = parseJsonStream(is, resourcePath);
            } finally {
                is.close();
            }

            // For Fabric, load delta patches from classpath
            if ("Fabric".equalsIgnoreCase(platform)) {
                loadDeltaPatchesEmbedded(version, mappings);
            }
        } else {
            // ── Filesystem mode: load from disk ──
            String mappingsPath = customPath;
            String baseFilePath = buildFilePath(mappingsPath, platformDir, version);
            File baseJson = new File(baseFilePath);

            System.out.println("[MappingLoader] Filesystem mode, path: " + baseFilePath);

            if (!baseJson.exists()) {
                System.err.println("[MappingLoader] Base mapping file not found: " + baseFilePath);
                throw new FileNotFoundException("Base mapping not found: " + baseFilePath);
            }

            mappings = parseJsonFile(baseJson);

            // For Fabric, load delta patches from filesystem
            if ("Fabric".equalsIgnoreCase(platform)) {
                loadDeltaPatchesFilesystem(mappingsPath, version, mappings);
            }
        }

        // Store in MappingContext
        MappingContext.storeMappings(mappings);

        System.out.println("[MappingLoader] Loaded " + mappings.size() + " mapping entries");
    }

    // ═══════════════════════════════════════════
    //  Delta patch loading
    // ═══════════════════════════════════════════

    /**
     * Load delta patches from embedded classpath (Fabric only)
     */
    private static void loadDeltaPatchesEmbedded(String version, Map<String, Object> baseMappings)
            throws IOException {
        String deltaResource = "mappings/fabric/deltas/v" + version.replace(".", "_") + ".json";
        InputStream is = MappingLoader.class.getClassLoader().getResourceAsStream(deltaResource);
        if (is != null) {
            System.out.println("[MappingLoader] Applying embedded delta patch: " + deltaResource);
            try {
                Map<String, Object> deltas = parseJsonStream(is, deltaResource);
                mergeMappings(baseMappings, deltas);
            } finally {
                is.close();
            }
        }
    }

    /**
     * Load delta patches from filesystem (Fabric only)
     */
    private static void loadDeltaPatchesFilesystem(String mappingsPath, String version, Map<String, Object> baseMappings)
            throws IOException {
        String deltaFile = mappingsPath + "/fabric/deltas/v" + version.replace(".", "_") + ".json";
        File deltaJson = new File(deltaFile);

        if (deltaJson.exists()) {
            System.out.println("[MappingLoader] Applying delta patch: " + deltaFile);
            Map<String, Object> deltas = parseJsonFile(deltaJson);
            mergeMappings(baseMappings, deltas);
        }
    }

    // ═══════════════════════════════════════════
    //  JSON parsing
    // ═══════════════════════════════════════════

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Parse a JSON mapping file from disk into a flat Map.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonFile(File file) throws IOException {
        System.out.println("[MappingLoader] Parsing JSON: " + file.getAbsolutePath());
        Map<String, Object> result = MAPPER.readValue(
            file,
            new TypeReference<Map<String, Object>>() {}
        );
        System.out.println("[MappingLoader] Parsed " + result.size() + " entries from " + file.getName());
        return result;
    }

    /**
     * Parse a JSON mapping stream from classpath into a flat Map.
     * @param sourceDesc description for logging (e.g. resource path)
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonStream(InputStream is, String sourceDesc) throws IOException {
        System.out.println("[MappingLoader] Parsing embedded JSON: " + sourceDesc);
        Map<String, Object> result = MAPPER.readValue(
            is,
            new TypeReference<Map<String, Object>>() {}
        );
        System.out.println("[MappingLoader] Parsed " + result.size() + " entries from " + sourceDesc);
        return result;
    }

    /**
     * Merge delta mappings into base mappings
     */
    private static void mergeMappings(Map<String, Object> base, Map<String, Object> deltas) {
        // TODO: Implement recursive merge logic
        base.putAll(deltas);
    }
}
