package dev.splitscreenfixes;

public final class Log {
    private static final String PREFIX = "[SplitscreenFixes] ";

    private Log() {}

    public static void info(String msg) {
        System.out.println(PREFIX + msg);
    }

    public static void warn(String msg) {
        System.out.println(PREFIX + "WARN: " + msg);
    }

    public static void error(String msg, Throwable t) {
        System.out.println(PREFIX + "ERROR: " + msg + ": " + t);
    }
}
