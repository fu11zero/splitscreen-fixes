package dev.splitscreenfixes;

import zombie.network.GameServer;

// Entry point called by ZombieBuddy when the mod jar is loaded (on the client and on the server).
// @Patch classes in this package are discovered and applied by ZombieBuddy itself.
public class Main {
    public static void main(String[] args) {
        Log.info("loading (" + (GameServer.server ? "server" : "client") + ")");
        if (!GameServer.server) {
            CoopServerAgent.install();
        }
    }
}
