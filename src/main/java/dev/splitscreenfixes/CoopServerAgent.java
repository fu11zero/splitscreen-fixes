package dev.splitscreenfixes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.List;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

// Runs ZombieBuddy (and so this mod) inside the server started by "Host".
//
// The splitscreen bugs are fixed on the server (PacketSlots), but CoopMaster.launchServer
// starts the host server as a separate JVM with a fixed command line, without the client's
// -javaagent. This hook copies the ZombieBuddy agent from the client's JVM arguments:
//
//     launchServer(...) {
//         ...
//         CoopServerAgent.addJvmArgs(command);   // inserted
//         command.add("zombie.network.GameServer");
//         ...
//     }
//
// Unless the client set a policy, the server agent gets policy=prompt with a batch approval
// timeout. deny-new is not enough: it loads only jar hashes persisted in mod_approvals.json,
// while a signed jar of a trusted author is approved in memory only, and only under prompt
// (ZB 2.3.4 Loader: isAuthorTrusted vs isJarAllowedByPolicy), so after every rebuild the
// server silently skipped this mod. Under prompt the trusted jars load without a dialog; a
// really new jar shows ZB's dialog while the host starts and is denied after the timeout.
public final class CoopServerAgent implements ClassFileTransformer {
    private static final String TARGET = "zombie/network/CoopMaster";
    private static final String TARGET_DOTTED = TARGET.replace('/', '.');
    private static final String METHOD = "launchServer";
    private static final String METHOD_DESC = "(Ljava/lang/String;Ljava/lang/String;IZ)V";
    private static final String MAIN_CLASS = "zombie.network.GameServer";
    private static final String HOOK = "dev/splitscreenfixes/CoopServerAgent";
    private static final String SERVER_POLICY = "policy=prompt,batch_approval_timeout=30";

    private CoopServerAgent() {}

    public static void install() {
        Instrumentation inst = zombieBuddyInstrumentation();
        if (inst == null) {
            return;
        }
        inst.addTransformer(new CoopServerAgent(), true);
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getName().equals(TARGET_DOTTED)) {
                try {
                    inst.retransformClasses(c);
                } catch (Exception e) {
                    Log.error("retransform " + TARGET_DOTTED + " failed", e);
                }
            }
        }
    }

    // ZombieBuddy keeps the agent Instrumentation in a package-private static field.
    private static Instrumentation zombieBuddyInstrumentation() {
        try {
            Class<?> loader = Class.forName("me.zed_0xff.zombie_buddy.Loader");
            Field f = loader.getDeclaredField("g_instrumentation");
            f.setAccessible(true);
            Instrumentation inst = (Instrumentation) f.get(null);
            if (inst == null) {
                Log.warn("ZombieBuddy Instrumentation is null, the host server will run without the mod");
            }
            return inst;
        } catch (ReflectiveOperationException e) {
            Log.error("can't get ZombieBuddy Instrumentation, the host server will run without the mod", e);
            return null;
        }
    }

    // Called from the hooked launchServer with the server's command line, before the main class.
    public static void addJvmArgs(List<String> command) {
        try {
            List<String> clientArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
            for (String arg : command) {
                if (isZombieBuddyAgent(arg)) {
                    return; // someone already added it
                }
            }
            boolean agentAdded = false;
            for (String arg : clientArgs) {
                if (isZombieBuddyAgent(arg)) {
                    String serverArg = serverAgentArg(arg);
                    command.add(serverArg);
                    agentAdded = true;
                    Log.info("host server JVM arg: " + serverArg);
                }
            }
            if (!agentAdded) {
                Log.warn("no ZombieBuddy agent in the client JVM arguments, the host server runs without the mod");
                return;
            }
            // The dedicated server script (start-server.sh) passes these; CoopMaster doesn't.
            for (String arg : clientArgs) {
                if ((arg.startsWith("--enable-native-access=") || arg.startsWith("--add-exports="))
                        && !command.contains(arg)) {
                    command.add(arg);
                }
            }
        } catch (Throwable t) {
            Log.error("can't add ZombieBuddy to the host server", t);
        }
    }

    private static boolean isZombieBuddyAgent(String arg) {
        String lower = arg.toLowerCase();
        return (lower.startsWith("-javaagent:") && lower.contains("zombiebuddy"))
                || ((lower.startsWith("-agentlib:") || lower.startsWith("-agentpath:")) && lower.contains("zbnative"));
    }

    // -javaagent:<jar>[=<options>]: absolute jar path, policy for an unattended process.
    private static String serverAgentArg(String arg) {
        int colon = arg.indexOf(':');
        int eq = arg.indexOf('=', colon + 1);
        String prefix = arg.substring(0, colon + 1);
        String target = eq < 0 ? arg.substring(colon + 1) : arg.substring(colon + 1, eq);
        String options = eq < 0 ? "" : arg.substring(eq + 1);
        if (prefix.equalsIgnoreCase("-javaagent:")) {
            Path jar = Paths.get(target);
            if (!jar.isAbsolute()) {
                target = Paths.get(System.getProperty("user.dir")).resolve(jar).normalize().toString();
            }
        }
        if (!options.contains("policy=")) {
            options = options.isEmpty() ? SERVER_POLICY : options + "," + SERVER_POLICY;
        }
        return prefix + target + "=" + options;
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> redefined,
                            ProtectionDomain domain, byte[] bytes) {
        if (!TARGET.equals(className)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(bytes);
            // COMPUTE_MAXS: the hook needs one more stack slot. Frames stay as they are:
            // the inserted code is straight-line and leaves the stack unchanged.
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] hooked = {0};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                    if (name.equals(METHOD) && desc.equals(METHOD_DESC)) {
                        return new LaunchHook(mv, hooked);
                    }
                    return mv;
                }
            }, 0);
            if (hooked[0] != 1) {
                Log.warn("expected 1 \"" + MAIN_CLASS + "\" in CoopMaster.launchServer, found " + hooked[0]
                        + " (game updated?). The host server will run without the mod");
                return null;
            }
            Log.info("host server launch hooked");
            return writer.toByteArray();
        } catch (Throwable t) {
            Log.error("transform " + TARGET_DOTTED + " failed", t);
            return null;
        }
    }

    // Before `command.add("zombie.network.GameServer")` the list is on top of the stack:
    // duplicate it and pass it to addJvmArgs.
    private static final class LaunchHook extends MethodVisitor {
        private final int[] hooked;

        LaunchHook(MethodVisitor mv, int[] hooked) {
            super(Opcodes.ASM9, mv);
            this.hooked = hooked;
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (MAIN_CLASS.equals(value)) {
                hooked[0]++;
                super.visitInsn(Opcodes.DUP);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "addJvmArgs", "(Ljava/util/List;)V", false);
            }
            super.visitLdcInsn(value);
        }
    }
}
