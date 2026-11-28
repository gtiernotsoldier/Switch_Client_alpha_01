package io.doppel.adapter.common.mapping;

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
 *   Now both MappingContext.getFieldValue(obj, "forge:entity_onGround") and
 *   MappingContext.getFieldValue(obj, "entity_onGround") resolve to the same entry.
 *   (Use invokeMethod() for method entries, e.g. "fabric:entity_posX" binds to
 *   Entity.getX on 1.21.11 — position has no primitive fields there.)
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
    /**
     * Method cache keyed by BOTH the primary mapping key and every alias/semantic
     * key that resolved to it. The hot path — [invokeMethod] on the 20 Hz tick —
     * is one lookup on the key the caller actually passed; the resolution chain
     * (primary-map probe, alias probe, reflection) runs only on the first call.
     *
     * Caches the reflective [Method], not a MethodHandle: `Method.invoke` on an
     * accessible method JIT-inflates to a near-direct call, while the generic
     * `bindTo(...).invokeWithArguments(...)` chain pays an adapter and an
     * Object[] boxing on every invocation. [getMethod] still hands out
     * MethodHandles for the cold-path callers that cache them in `by lazy`.
     */
    private static Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    /**
     * Same dual-key caching as [METHOD_CACHE]: one lookup per field read on the
     * hot path, resolution only on the first call.
     */
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
        Method found = resolveMethod(key);
        if (found == null) return null;
        try {
            return MethodHandles.lookup().unreflect(found);
        } catch (IllegalAccessException e) {
            logErr("Cannot access method for key " + key + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * The hot-path resolver behind [invokeMethod] and [getMethod]: the cached
     * [Method] under whichever key the caller used, resolved on first call.
     */
    private static Method resolveMethod(String key) {
        Method cached = METHOD_CACHE.get(key);
        if (cached != null) return cached;

        String resolvedKey = resolveKey(key);
        if (!resolvedKey.equals(key)) {
            Method viaPrimary = METHOD_CACHE.get(resolvedKey);
            if (viaPrimary != null) {
                METHOD_CACHE.put(key, viaPrimary);
                return viaPrimary;
            }
        }

        Method found = METHOD_CACHE.computeIfAbsent(resolvedKey, k -> resolveMethodEntry(key, k));
        if (found != null && !resolvedKey.equals(key)) {
            // Remember the alias the caller actually used, so the next call is
            // a single lookup.
            METHOD_CACHE.put(key, found);
        }
        return found;
    }

    private static Method resolveMethodEntry(String askedKey, String k) {
        Object mapping = MAPPINGS.get(k);
        if (mapping == null) {
            logErr("No method mapping found for key: " + askedKey + " (resolved: " + k + ")");
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> entry = (Map<String, String>) mapping;
            String className = entry.get("class");
            String methodName = entry.get("method");
            if (className == null || methodName == null) {
                // The usual cause is calling invokeMethod() on a FIELD entry. Say so
                // explicitly: the caller turns the null into a default value, so the only
                // symptom is a feature that quietly does nothing — which is exactly how
                // forge:keybinding_keyCode disabled every physical-key check.
                Object fieldName = entry.get("field");
                if (className != null && fieldName != null) {
                    logErr("Mapping '" + k + "' is a FIELD (" + className + "." + fieldName
                            + ") but was read with invokeMethod() — use getFieldValue()");
                } else {
                    logErr("Missing 'class' or 'method' in mapping for key: " + k);
                }
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
            return found;
        } catch (ClassNotFoundException e) {
            logErr("Class not found for key " + k + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Resolve a field by semantic key
     * @param key Semantic key (e.g., "player_motion_x")
     * @return Field object for reflection access
     */
    public static Field getField(String key) {
        Field cached = FIELD_CACHE.get(key);
        if (cached != null) return cached;

        String resolvedKey = resolveKey(key);
        if (!resolvedKey.equals(key)) {
            Field viaPrimary = FIELD_CACHE.get(resolvedKey);
            if (viaPrimary != null) {
                FIELD_CACHE.put(key, viaPrimary);
                return viaPrimary;
            }
        }

        Field found = FIELD_CACHE.computeIfAbsent(resolvedKey, k -> resolveFieldEntry(key, k));
        if (found != null && !resolvedKey.equals(key)) {
            // Remember the alias the caller actually used, so the next read is
            // a single lookup.
            FIELD_CACHE.put(key, found);
        }
        return found;
    }

    private static Field resolveFieldEntry(String askedKey, String k) {
        Object mapping = MAPPINGS.get(k);
        if (mapping == null) {
            logErr("No field mapping found for key: " + askedKey + " (resolved: " + k + ")");
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
                // Mirror of the message in getMethod(): name the mismatch instead of
                // leaving the reader to guess which of class/field/method is wrong.
                Object methodName = entry.get("method");
                if (className != null && methodName != null) {
                    logErr("Mapping '" + k + "' is a METHOD (" + className + "." + methodName
                            + ") but was read with getFieldValue() — use invokeMethod()");
                } else {
                    logErr("Missing 'class' or 'field' in mapping for key: " + k);
                }
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
     * Return the RAW class/member strings behind a semantic key — for callers that must
     * act on names only, before the target class exists.
     *
     * WHY a separate accessor: every other resolver here (getClass/getMethod/getField)
     * calls Class.forName() on the mapping's class. The agent's Transformer runs inside
     * ClassFileTransformer.transform(), i.e. DURING class loading — the target class
     * (net.minecraft.class_8673 / class_2743 on Fabric) is not loaded yet, and a
     * Class.forName() from inside transform() would either fail (agent CL cannot see the
     * game classes) or recurse into the very class-loading round it is supposed to serve.
     * The Transformer therefore needs the bare strings: it compares them against the
     * className argument it was handed and injects bytecode — no resolution of game types.
     *
     * Deliberately cache-free: the same resolveKey/alias logic as the runtime accessors,
     * a straight read of the raw entry map, no CLASS/METHOD/FIELD cache traffic. Transform
     * -time calls are rare (two keys, resolved once per JVM and then held in the
     * Transformer's own volatile fields), so the cache would be dead weight and another
     * invalidation surface for clearCache() to get wrong.
     *
     * @param key Semantic key (e.g. "fabric:clientCommonNetworkHandler_sendPacket")
     * @return {@code new String[] {classNameWithDots, memberName, kind}} where kind is
     *         "method" or "field" — or null when the key has no usable entry (wrong table
     *         loaded, or the entry lacks class/member). Failures are reported through the
     *         same logErr channel as every other resolver so a dead key surfaces in
     *         doppel-agent.log instead of silently leaving a hook uninjected.
     */
    public static String[] rawEntry(String key) {
        try {
            String resolvedKey = resolveKey(key);
            Object mapping = MAPPINGS.get(resolvedKey);
            if (!(mapping instanceof Map)) {
                // Mirrors the message shape of getMethod()/getField(): say WHICH key failed
                // and what it resolved to — a wrong-platform table (Forge table loaded on a
                // Fabric launch) must not look identical to a typo'd key.
                logErr("No mapping found for key: " + key + " (resolved: " + resolvedKey + ")");
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) mapping;
            String className = entry.get("class") instanceof String ? (String) entry.get("class") : null;
            String methodName = entry.get("method") instanceof String ? (String) entry.get("method") : null;
            String fieldName = entry.get("field") instanceof String ? (String) entry.get("field") : null;
            String member = methodName != null ? methodName : fieldName;
            String kind = methodName != null ? "method" : fieldName != null ? "field" : null;
            if (className == null || member == null || kind == null) {
                // Same discipline as the kind-mismatch messages in getMethod()/getField():
                // name the actual defect instead of letting the caller guess.
                logErr("Mapping '" + key + "' carries no usable {class, method|field} — rawEntry cannot resolve it");
                return null;
            }
            return new String[] { className, member, kind };
        } catch (Throwable t) {
            // Transform-time must never throw into the JVMTI callback — a null return
            // leaves the hook uninjected (fail-open), a throw would abort class loading.
            logErr("rawEntry failed for key: " + key + ": " + t);
            return null;
        }
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
            Class<?> declaring = field.getDeclaringClass();
            if (!declaring.isInstance(obj)) {
                // A wrong receiver normally surfaces as the JDK's own confusing message from
                // Field.get — "Can not set <type> field <class>.<field> to <class>", which reads
                // like a setter error even though nothing was written (sun.reflect's ensureObj
                // uses that wording for reads too). Name the actual mistake instead: this is how
                // a player object was passed where the Minecraft handle was meant.
                logErr("Mapping '" + key + "' is declared on " + declaring.getName()
                        + " but the receiver is " + obj.getClass().getName() + " — wrong object passed");
                return null;
            }
            // setAccessible happened once at resolve time; re-asserting it per call
            // was a redundant security check on every field read of every tick.
            return field.get(obj);
        } catch (Exception e) {
            // Include the detail: a bare "failed" line is indistinguishable from a mapping
            // error, an access problem and a stale reflection cache.
            logErr("Failed to get field value for key: " + key + ": " + e.getClass().getName() + ": " + e.getMessage());
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
            Method method = resolveMethod(key);
            if (method == null) return null;
            // Plain reflective invoke on the cached accessible Method: it
            // JIT-inflates to a near-direct call, and handles both static
            // (obj == null) and instance receivers without the per-call
            // bindTo adapter the generic MethodHandle path needed.
            return method.invoke(obj, args);
        } catch (Throwable e) {
            // Method.invoke wraps whatever the target threw in
            // InvocationTargetException; the useful message is in the cause.
            Throwable reported = (e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null)
                    ? e.getCause() : e;
            logErr("Failed to invoke method for key: " + key + ": " + reported);
            return null;
        }
    }

    /**
     * Resolve every entry in the mapping table once and report how many are
     * actually usable.
     *
     * This exists because the table silently rotted: five entries named SRG
     * members that do not exist in 1.8.9 at all, and two more named members
     * that exist but are a DIFFERENT method (func_72945_a is
     * getCollidingBoundingBoxes, func_70027_ad is not canBeCollidedWith). Every
     * one of them failed closed — the caller caught the exception, treated it
     * as "no", and the feature died without a word. Nothing surfaced until the
     * raw log was read line by line.
     *
     * Resolution only: classes and members are looked up, nothing is invoked,
     * so this is deterministic and safe to run at startup.
     *
     * @return a one-line health summary, plus the dead keys when there are any
     */
    public static String selfCheck() {
        int ok = 0;
        int dead = 0;
        StringBuilder deadKeys = new StringBuilder();
        for (String key : new java.util.ArrayList<>(MAPPINGS.keySet())) {
            Object raw = MAPPINGS.get(key);
            if (!(raw instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, String> entry = (Map<String, String>) raw;
            boolean isField = entry.get("field") != null;
            boolean isMethod = entry.get("method") != null;
            if (!isField && !isMethod) continue;
            boolean resolved;
            try {
                resolved = isField ? (getField(key) != null) : (getMethod(key) != null);
            } catch (Throwable t) {
                resolved = false;
            }
            if (resolved) {
                ok++;
            } else {
                dead++;
                if (deadKeys.length() < 600) {
                    if (deadKeys.length() > 0) deadKeys.append(", ");
                    deadKeys.append(key);
                }
            }
        }
        int total = ok + dead;
        if (dead == 0) {
            return "MAPPINGS " + ok + "/" + total + " resolved — every key usable";
        }
        return "MAPPINGS " + ok + "/" + total + " resolved — DEAD KEYS: " + deadKeys;
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
