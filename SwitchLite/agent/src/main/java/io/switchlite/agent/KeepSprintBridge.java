package io.switchlite.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Javassist-injected attack bridge for KeepSprint.
 *
 * Called at the END of EntityPlayer.attackTargetEntityWithCurrentItem (func_71061_d_ on the MC
 * main thread, inside the attack method). Mirrors Raven's ASMEventHandler.onAttackTargetEntityWithCurrentItem:
 * when KeepSprint is enabled, multiply the player's horizontal motion (motionX/motionZ) by the
 * keep factor so the vanilla ~60% attack slowdown is undone — the "no speed drop when attacking"
 * model.
 *
 * The injected bytecode only calls this single no-arg static method (same pattern as
 * RenderBridge.onFrame()), so javassist never has to compile complex expressions. All MC access
 * is done here via reflection / MappingContext-safe class loading.
 */
public class KeepSprintBridge {

    private static volatile ClassLoader gameCL = null;
    private static volatile Boolean keepSprintEnabledChecked = null;

    /**
     * Called at the end of the attack method (main thread). Never throws.
     */
    public static void onAttack() {
        try {
            Object player = getPlayer();
            if (player == null) return;

            float factor = getKeepFactor();
            if (factor <= 0f) return;

            Field mX = entityMotionX();
            Field mZ = entityMotionZ();
            if (mX == null || mZ == null) return;

            double x = mX.getDouble(player);
            double z = mZ.getDouble(player);
            mX.setDouble(player, x * factor);
            mZ.setDouble(player, z * factor);
        } catch (Throwable t) {
            // Silently ignore — never crash the game on an attack.
        }
    }

    private static float getKeepFactor() {
        try {
            ClassLoader cl = gameCL();
            if (cl == null) return 0f;
            // KeepSprint is a Kotlin object: read INSTANCE, then isEnabled() and the active factor.
            Class<?> ks = Class.forName("io.switchlite.adapter.common.module.combat.KeepSprint", true, cl);
            Object inst = ks.getField("INSTANCE").get(null);

            // isEnabled() — Kotlin Module base class getter.
            Method enabled = ks.getMethod("isEnabled");
            Boolean on = (Boolean) enabled.invoke(inst);
            if (!Boolean.TRUE.equals(on)) return 0f;

            // activeKeepFactor — the module keeps this in sync; bridge applies it on attack.
            try {
                Method getKeep = ks.getMethod("getActiveKeepFactor");
                Object v = getKeep.invoke(inst);
                if (v instanceof Number) return ((Number) v).floatValue();
            } catch (NoSuchMethodException e) {
                // fall through to default 1.0
            }
            return 1.0f;
        } catch (Throwable t) {
            return 0f;
        }
    }

    private static Object getPlayer() throws Throwable {
        ClassLoader cl = gameCL();
        if (cl == null) return null;
        Class<?> mc = Class.forName("net.minecraft.client.Minecraft", true, cl);
        Object instance = mc.getMethod("getMinecraft").invoke(null);
        if (instance == null) return null;
        Field thePlayer = mc.getField("thePlayer");
        return thePlayer.get(instance);
    }

    private static Field entityMotionX() {
        try {
            Class<?> entity = Class.forName("net.minecraft.entity.Entity", true, gameCL());
            Field f = entity.getField("motionX");
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field entityMotionZ() {
        try {
            Class<?> entity = Class.forName("net.minecraft.entity.Entity", true, gameCL());
            Field f = entity.getField("motionZ");
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    private static ClassLoader gameCL() {
        if (gameCL != null) return gameCL;
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = ClassLoader.getSystemClassLoader();
            // Find a CL that can see the Minecraft class.
            try {
                Class.forName("net.minecraft.client.Minecraft", false, cl);
                gameCL = cl;
                return gameCL;
            } catch (ClassNotFoundException e) {
                // try LaunchClassLoader
            }
            Class<?> launch = Class.forName("net.minecraft.launchwrapper.Launch", true, cl);
            Field clf = launch.getField("classLoader");
            Object lcl = clf.get(null);
            if (lcl instanceof ClassLoader) {
                gameCL = (ClassLoader) lcl;
            }
        } catch (Throwable ignored) {}
        return gameCL;
    }
}
