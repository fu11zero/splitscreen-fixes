package dev.splitscreenfixes;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;
import java.util.WeakHashMap;

import zombie.core.Transaction;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;
import zombie.network.PacketTypes.PacketType;
import zombie.network.PacketsCache;
import zombie.network.packets.INetworkPacket;

// Server side: one packet instance per splitscreen player instead of one per connection.
//
// Vanilla PacketType.onServerPacket parses every incoming packet into the instance the
// connection caches for that packet type (PacketsCache.getPacket). Three handlers keep that
// instance after processServer returns:
//   ItemTransactionPacket  -> TransactionManager.add(this)  (item transfers, ~all inventory actions)
//   BuildActionPacket      -> ActionManager.start(this)
//   FishingActionPacket    -> ActionManager.start(this)     (later packets update it in place)
// With one player per connection this works because a player has one such request in flight.
// Splitscreen players share the connection, so player 2's request is parsed over player 1's
// pending transaction: player 1's transaction vanishes (its progress bar sits at 100% until
// the client times out), and player 2's is often rejected because the server compares it with
// itself in TransactionManager.isConsistent.
//
// Fix: for these types every local player index gets its own instance, which is exactly what
// a player on a separate connection gets. Player 0 keeps the vanilla instance. A packet whose
// player can't be read (ItemTransaction Reject carries no player) gets a throwaway instance,
// so it never overwrites anyone's pending request.
// Public: ZombieBuddy inlines the advice calling it into game classes.
public final class PacketSlots {
    // Layout shared by Action.parse and ItemTransactionPacket.parse:
    // byte id, byte state, short onlineId, byte playerIndex.
    private static final int STATE_OFFSET = 1;
    private static final int ONLINE_ID_OFFSET = 2;
    private static final int PLAYER_INDEX_OFFSET = 4;
    private static final int HEADER_SIZE = 5;
    private static final int NO_SLOT = -1;

    private record Pending(UdpConnection connection, PacketType type, int slot) {}

    private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();
    // Keyed by connection (UdpConnection extends PacketsCache); weak so closed connections go away.
    private static final Map<PacketsCache, Map<PacketType, INetworkPacket[]>> SLOTS = new WeakHashMap<>();
    private static volatile boolean announced;

    private PacketSlots() {}

    public static boolean isPerPlayer(PacketType type) {
        return type == PacketType.ItemTransaction
                || type == PacketType.BuildAction
                || type == PacketType.FishingAction;
    }

    // PacketType.onServerPacket, before the packet instance is fetched.
    public static void beforeServerPacket(PacketType type, ByteBufferReader reader, UdpConnection connection) {
        if (!GameServer.server || connection == null || !isPerPlayer(type)) {
            PENDING.remove();
            return;
        }
        PENDING.set(new Pending(connection, type, peekSlot(type, reader, connection)));
    }

    public static void afterServerPacket() {
        PENDING.remove();
    }

    // PacketsCache.getPacket: swap the vanilla instance for the player's own one.
    public static INetworkPacket packetFor(PacketsCache cache, PacketType type, INetworkPacket vanilla) {
        Pending pending = PENDING.get();
        if (pending == null || pending.connection() != cache || pending.type() != type || vanilla == null) {
            return vanilla;
        }
        PENDING.remove();
        int slot = pending.slot();
        if (slot == 0) {
            return vanilla;
        }
        try {
            if (slot == NO_SLOT) {
                return newInstance(vanilla);
            }
            INetworkPacket packet;
            synchronized (SLOTS) {
                INetworkPacket[] slots = SLOTS
                        .computeIfAbsent(cache, c -> new EnumMap<>(PacketType.class))
                        .computeIfAbsent(type, t -> new INetworkPacket[4]);
                if (slots[slot] == null) {
                    slots[slot] = newInstance(vanilla);
                }
                packet = slots[slot];
            }
            if (!announced) {
                announced = true;
                Log.info("separate " + type + " packet for splitscreen player " + slot
                        + " (further ones are not logged)");
            }
            return packet;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.error("can't create " + type + " packet, using the shared one", e);
            return vanilla;
        }
    }

    private static INetworkPacket newInstance(INetworkPacket vanilla) throws ReflectiveOperationException {
        return vanilla.getClass().getDeclaredConstructor().newInstance();
    }

    // Reads the player index without moving the buffer. The online id is checked against the
    // connection so a layout change in a game update falls back to a throwaway instance.
    private static int peekSlot(PacketType type, ByteBufferReader reader, UdpConnection connection) {
        ByteBuffer bb = reader.bb;
        int pos = bb.position();
        if (bb.limit() - pos < HEADER_SIZE) {
            return NO_SLOT;
        }
        int state = bb.get(pos + STATE_OFFSET) & 0xFF;
        if (type == PacketType.ItemTransaction && state == Transaction.TransactionState.Reject.ordinal()) {
            return NO_SLOT;
        }
        short onlineId = bb.getShort(pos + ONLINE_ID_OFFSET);
        int index = bb.get(pos + PLAYER_INDEX_OFFSET);
        if (index < 0 || index >= connection.playerIds.length || connection.playerIds[index] != onlineId) {
            return NO_SLOT;
        }
        return index;
    }
}
