package dev.splitscreenfixes;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.CombatManager;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameClient;
import zombie.network.PacketTypes.PacketType;
import zombie.network.PacketsCache;
import zombie.network.packets.INetworkPacket;
import zombie.vehicles.BaseVehicle;

public final class Patches {
    private Patches() {}

    // Server: remember which splitscreen player sent the packet (see PacketSlots).
    @Patch(className = "zombie.network.PacketTypes$PacketType", methodName = "onServerPacket")
    public static class ServerPacket {
        @Patch.OnEnter
        public static void enter(@Patch.This PacketType type,
                                 @Patch.Argument(0) ByteBufferReader reader,
                                 @Patch.Argument(1) UdpConnection connection) {
            PacketSlots.beforeServerPacket(type, reader, connection);
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() {
            PacketSlots.afterServerPacket();
        }
    }

    // Server: hand that player its own packet instance instead of the connection's shared one.
    @Patch(className = "zombie.network.PacketsCache", methodName = "getPacket")
    public static class CachedPacket {
        @Patch.OnExit
        public static void exit(@Patch.This PacketsCache cache,
                                @Patch.Argument(0) PacketType type,
                                @Patch.Return(readOnly = false) INetworkPacket packet) {
            packet = PacketSlots.packetFor(cache, type, packet);
        }
    }

    // Client: vanilla BaseVehicle.hasAuthorization compares the vehicle's authorized player with
    // IsoPlayer.getInstance() only. In splitscreen that is whichever local player was updated
    // last, so for the other drivers BaseVehicle.update never calls updatePhysicsNetwork: the
    // car is simulated locally but its position never reaches the server. Other players see it
    // parked, the server streams no chunks around it (the road is blocked at the chunk edge),
    // and on exit the car snaps back. Any local player holding the authorization counts.
    @Patch(className = "zombie.vehicles.BaseVehicle", methodName = "hasAuthorization")
    public static class VehicleAuthorization {
        @Patch.OnExit
        public static void exit(@Patch.This BaseVehicle vehicle,
                                @Patch.Argument(0) UdpConnection connection,
                                @Patch.Return(readOnly = false) boolean authorized) {
            if (!authorized && GameClient.client && connection != null) {
                authorized = isLocalPlayer(vehicle.getNetPlayerId());
            }
        }
    }

    // Client: vanilla CombatManager.update aims only for IsoPlayer.players[0] (see CombatReticle).
    @Patch(className = "zombie.CombatManager", methodName = "update")
    public static class AimingReticle {
        @Patch.OnExit
        public static void exit(@Patch.This CombatManager manager) {
            CombatReticle.afterUpdate(manager);
        }
    }

    public static boolean isLocalPlayer(short onlineId) {
        if (onlineId == -1) {
            return false;
        }
        for (IsoPlayer player : IsoPlayer.players) {
            if (player != null && player.isLocalPlayer() && player.getOnlineID() == onlineId) {
                return true;
            }
        }
        return false;
    }
}
