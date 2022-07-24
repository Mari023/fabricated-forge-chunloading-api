/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package io.github.mari023.chunkloading;

import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ForcedChunksSavedData;

import java.util.Map;
import java.util.UUID;

/**
 * Class to help mods remove no longer valid tickets.
 */
public class TicketHelper {
    private final Map<BlockPos, Pair<LongSet, LongSet>> blockTickets;
    private final Map<UUID, Pair<LongSet, LongSet>> entityTickets;
    private final ForcedChunksSavedData saveData;
    private final String modId;

    public TicketHelper(ForcedChunksSavedData saveData, String modId, Map<BlockPos, Pair<LongSet, LongSet>> blockTickets, Map<UUID, Pair<LongSet, LongSet>> entityTickets) {
        this.saveData = saveData;
        this.modId = modId;
        this.blockTickets = blockTickets;
        this.entityTickets = entityTickets;
    }

    /**
     * Gets all "BLOCK" tickets this mod had registered and which block positions are forcing which chunks. First element of the pair is the non-fully ticking
     * tickets, second element is the fully ticking tickets.
     *
     * @apiNote This map is unmodifiable and does not update to reflect removed tickets so it is safe to call the remove methods while iterating it.
     */
    public Map<BlockPos, Pair<LongSet, LongSet>> getBlockTickets() {
        return blockTickets;
    }

    /**
     * Gets all "ENTITY" tickets this mod had registered and which entity (UUID) is forcing which chunks. First element of the pair is the non-fully ticking
     * tickets, second element is the fully ticking tickets.
     *
     * @apiNote This map is unmodifiable and does not update to reflect removed tickets so it is safe to call the remove methods while iterating it.
     */
    public Map<UUID, Pair<LongSet, LongSet>> getEntityTickets() {
        return entityTickets;
    }

    /**
     * Removes all tickets that a given block was responsible for; both ticking and not ticking.
     *
     * @param owner Block that was responsible.
     */
    public void removeAllTickets(BlockPos owner) {
        removeAllTickets(ForgeChunkManager.getBlockForcedChunks(saveData), owner);
    }

    /**
     * Removes all tickets that a given entity (UUID) was responsible for; both ticking and not ticking.
     *
     * @param owner Entity (UUID) that was responsible.
     */
    public void removeAllTickets(UUID owner) {
        removeAllTickets(ForgeChunkManager.getEntityForcedChunks(saveData), owner);
    }

    /**
     * Removes all tickets that a given owner was responsible for; both ticking and not ticking.
     */
    private <T extends Comparable<? super T>> void removeAllTickets(TicketTracker<T> tickets, T owner) {
        TicketOwner<T> ticketOwner = new TicketOwner<>(modId, owner);
        if (tickets.chunks.containsKey(ticketOwner) || tickets.tickingChunks.containsKey(ticketOwner)) {
            tickets.chunks.remove(ticketOwner);
            tickets.tickingChunks.remove(ticketOwner);
            saveData.setDirty(true);
        }
    }

    /**
     * Removes the ticket for the given chunk that a given block was responsible for.
     *
     * @param owner   Block that was responsible.
     * @param chunk   Chunk to remove ticket of.
     * @param ticking Whether or not the ticket to remove represents a ticking set of tickets or not.
     */
    public void removeTicket(BlockPos owner, long chunk, boolean ticking) {
        removeTicket(ForgeChunkManager.getBlockForcedChunks(saveData), owner, chunk, ticking);
    }

    /**
     * Removes the ticket for the given chunk that a given entity (UUID) was responsible for.
     *
     * @param owner   Entity (UUID) that was responsible.
     * @param chunk   Chunk to remove ticket of.
     * @param ticking Whether or not the ticket to remove represents a ticking set of tickets or not.
     */
    public void removeTicket(UUID owner, long chunk, boolean ticking) {
        removeTicket(ForgeChunkManager.getEntityForcedChunks(saveData), owner, chunk, ticking);
    }

    private <T extends Comparable<? super T>> void removeTicket(TicketTracker<T> tickets, T owner, long chunk, boolean ticking) {
        if (tickets.remove(new TicketOwner<>(modId, owner), chunk, ticking))
            saveData.setDirty(true);
    }
}