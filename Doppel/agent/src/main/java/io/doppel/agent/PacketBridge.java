package io.doppel.agent;

import java.lang.reflect.Method;

/**
 * ClassLoader bridge between the ASM-injected Fabric packet hooks and the Fabric
 * adapter's decision objects. The packet counterpart of {@link RenderBridge}.
 *
 * Architecture role: this is the packet bridge.
 * - The Transformer rewrites the HEAD of two Fabric 1.21.11 methods (intermediary
 *   names, resolved from the fabric mapping table):
 *     net.minecraft.class_8673.method_52787  sendPacket   (ALL client outbound packets,
 *      play + configuration phases — see fabric:clientCommonNetworkHandler_sendPacket)
 *     net.minecraft.class_2743.method_11817  apply        (EntityVelocityUpdateS2CPacket;
 *      returning early = the packet is never applied, exactly the old listener-HEAD
 *      cancel — see fabric:entityVelocityUpdateS2CPacket_apply)
 * - The injected bytecode reflectively calls {@link #onC2SPacket(Object)} /
 *   {@link #onVelocityPacket(Object)} on this class, passing the packet (or listener)
 *   object. Reflection, not a direct INVOKESTATIC, for the same reason as the Display
 *   hook: the hooked classes are defined by Fabric's Knot ClassLoader while PacketBridge
 *   lives in agent.jar — a direct reference would put a ClassLoader Knot cannot see on
 *   the resolution path and the first call would NoClassDefFoundError into the netty
 *   event loop.
 * - This class delegates ALL decisions to the adapter: onC2SPacket → the registered c2s
 *   decision object's onOutbound(Object) (FabricPacketVault), onVelocityPacket → the
 *   s2c decision object's onVelocityPacket(Object) (FabricVelocityInterceptor). PacketBridge
 *   itself holds no Minecraft types and no policy — it is plumbing only.
 *
 * Delegation chain (one packet):
 *   game method HEAD (ASM hook) → PacketBridge.onC2SPacket/onVelocityPacket
 *     → reflective invoke on the adapter decision object → EventBridge/core decision
 *   true  → hook RETURNs early  → the packet is cancelled (never sent / never applied)
 *   false → hook falls through  → the original body runs, vanilla behaviour
 *
 * Fail-open degradation direction (the invariant that outranks everything else):
 * a broken hook must NEVER block or drop a real packet. No handler installed,
 * handler method unresolved, adapter threw, anything returned null or non-Boolean —
 * every one of those degrades to Boolean.FALSE, the hook falls through, and the
 * packet continues exactly as vanilla would have sent/applied it. The worst case a
 * defect in this bridge can produce is a module that does nothing — never a dead
 * connection or a swallowed packet. The injected bytecode mirrors the same discipline:
 * it wraps the whole reflective call in its own try/catch and rethrows nothing.
 *
 * install() is called from FabricBootstrap (adapter side) once the adapter knows the
 * agent is attached; it resolves the two decision methods reflectively ONCE so the
 * per-packet path is a single cached Method.invoke.
 */
public class PacketBridge {

    /** The adapter's outbound (c2s) decision object — FabricPacketVault. */
    private static volatile Object c2sHandler = null;
    /** The adapter's inbound velocity (s2c) decision object — FabricVelocityInterceptor. */
    private static volatile Object s2cHandler = null;

    /** Cached onOutbound(Object) from the c2s handler's class. Null = half inert. */
    private static volatile Method c2sOnOutbound = null;
    /** Cached onVelocityPacket(Object) from the s2c handler's class. Null = half inert. */
    private static volatile Method s2cOnVelocityPacket = null;

    /**
     * Register the two adapter decision objects and resolve their entry methods ONCE.
     *
     * Both halves are independent: a failure to resolve one leaves only that half inert
     * (fail-open) and must not prevent installing the other. The method contract is
     * loose by design — name, one Object parameter, boolean/Boolean result — because the
     * adapter classes are compiled against Minecraft and must never appear on this side
     * of the ClassLoader boundary as a compile-time type.
     *
     * @param c2sHandler the outbound decision object (FabricPacketVault, Kotlin object → INSTANCE)
     * @param s2cHandler the velocity decision object (FabricVelocityInterceptor, same)
     */
    public static void install(Object c2sHandler, Object s2cHandler) {
        if (c2sHandler != null) {
            PacketBridge.c2sHandler = c2sHandler;
            c2sOnOutbound = resolveDecisionMethod(c2sHandler.getClass(), "onOutbound", "c2s");
        }
        if (s2cHandler != null) {
            PacketBridge.s2cHandler = s2cHandler;
            s2cOnVelocityPacket = resolveDecisionMethod(s2cHandler.getClass(), "onVelocityPacket", "s2c");
        }
    }

    /**
     * Resolve a decision method by contract: named {@code name}, exactly one parameter of
     * type Object, returning boolean or Boolean. setAccessible(true) so package/private
     * Kotlin accessors still work. Resolution failure is logged and the half goes inert —
     * it must never throw out of install().
     */
    private static Method resolveDecisionMethod(Class<?> handlerClass, String name, String half) {
        try {
            for (Method m : handlerClass.getMethods()) {
                if (!name.equals(m.getName())) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 1 || !Object.class.equals(params[0])) continue;
                Class<?> ret = m.getReturnType();
                if (!boolean.class.equals(ret) && !Boolean.class.equals(ret)) continue;
                m.setAccessible(true);
                Agent.log("[PacketBridge] " + half + " decision method resolved: "
                        + handlerClass.getName() + "." + name + " -> " + ret.getName());
                return m;
            }
            Agent.log("[PacketBridge] " + half + " decision method '" + name + "(Object)' not found on "
                    + handlerClass.getName() + " — " + half + " hooks stay pass-through");
        } catch (Throwable t) {
            Agent.log("[PacketBridge] " + half + " decision method resolution failed: "
                    + t.getClass().getName() + ": " + t.getMessage() + " — " + half + " hooks stay pass-through");
        }
        return null;
    }

    /**
     * Outbound decision point — called from the injected HEAD of
     * class_8673.method_52787 (sendPacket) for every packet the client is about to send.
     *
     * @param packet the C2S packet object (typed only on the adapter side)
     * @return Boolean.TRUE to cancel the send (vault hold), Boolean.FALSE to pass through.
     *         Never null, never throws — any failure collapses to FALSE (fail-open).
     */
    public static Object onC2SPacket(Object packet) {
        Method decision = c2sOnOutbound;
        if (decision == null || c2sHandler == null) {
            return Boolean.FALSE;
        }
        try {
            return decision.invoke(c2sHandler, packet);
        } catch (Throwable t) {
            // InvocationTargetException from the adapter, a stale handler object, a class
            // that changed underneath us — none of it may block the send. FALSE = send it.
            Agent.log("[PacketBridge] onC2SPacket decision failed — passing packet through: "
                    + t.getClass().getName() + ": " + t.getMessage());
            return Boolean.FALSE;
        }
    }

    /**
     * Velocity decision point — called from the injected HEAD of
     * class_2743.method_11817 (EntityVelocityUpdateS2CPacket.apply) for every velocity
     * update the client is about to apply.
     *
     * @param packet the S2C velocity packet object (typed only on the adapter side)
     * @return Boolean.TRUE to cancel (the packet is never applied — the old listener-HEAD
     *         cancel semantics), Boolean.FALSE to apply normally. Never null, never throws.
     */
    public static Object onVelocityPacket(Object packet) {
        Method decision = s2cOnVelocityPacket;
        if (decision == null || s2cHandler == null) {
            return Boolean.FALSE;
        }
        try {
            return decision.invoke(s2cHandler, packet);
        } catch (Throwable t) {
            Agent.log("[PacketBridge] onVelocityPacket decision failed — passing packet through: "
                    + t.getClass().getName() + ": " + t.getMessage());
            return Boolean.FALSE;
        }
    }
}
