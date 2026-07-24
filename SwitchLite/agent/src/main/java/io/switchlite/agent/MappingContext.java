package io.switchlite.agent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global mapping cache and runtime resolver
 * Provides unified access to Minecraft classes/methods/fields across versions
 */
public class MappingContext {
    
    private static Map<String, Object> MAPPINGS = new ConcurrentHashMap<>();
    private static Map<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();
    private static Map<String, MethodHandle> METHOD_CACHE = new ConcurrentHashMap<>();
    private static Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    
    /**
     * Initialize MappingContext after mappings are loaded
     */
    public static void initialize() {
        System.out.println("[MappingContext] Initialized with " + MAPPINGS.size() + " entries");
    }
    
    /**
     * Store mappings from MappingLoader
     */
    public static void storeMappings(Map<String, Object> mappings) {
        MAPPINGS = mappings;
    }
    
    /**
     * Resolve a class by semantic key
     * @param key Semantic key (e.g., "player_class", "velocity_packet")
     * @return Resolved Class object
     */
    public static Class<?> getClass(String key) {
        return CLASS_CACHE.computeIfAbsent(key, k -> {
            Object mapping = MAPPINGS.get(k);
            if (mapping == null) {
                System.err.println("[MappingContext] No mapping found for key: " + k);
                return null;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> entry = (Map<String, String>) mapping;
                String className = entry.get("class");
                if (className == null) {
                    System.err.println("[MappingContext] Missing 'class' in mapping for key: " + k);
                    return null;
                }
                return Class.forName(className);
            } catch (ClassNotFoundException e) {
                System.err.println("[MappingContext] Class not found for key " + k + ": " + e.getMessage());
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
        return METHOD_CACHE.computeIfAbsent(key, k -> {
            Object mapping = MAPPINGS.get(k);
            if (mapping == null) {
                System.err.println("[MappingContext] No method mapping found for key: " + k);
                return null;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> entry = (Map<String, String>) mapping;
                String className = entry.get("class");
                String methodName = entry.get("method");
                if (className == null || methodName == null) {
                    System.err.println("[MappingContext] Missing 'class' or 'method' in mapping for key: " + k);
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
                    System.err.println("[MappingContext] Method '" + methodName + "' not found in " + className + " for key: " + k);
                    return null;
                }
                found.setAccessible(true);
                return MethodHandles.lookup().unreflect(found);
            } catch (ClassNotFoundException e) {
                System.err.println("[MappingContext] Class not found for key " + k + ": " + e.getMessage());
                return null;
            } catch (IllegalAccessException e) {
                System.err.println("[MappingContext] Cannot access method for key " + k + ": " + e.getMessage());
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
        return FIELD_CACHE.computeIfAbsent(key, k -> {
            Object mapping = MAPPINGS.get(k);
            if (mapping == null) {
                System.err.println("[MappingContext] No field mapping found for key: " + k);
                return null;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> entry = (Map<String, String>) mapping;
                String className = entry.get("class");
                String fieldName = entry.get("field");
                if (className == null || fieldName == null) {
                    System.err.println("[MappingContext] Missing 'class' or 'field' in mapping for key: " + k);
                    return null;
                }
                Class<?> clazz = Class.forName(className);
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (ClassNotFoundException e) {
                System.err.println("[MappingContext] Class not found for key " + k + ": " + e.getMessage());
                return null;
            } catch (NoSuchFieldException e) {
                System.err.println("[MappingContext] Field '" + fieldName + "' not found in " + className + " for key: " + k);
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
        return MAPPINGS.get(key);
    }
    
    /**
     * Get a field value from an object using a semantic key.
     * @param obj The target object
     * @param key Semantic key (e.g., "forge:entity_posX")
     * @return The field value
     */
    public static Object getFieldValue(Object obj, String key) {
        try {
            Field field = getField(key);
            if (field == null) return null;
            field.setAccessible(true);
            return field.get(obj);
        } catch (Exception e) {
            System.err.println("[MappingContext] Failed to get field value for key: " + key);
            return null;
        }
    }

    /**
     * Invoke a method on an object using a semantic key.
     * @param obj The target object
     * @param key Semantic key (e.g., "forge:world_getEntityByID")
     * @param args Method arguments
     * @return The method return value
     */
    public static Object invokeMethod(Object obj, String key, Object... args) {
        try {
            MethodHandle handle = getMethod(key);
            if (handle == null) return null;
            return handle.bindTo(obj).invokeWithArguments(args);
        } catch (Throwable e) {
            System.err.println("[MappingContext] Failed to invoke method for key: " + key);
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
