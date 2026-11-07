package dev.splitscreenfixes;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;

import se.krka.kahlua.integration.annotations.LuaMethod;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.znet.SteamUtils;
import zombie.core.Core;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.packets.character.CreatePlayerPacket;
import zombie.savefile.ClientPlayerDB;
import zombie.savefile.PlayerDBHelper;
import zombie.savefile.ServerPlayerDB;

// Keeps a saved splitscreen character in the players.db row of the local slot it plays in.
//
// Vanilla joins a saved character into the first free local slot (ISJoypadListBox), but
// ConnectCoopPacket sends only that slot and the server reads and writes the networkPlayers
// row whose playerIndex equals it (GameServer.receivePlayerConnect, disconnect, the periodic
// save). With rows 1 Roma, 2 Otis, choosing Otis as the second player makes the client play
// Otis while the server loads Roma: the skills shown are Roma's, inventory actions fail, and on
// exit the server's Roma goes back to Roma's row under the name typed for Otis, while Otis's
// progress is lost. "Add new player" is the same bug: CreatePlayerPacket writes the new
// character over the row of the slot, even when another saved character lives there.
//
// A slot can't simply follow the row: a hole in IsoPlayer.players breaks every loop over
// numPlayers. So the row follows the slot. Before the join the client asks the server to swap
// the two rows (lua/server/SplitscreenFixes/CharacterRows.lua) and waits for the answer; a
// server without this class answers no, or not at all, and nothing is damaged. The swap waits
// in PENDING for the moment the slot's row is about to be used, because until then a dead
// player of that slot may still be saved there, and an abandoned join must change nothing.
//
// All indices here are zero-based networkPlayers.playerIndex values, which equal local slots.
// Row 0 is the connection's main character and never moves.
public final class CharacterRows {
    private static final int NONE = -1;
    private static final int FIRST_ROW = 1;
    private static final int ROWS = 4;

    private static final String SWAP_SQL =
            "UPDATE networkPlayers SET playerIndex = CASE playerIndex WHEN ? THEN ? ELSE ? END "
            + "WHERE %s=? AND world=? AND playerIndex IN (?,?)";

    // Slot -> row to swap with, per connection. Weak so closed connections go away.
    private static final Map<IConnection, int[]> PENDING = new WeakHashMap<>();
    private static final Field CREATE_PLAYER_SLOT = createPlayerSlotField();

    private CharacterRows() {}

    private static Field createPlayerSlotField() {
        try {
            Field field = CreatePlayerPacket.class.getDeclaredField("playerIndex");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            Log.error("CreatePlayerPacket.playerIndex not found", e);
            return null;
        }
    }

    private static boolean isRow(int row) {
        return row >= FIRST_ROW && row < ROWS;
    }

    // --- Server ---

    // Called from OnClientCommand. False if the rows can't be swapped for this connection.
    @LuaMethod(name = "SplitscreenFixes_queueRowSwap", global = true)
    public static boolean queueSwap(IsoPlayer player, int row, int slot) {
        IConnection connection = GameServer.getConnectionFromPlayer(player);
        if (connection == null || !isRow(row) || !isRow(slot) || row == slot) {
            return false;
        }
        IsoPlayer slotPlayer = connection.getPlayerAt(slot);
        if (connection.getPlayerAt(row) != null || (slotPlayer != null && !slotPlayer.isDead())) {
            Log.warn("refused to swap character rows " + row + " and " + slot + ": a player is using one");
            return false;
        }
        synchronized (PENDING) {
            int[] rows = PENDING.computeIfAbsent(connection, c -> newPending());
            rows[slot] = row;
        }
        return true;
    }

    @LuaMethod(name = "SplitscreenFixes_cancelRowSwap", global = true)
    public static void cancelSwap(IsoPlayer player, int slot) {
        IConnection connection = GameServer.getConnectionFromPlayer(player);
        if (connection != null && isRow(slot)) {
            takePending(connection, slot);
        }
    }

    private static int[] newPending() {
        int[] rows = new int[ROWS];
        Arrays.fill(rows, NONE);
        return rows;
    }

    private static int takePending(IConnection connection, int slot) {
        synchronized (PENDING) {
            int[] rows = PENDING.get(connection);
            if (rows == null) {
                return NONE;
            }
            int row = rows[slot];
            rows[slot] = NONE;
            return row;
        }
    }

    // GameServer.receivePlayerConnect, before the slot's row is loaded. The reader stands on
    // the player index, the method's first read.
    public static void beforePlayerConnect(ByteBufferReader reader, IConnection connection) {
        ByteBuffer bb = reader.bb;
        if (bb.hasRemaining()) {
            applyPending(connection, bb.get(bb.position()));
        }
    }

    // CreatePlayerPacket.processServer, before the new character is written to the slot's row.
    public static void beforeCreatePlayer(CreatePlayerPacket packet, IConnection connection) {
        try {
            if (CREATE_PLAYER_SLOT != null) {
                applyPending(connection, CREATE_PLAYER_SLOT.getByte(packet));
            }
        } catch (ReflectiveOperationException e) {
            Log.error("CreatePlayerPacket.playerIndex unreadable", e);
        }
    }

    private static void applyPending(IConnection connection, int slot) {
        if (!isRow(slot)) {
            return;
        }
        int row = takePending(connection, slot);
        if (row == NONE) {
            return;
        }
        // A new player may have taken the row since the request.
        if (connection.getPlayerAt(row) != null) {
            Log.warn("character rows " + row + " and " + slot + " not swapped: a player is using one");
            return;
        }
        ServerPlayerDB db = ServerPlayerDB.getInstance();
        if (db == null || db.conn == null) {
            return;
        }
        // Writes still queued for these rows must land before their characters change places.
        db.process();
        boolean bySteamId = GameServer.coop && SteamUtils.isSteamModeEnabled();
        String sql = String.format(SWAP_SQL, bySteamId ? "steamid" : "username");
        try (PreparedStatement update = db.conn.prepareStatement(sql)) {
            update.setInt(1, row);
            update.setInt(2, slot);
            update.setInt(3, row);
            update.setString(4, bySteamId ? connection.getIDStr() : connection.getUserName());
            update.setString(5, Core.gameSaveWorld);
            update.setInt(6, row);
            update.setInt(7, slot);
            int changed = update.executeUpdate();
            db.conn.commit();
            Log.info("swapped character rows " + row + " and " + slot + " (" + changed + " changed)");
        } catch (SQLException e) {
            Log.error("swapping character rows failed", e);
            PlayerDBHelper.rollback(db.conn);
        }
    }

    // --- Client ---

    // The zero-based row of a character from IsoPlayer.getAllSavedPlayers.
    @LuaMethod(name = "SplitscreenFixes_savedRow", global = true)
    public static int savedRow(IsoPlayer saved) {
        return saved.serverPlayerIndex - 1;
    }

    // An empty row to move a saved character to before a new one takes its slot's row, or -1
    // if the slot's row holds nobody alive and needs no move.
    @LuaMethod(name = "SplitscreenFixes_freeRow", global = true)
    public static int freeRow(int slot) {
        ClientPlayerDB.NetworkCharacterProfile profile = profile();
        if (profile == null || !isRow(slot) || isEmptyRow(profile, slot)) {
            return NONE;
        }
        for (int row = FIRST_ROW; row < ROWS; row++) {
            if (row != slot && IsoPlayer.players[row] == null && isEmptyRow(profile, row)) {
                return row;
            }
        }
        return NONE;
    }

    private static boolean isEmptyRow(ClientPlayerDB.NetworkCharacterProfile profile, int row) {
        return profile.character[row] == null || profile.isDead[row];
    }

    // The client's copy of the rows, read at login and never refreshed, follows the server's.
    @LuaMethod(name = "SplitscreenFixes_swapProfileRows", global = true)
    public static void swapProfileRows(int a, int b) {
        ClientPlayerDB.NetworkCharacterProfile p = profile();
        if (p == null || !isRow(a) || !isRow(b)) {
            return;
        }
        byte[] character = p.character[a]; p.character[a] = p.character[b]; p.character[b] = character;
        String username = p.username[a]; p.username[a] = p.username[b]; p.username[b] = username;
        int worldVersion = p.worldVersion[a]; p.worldVersion[a] = p.worldVersion[b]; p.worldVersion[b] = worldVersion;
        float x = p.x[a]; p.x[a] = p.x[b]; p.x[b] = x;
        float y = p.y[a]; p.y[a] = p.y[b]; p.y[b] = y;
        float z = p.z[a]; p.z[a] = p.z[b]; p.z[b] = z;
        boolean isDead = p.isDead[a]; p.isDead[a] = p.isDead[b]; p.isDead[b] = isDead;
        p.playerCount = Math.max(p.playerCount, Math.max(a, b) + 1);
    }

    private static ClientPlayerDB.NetworkCharacterProfile profile() {
        ClientPlayerDB db = ClientPlayerDB.getInstance();
        return db == null ? null : db.networkProfile;
    }

    // IsoPlayer.isSaveFileInUse compares sqlId and saveFileName, which network characters don't
    // have, so a character already in play stays in the join list and could be added twice.
    // A row's character is in play when its slot is taken.
    public static boolean isRowInUse(IsoPlayer saved) {
        int row = saved.serverPlayerIndex - 1;
        return GameClient.client && isRow(row) && IsoPlayer.players[row] != null;
    }
}
