package io.doppel.agent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global mapping cache and runtime resolver
 * Provides unified access to Minecraft classes/methods/fields across versions
 *
 * Aliases mechanism:
 *   Each mapping entry can declare an "aliases" array of alternative keys.
 *   When resolving a key, MappingContext first tries exact match, then checks aliases.
 *   This allows shared code to use platform-agnostic keys like "entity_posX"
 *   while platform-specific code uses "forge:entity_posX" or "fabric:entity_posX".
 *
 *   Example JSON:
 *     "forge:entity_posX": {
 *       "class": "net.minecraft.entity.Entity",
 *       "field": "field_70165_t",
 *       "mcp": "posX",
 *       "aliases": ["entity_posX", "posX"]
 *     }
 *
 *   Now both MappingContext.getFieldValue(obj, "forge:entity_posX") and
 *   MappingContext.getFieldValue(obj, "entity_posX") resolve to the same entry.
 *
 * SRG/MCP naming:
 *   In Forge 1.8.9, the runtime uses SRG names (field_70165_t, func_70071_h_)
 *   for fields and methods. MCP names (posX, onUpdate) are only for development.
 *   The "field"/"method" values in JSON are SRG names (runtime names).
 *   The "mcp" field stores the human-readable MCP name for documentation.
 *   If SRG lookup fails, the MCP name is tried as a fallback (handles
 *   non-obfuscated and Forge-added members).
 */
public class MappingContext {

    /**
     * Log a MappingContext error to %TEMP%\doppel-agent.log.
     *
     * System.err is a black hole under javaw.exe (no console attached), so
     * mapping-resolution failures were previously completely invisible in the
     * diagnostic logs. This mirrors the CoreLogger file sink so that a failed
     * semantic key (e.g. a wrong SRG field name) actually surfaces in
     * doppel-agent.log instead of vanishing silently.
     *
     * Deduplicated per message: tick() runs at 20Hz and retries the same
     * missing keys every tick, which used to spam the log thousands of times.
     * Each distinct failure is now reported once.
     */
    private static final java.util.Set<String> REPORTED_ERRS =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private static void logErr(String msg) {
        if (!REPORTED_ERRS.add(msg)) return;
        try {
            String tmp = System.getProperty("java.io.tmpdir");
            if (tmp != null) {
                java.io.File f = new java.io.File(tmp, "doppel-agent.log");
                java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(f, true), true);
                pw.println("[" + new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date())
                        + "] [ERROR] [MappingContext] " + msg);
                pw.close();
            }
        } catch (Exception ignored) {
            // logging must never crash the client
        }
        System.err.println("[MappingContext] " + msg);
    }
    
    private static Map<String, Object> MAPPINGS = new ConcurrentHashMap<>();
    private static Map<String, String> ALIASES = new ConcurrentHashMap<>();
    private static Map<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();
    private static Map<String, MethodHandle> METHOD_CACHE = new ConcurrentHashMap<>();
    private static Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    
    /**
     * Initialize MappingContext after mappings are loaded
     */
    public static void initialize() {
        System.out.println("[MappingContext] Initialized with " + MAPPINGS.size() + " entries, " + ALIASES.size() + " aliases");
    }
    
    /**
     * Store mappings from MappingLoader and register aliases.
     */
    @SuppressWarnings("unchecked")
    public static void storeMappings(Map<String, Object> mappings) {
        MAPPINGS = mappings;
        ALIASES.clear();
        for (Map.Entry<String, Object> entry : mappings.entrySet()) {
            String primaryKey = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                Object aliasesObj = ((Map<String, Object>) value).get("aliases");
                if (aliasesObj instanceof List) {
                    for (Object alias : (List<?>) aliasesObj) {
                        if (alias instanceof String) {
                            ALIASES.put((String) alias, primaryKey);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Resolve a key to its primary mapping key.
     * First tries exact match, then checks aliases.
     */
    private static String resolveKey(String key) {
        if (MAPPINGS.containsKey(key)) {
            return key;
        }
        String resolved = ALIASES.get(key);
        if (resolved != null) {
            return resolved;
        }
        return key; // Return original key — will fail in lookup with clear error
    }
    
    /**
     * Resolve a class by semantic key
     * @param key Semantic key (e.g., "player_class", "velocity_packet")
     * @return Resolved Class object
     */
    public static Class<?> getClass(String key) {
        String resolvedKey = resolveKey(key);
        return CLASS_CACHE.computeIfAbsent(resolvedKey, k -> {
            Object mapping = MAPPINGS.get(k);
            if (mapping == null) {
                logErr("No mapping found for key: " + key + " (resolved: " + k + ")");
                return null;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> entry = (Map<String, String>) mapping;
                String className = entry.get("class");
                if (className == null) {
                    logErr("Missing 'class' in mapping for key: " + k);
                    return null;
                }
                return Class.forName(className);
            } catch (ClassNotFoundException e) {
                logErr("Class not found for key " + k + ": " + e.getMessage());
                return null;
            }
        });
    }
    
    /**
     * Resolve a method by semantic key
     * @param key Semantic key (e.g., "player_get_position")
     * @return MethodHandle for invocation
     */
    public static MethodHandle getMethod(String key) {
        String resolvedKey = resolveKey(key);
        return METHOD_CACHE.computeIfAbsent(resolvedKey, k -> {
            Object mapping = MAPPINGS.get(k);
            if (mapping == null) {
                logErr("No method mapping found for key: " + key + " (resolved: " + k + ")");
                return null;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> entry = (Map<String, String>) mapping;
                String className = entry.get("class");
                String methodName = entry.get("method");
                if (className == null || methodName == null) {
                    logErr("Missing 'class' or 'method' in mapping for key: " + k);
                    return null;
                }
                Class<?> clazz = Class.forName(className);
                // Search declared methods (including private) first, then public inherited
                Method found = null;
                for (Method m : clazz.getDeclaredMethods()) {
                    if (m.getName().equals(methodName)) {
                        found = m;
                        break;
                    }
                }
                if (found == null) {
                    for (Method m : clazz.getMethods()) {
                        if (m.getName().equals(methodName)) {
                            found = m;
                            break;
                        }
                    }
                }
                if (found == null) {
                    // SRG name not found — try MCP fallback (handles non-obfuscated/Forge-added methods)
                    @SuppressWarnings("unchecked")
                    Map<String, String> entryMap = (Map<String, String>) mapping;
                    String mcpName = entryMap.get("mcp");
                    if (mcpName != null && !mcpName.equals(methodName)) {
                        logErr("SRG method '" + methodName + "' not found in " + className + ", trying MCP fallback '" + mcpName + "'");
                        for (Method m : clazz.getDeclaredMethods()) {
                            if (m.getName().equals(mcpName)) {
                                found = m;
                                break;
                            }
                        }
                        if (found == null) {
                            for (Method m : clazz.getMethods()) {
                                if (m.getName().equals(mcpName)) {
                                    found = m;
                                    break;
                                }
                            }
                        }
                    }
                }
                if (found == null) {
                    logErr("Method '" + methodName + "' not found in " + className + " for key: " + k);
                    return null;
                }
                found.setAccessible(true);
                return MethodHandles.lookup().unreflect(found);
            } catch (ClassNotFoundException e) {
                logErr("Class not found for key " + k + ": " + e.getMessage());
                return null;
            } catch (IllegalAccessException e) {
                logErr("Cannot access method for key " + k + ": " + e.getMessage());
                return null;
            }
        });
    }
    
    /**
     * Resolve a field by semantic key
     * @param key Semantic key (e.g., "player_motion_x")
     * @return Field object for reflection access
     */
    public static Field getField(String key) {
        String resolvedKey = resolveKey(key);
        return FIELD_CACHE.computeIfAbsent(resolvedKey, k -> {
            Object mapping = MAPPINGS.get(k);
            if (mapping == null) {
                logErr("No field mapping found for key: " + key + " (resolved: " + k + ")");
                return null;
            }
            String className = null;
            String fieldName = null;
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> entry = (Map<String, String>) mapping;
                className = entry.get("class");
                fieldName = entry.get("field");
                if (className == null || fieldName == null) {
                    logErr("Missing 'class' or 'field' in mapping for key: " + k);
                    return null;
                }
                Class<?> clazz = Class.forName(className);
                Field field = null;
                try {
                    field = clazz.getDeclaredField(fieldName);
                } catch (NoSuchFieldException e) {
                    // SRG name not found — try MCP fallback (handles non-obfuscated/Forge-added fields)
                    @SuppressWarnings("unchecked")
                    Map<String, String> entryMap = (Map<String, String>) mapping;
                    String mcpName = entryMap.get("mcp");
                    if (mcpName != null && !mcpName.equals(fieldName)) {
                        logErr("SRG field '" + fieldName + "' not found in " + className + ", trying MCP fallback '" + mcpName + "'");
                        try {
                            field = clazz.getDeclaredField(mcpName);
                        } catch (NoSuchFieldException e2) {
                            logErr("MCP field '" + mcpName + "' also not found in " + className + " for key: " + k);
                            return null;
                        }
                    } else {
                        logErr("Field '" + fieldName + "' not found in " + className + " for key: " + k);
                        return null;
                    }
                }
                field.setAccessible(true);
                return field;
            } catch (ClassNotFoundException e) {
                logErr("Class not found for key " + k + ": " + e.getMessage());
                return null;
            }
        });
    }
    
    /**
     * Get complete access path for complex operations
     * @param key Semantic key for access chain
     * @return Access path configuration
     */
    public static Object getAccessPath(String key) {
        String resolvedKey = resolveKey(key);
        return MAPPINGS.get(resolvedKey);
    }
    
    /**
     * Get a field value from an object using a semantic key.
     * @param obj The target object
     * @param key Semantic key (e.g., "forge:entity_posX" or "entity_posX")
     * @return The field value
     */
    public static Object getFieldValue(Object obj, String key) {
        if (obj == null) return null; // null receiver: nothing to read (e.g. main-menu mouseOver)
        try {
            Field field = getField(key);
            if (field == null) return null;
            field.setAccessible(true);
            return field.get(obj);
        } catch (Exception e) {
            logErr("Failed to get field value for key: " + key);
            return null;
        }
    }

    /**
     * Invoke a method on an object using a semantic key.
     * @param obj The target object
     * @param key Semantic key (e.g., "forge:world_getEntityByID" or "world_getEntityByID")
     * @param args Method arguments
     * @return The method return value
     */
    public static Object invokeMethod(Object obj, String key, Object... args) {
        try {
            MethodHandle handle = getMethod(key);
            if (handle == null) return null;
            if (obj == null) {
                // Static method (e.g. Minecraft.getMinecraft) — no receiver.
                // bindTo(null) on a zero-arg static MethodHandle throws
                // IllegalStateException "no leading reference parameter",
                // which made mc_getMinecraft return null on every call
                // (silent render failure — confirmed 2026-08-05).
                return handle.invokeWithArguments(args);
            }
            return handle.bindTo(obj).invokeWithArguments(args);
        } catch (Throwable e) {
            logErr("Failed to invoke method for key: " + key + ": " + e);
            return null;
        }
    }

    /**
     * Clear all caches (for hot-reloading)
     */
    public static void clearCache() {
        CLASS_CACHE.clear();
        METHOD_CACHE.clear();
        FIELD_CACHE.clear();
        System.out.println("[MappingContext] Cache cleared");
    }
}
