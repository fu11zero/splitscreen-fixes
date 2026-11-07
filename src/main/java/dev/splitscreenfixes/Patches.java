package dev.splitscreenfixes;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.CombatManager;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.textures.TextureDraw;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameClient;
import zombie.network.IConnection;
import zombie.network.PacketTypes.PacketType;
import zombie.network.PacketsCache;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.character.CreatePlayerPacket;
import zombie.ui.Clock;
import zombie.ui.MoodlesUI;
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

    // Server: move a saved character's players.db row to its slot before the slot's row is
    // loaded or overwritten (see CharacterRows).
    @Patch(className = "zombie.network.GameServer", methodName = "receivePlayerConnect")
    public static class SavedCharacterConnect {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) ByteBufferReader reader,
                                 @Patch.Argument(1) IConnection connection) {
            CharacterRows.beforePlayerConnect(reader, connection);
        }
    }

    @Patch(className = "zombie.network.packets.character.CreatePlayerPacket", methodName = "processServer")
    public static class NewCharacterConnect {
        @Patch.OnEnter
        public static void enter(@Patch.This CreatePlayerPacket packet,
                                 @Patch.Argument(1) UdpConnection connection) {
            CharacterRows.beforeCreatePlayer(packet, connection);
        }
    }

    // Client: don't offer a saved character that already plays (see CharacterRows).
    @Patch(className = "zombie.characters.IsoPlayer", methodName = "isSaveFileInUse")
    public static class SavedCharacterInUse {
        @Patch.OnExit
        public static void exit(@Patch.This IsoPlayer saved,
                                @Patch.Return(readOnly = false) boolean inUse) {
            if (!inUse) {
                inUse = CharacterRows.isRowInUse(saved);
            }
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

    // Client: shrink moodle icons so player 0's column doesn't run onto player 2's screen (see MoodleSize).
    @Patch(className = "zombie.ui.MoodlesUI", methodName = "getTextureSizeForOption")
    public static class MoodleIconSize {
        @Patch.OnExit
        public static void exit(@Patch.This MoodlesUI ui,
                                @Patch.Return(readOnly = false) int size) {
            size = MoodleSize.fit(ui, size);
        }
    }

    // Client: one clock per splitscreen player (see PlayerClocks).
    @Patch(className = "zombie.ui.Clock", methodName = "resize")
    public static class ClockResize {
        @Patch.OnExit
        public static void exit(@Patch.This Clock clock) {
            PlayerClocks.afterClockResize(clock);
        }
    }

    @Patch(className = "zombie.ui.UIManager", methodName = "resize")
    public static class ClockLayout {
        @Patch.OnExit
        public static void exit() {
            PlayerClocks.afterUiResize();
        }
    }

    @Patch(className = "zombie.ui.Clock", methodName = "onMouseDown")
    public static class ClockClick {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Clock clock) {
            return PlayerClocks.onMouseDown(clock);
        }

        // A skipped method returns null; the caller unboxes the Boolean.
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) Boolean handled) {
            if (handled == null) {
                handled = Boolean.TRUE;
            }
        }
    }

    // Client: search mode circle for every splitscreen player (see SearchModeOverlay).
    @Patch(className = "zombie.iso.weather.WeatherShader", methodName = "startMainThread")
    public static class SearchModeOffset {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) TextureDraw texd, @Patch.Argument(1) int playerIndex) {
            SearchModeOverlay.afterStartMainThread(texd, playerIndex);
        }
    }

    // Client: view cone for every splitscreen player (see ViewCone).
    @Patch(className = "zombie.core.opengl.ShaderUnit", methodName = "loadShaderFile")
    public static class ViewConeShader {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) String fileName,
                                @Patch.Return(readOnly = false) String code) {
            code = ViewCone.afterLoadShaderFile(fileName, code);
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
