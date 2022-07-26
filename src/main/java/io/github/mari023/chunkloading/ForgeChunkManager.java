/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package io.github.mari023.chunkloading;

import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ForcedChunksSavedData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

@ParametersAreNonnullByDefault
public class ForgeChunkManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final TicketType<TicketOwner<BlockPos>> BLOCK = TicketType.create("forge:block", Comparator.comparing(info -> info));
    private static final TicketType<TicketOwner<BlockPos>> BLOCK_TICKING = TicketType.create("forge:block_ticking", Comparator.comparing(info -> info));
    private static final TicketType<TicketOwner<UUID>> ENTITY = TicketType.create("forge:entity", Comparator.comparing(info -> info));
    private static final TicketType<TicketOwner<UUID>> ENTITY_TICKING = TicketType.create("forge:entity_ticking", Comparator.comparing(info -> info));
    private static final Map<String, LoadingValidationCallback> callbacks = new HashMap<>();

    /**
     * Sets the forced chunk loading validation callback for the given mod. This allows for validating and removing no longer valid tickets on level load.
     */
    public static void setForcedChunkLoadingCallback(String modId, LoadingValidationCallback callback) {
        if (FabricLoader.getInstance().isModLoaded(modId))
            callbacks.put(modId, callback);
        else
            LOGGER.warn("A mod attempted to set the forced chunk validation loading callback for an unloaded mod of id: {}", modId);
    }

    /**
     * Checks if a level has any forced chunks. Mainly used for seeing if a level should continue ticking with no players in it.
     */
    public static boolean hasForcedChunks(ServerLevel level) {
        ForcedChunksSavedData data = level.getDataStorage().get(ForcedChunksSavedData::load, "chunks");
        if (data == null) return false;
        return !data.getChunks().isEmpty() || !getBlockForcedChunks(data).isEmpty() || !getEntityForcedChunks(data).isEmpty();
    }

    /**
     * Forces a chunk to be loaded for the given mod with the "owner" of the ticket being a given block position.
     *
     * @param add     {@code true} to force the chunk, {@code false} to unforce the chunk.
     * @param ticking {@code true} to make the chunk receive full chunk ticks even if there is no player nearby.
     */
    public static boolean forceChunk(ServerLevel level, String modId, BlockPos owner, int chunkX, int chunkZ, boolean add, boolean ticking) {
        return forceChunk(level, modId, owner, chunkX, chunkZ, add, ticking, ticking ? BLOCK_TICKING : BLOCK, ForgeChunkManager::getBlockForcedChunks);
    }

    /**
     * Forces a chunk to be loaded for the given mod with the "owner" of the ticket being the UUID of the given entity.
     *
     * @param add     {@code true} to force the chunk, {@code false} to unforce the chunk.
     * @param ticking {@code true} to make the chunk receive full chunk ticks even if there is no player nearby.
     */
    public static boolean forceChunk(ServerLevel level, String modId, Entity owner, int chunkX, int chunkZ, boolean add, boolean ticking) {
        return forceChunk(level, modId, owner.getUUID(), chunkX, chunkZ, add, ticking);
    }

    /**
     * Forces a chunk to be loaded for the given mod with the "owner" of the ticket being a given UUID.
     *
     * @param add     {@code true} to force the chunk, {@code false} to unforce the chunk.
     * @param ticking {@code true} to make the chunk receive full chunk ticks even if there is no player nearby.
     */
    public static boolean forceChunk(ServerLevel level, String modId, UUID owner, int chunkX, int chunkZ, boolean add, boolean ticking) {
        return forceChunk(level, modId, owner, chunkX, chunkZ, add, ticking, ticking ? ENTITY_TICKING : ENTITY, ForgeChunkManager::getEntityForcedChunks);
    }

    /**
     * Forces a chunk to be loaded for the given mod with the given "owner".
     *
     * @param add {@code true} to force the chunk, {@code false} to unforce the chunk.
     * @implNote Based on {@link ServerLevel#setChunkForced(int, int, boolean)}
     */
    private static <T extends Comparable<? super T>> boolean forceChunk(ServerLevel level, String modId, T owner, int chunkX, int chunkZ, boolean add, boolean ticking,
                                                                        TicketType<TicketOwner<T>> type, Function<ForcedChunksSavedData, TicketTracker<T>> ticketGetter) {
        if (!FabricLoader.getInstance().isModLoaded(modId)) {
            LOGGER.warn("A mod attempted to force a chunk for an unloaded mod of id: {}", modId);
            return false;
        }
        ForcedChunksSavedData saveData = level.getDataStorage().computeIfAbsent(ForcedChunksSavedData::load, ForcedChunksSavedData::new, "chunks");
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        long chunk = pos.toLong();
        TicketTracker<T> tickets = ticketGetter.apply(saveData);
        TicketOwner<T> ticketOwner = new TicketOwner<>(modId, owner);
        boolean success;
        if (add) {
            success = tickets.add(ticketOwner, chunk, ticking);
            if (success)
                level.getChunk(chunkX, chunkZ);
        } else {
            success = tickets.remove(ticketOwner, chunk, ticking);
        }
        if (success) {
            saveData.setDirty(true);
            forceChunk(level, pos, type, ticketOwner, add, ticking);
        }
        return success;
    }

    /**
     * Adds/Removes a ticket from the level's chunk provider with the proper levels to match the forced chunks.
     *
     * @param add     {@code true} to force the chunk, {@code false} to unforce the chunk.
     * @param ticking {@code true} to make the chunk receive full chunk ticks even if there is no player nearby.
     * @implNote We use distance 2 for what we pass, as when using register/releaseTicket the ticket's level is set to 33 - distance and the level that forced chunks use
     * is 31.
     */
    private static <T extends Comparable<? super T>> void forceChunk(ServerLevel level, ChunkPos pos, TicketType<TicketOwner<T>> type, TicketOwner<T> owner, boolean add,
                                                                     boolean ticking) {
        if (add)
            ((AdditionalDistanceManager) level.getChunkSource().distanceManager).addRegionTicket(type, pos, 2, owner, ticking);
        else
            ((AdditionalDistanceManager) level.getChunkSource().distanceManager).removeRegionTicket(type, pos, 2, owner, ticking);
    }

    /**
     * Reinstates forge's forced chunks when vanilla initially loads a level and reinstates their forced chunks. This method also will validate all of forge's forced
     * chunks using and registered {@link LoadingValidationCallback}.
     *
     * @apiNote Internal
     */
    public static void reinstatePersistentChunks(ServerLevel level, ForcedChunksSavedData saveData) {
        if (!callbacks.isEmpty()) {
            //If we have any callbacks, gather all owned tickets by modid for both blocks and entities
            Map<String, Map<BlockPos, Pair<LongSet, LongSet>>> blockTickets = gatherTicketsByModId(getBlockForcedChunks(saveData));
            Map<String, Map<UUID, Pair<LongSet, LongSet>>> entityTickets = gatherTicketsByModId(getEntityForcedChunks(saveData));
            //Fire the callbacks allowing them to remove any tickets they don't want anymore
            for (Map.Entry<String, LoadingValidationCallback> entry : callbacks.entrySet()) {
                String modId = entry.getKey();
                boolean hasBlockTicket = blockTickets.containsKey(modId);
                boolean hasEntityTicket = entityTickets.containsKey(modId);
                if (hasBlockTicket || hasEntityTicket) {
                    Map<BlockPos, Pair<LongSet, LongSet>> ownedBlockTickets = hasBlockTicket ? Collections.unmodifiableMap(blockTickets.get(modId)) : Collections.emptyMap();
                    Map<UUID, Pair<LongSet, LongSet>> ownedEntityTickets = hasEntityTicket ? Collections.unmodifiableMap(entityTickets.get(modId)) : Collections.emptyMap();
                    entry.getValue().validateTickets(level, new TicketHelper(saveData, modId, ownedBlockTickets, ownedEntityTickets));
                }
            }
        }
        //Reinstate the chunks that we want to load
        reinstatePersistentChunks(level, BLOCK, getBlockForcedChunks(saveData).chunks, false);
        reinstatePersistentChunks(level, BLOCK_TICKING, getBlockForcedChunks(saveData).tickingChunks, true);
        reinstatePersistentChunks(level, ENTITY, getEntityForcedChunks(saveData).chunks, false);
        reinstatePersistentChunks(level, ENTITY_TICKING, getEntityForcedChunks(saveData).tickingChunks, true);
    }

    /**
     * Gathers tickets into a mod filtered map for use in providing all tickets a mod has registered to its {@link LoadingValidationCallback}.
     */
    private static <T extends Comparable<? super T>> Map<String, Map<T, Pair<LongSet, LongSet>>> gatherTicketsByModId(TicketTracker<T> tickets) {
        Map<String, Map<T, Pair<LongSet, LongSet>>> modSortedOwnedChunks = new HashMap<>();
        gatherTicketsByModId(tickets.chunks, Pair::getFirst, modSortedOwnedChunks);
        gatherTicketsByModId(tickets.tickingChunks, Pair::getSecond, modSortedOwnedChunks);
        return modSortedOwnedChunks;
    }

    /**
     * Gathers tickets into a mod filtered map for use in providing all tickets a mod has registered to its {@link LoadingValidationCallback}.
     */
    private static <T extends Comparable<? super T>> void gatherTicketsByModId(Map<TicketOwner<T>, LongSet> tickets, Function<Pair<LongSet, LongSet>, LongSet> typeGetter,
                                                                               Map<String, Map<T, Pair<LongSet, LongSet>>> modSortedOwnedChunks) {
        for (Map.Entry<TicketOwner<T>, LongSet> entry : tickets.entrySet()) {
            Pair<LongSet, LongSet> pair = modSortedOwnedChunks.computeIfAbsent(entry.getKey().modId(), modId -> new HashMap<>())
                    .computeIfAbsent(entry.getKey().owner(), owner -> new Pair<>(new LongOpenHashSet(), new LongOpenHashSet()));
            typeGetter.apply(pair).addAll(entry.getValue());
        }
    }

    /**
     * Adds back any persistent forced chunks to the level's chunk provider.
     */
    private static <T extends Comparable<? super T>> void reinstatePersistentChunks(ServerLevel level, TicketType<TicketOwner<T>> type,
                                                                                    Map<TicketOwner<T>, LongSet> tickets, boolean ticking) {
        for (Map.Entry<TicketOwner<T>, LongSet> entry : tickets.entrySet()) {
            for (long chunk : entry.getValue()) {
                forceChunk(level, new ChunkPos(chunk), type, entry.getKey(), true, ticking);
            }
        }
    }

    /**
     * Writes the forge forced chunks into the NBT compound. Format is List{modid, List{ChunkPos, List{BlockPos}, List{UUID}}}
     *
     * @apiNote Internal
     */
    public static void writeForgeForcedChunks(CompoundTag nbt, TicketTracker<BlockPos> blockForcedChunks, TicketTracker<UUID> entityForcedChunks) {
        if (!blockForcedChunks.isEmpty() || !entityForcedChunks.isEmpty()) {
            Map<String, Long2ObjectMap<CompoundTag>> forcedEntries = new HashMap<>();
            writeForcedChunkOwners(forcedEntries, blockForcedChunks, "Blocks", Tag.TAG_COMPOUND, (pos, forcedBlocks) -> forcedBlocks.add(NbtUtils.writeBlockPos(pos)));
            writeForcedChunkOwners(forcedEntries, entityForcedChunks, "Entities", Tag.TAG_INT_ARRAY, (uuid, forcedEntities) -> forcedEntities.add(NbtUtils.createUUID(uuid)));
            ListTag forcedChunks = new ListTag();
            for (Map.Entry<String, Long2ObjectMap<CompoundTag>> entry : forcedEntries.entrySet()) {
                CompoundTag forcedEntry = new CompoundTag();
                forcedEntry.putString("Mod", entry.getKey());
                ListTag modForced = new ListTag();
                modForced.addAll(entry.getValue().values());
                forcedEntry.put("ModForced", modForced);
                forcedChunks.add(forcedEntry);
            }
            nbt.put("ForgeForced", forcedChunks);
        }
    }

    private static <T extends Comparable<? super T>> void writeForcedChunkOwners(Map<String, Long2ObjectMap<CompoundTag>> forcedEntries, TicketTracker<T> tracker,
                                                                                 String listKey, int listType, BiConsumer<T, ListTag> ownerWriter) {
        writeForcedChunkOwners(forcedEntries, tracker.chunks, listKey, listType, ownerWriter);
        writeForcedChunkOwners(forcedEntries, tracker.tickingChunks, "Ticking" + listKey, listType, ownerWriter);
    }

    private static <T extends Comparable<? super T>> void writeForcedChunkOwners(Map<String, Long2ObjectMap<CompoundTag>> forcedEntries,
                                                                                 Map<TicketOwner<T>, LongSet> forcedChunks, String listKey, int listType, BiConsumer<T, ListTag> ownerWriter) {
        for (Map.Entry<TicketOwner<T>, LongSet> entry : forcedChunks.entrySet()) {
            Long2ObjectMap<CompoundTag> modForced = forcedEntries.computeIfAbsent(entry.getKey().modId(), modId -> new Long2ObjectOpenHashMap<>());
            for (long chunk : entry.getValue()) {
                CompoundTag modEntry = modForced.computeIfAbsent(chunk, chunkPos -> {
                    CompoundTag baseEntry = new CompoundTag();
                    baseEntry.putLong("Chunk", chunkPos);
                    return baseEntry;
                });
                ListTag ownerList = modEntry.getList(listKey, listType);
                ownerWriter.accept(entry.getKey().owner(), ownerList);
                //Note: As getList returns a new list in the case the data is of the wrong type,
                // we need to mimic was vanilla does in various places and put our list back in
                // the CompoundNBT regardless.
                modEntry.put(listKey, ownerList);
            }
        }
    }

    /**
     * Reads the forge forced chunks from the NBT compound. Format is List{modid, List{ChunkPos, List{BlockPos}, List{UUID}}}
     *
     * @apiNote Internal
     */
    public static void readForgeForcedChunks(CompoundTag nbt, TicketTracker<BlockPos> blockForcedChunks, TicketTracker<UUID> entityForcedChunks) {
        ListTag forcedChunks = nbt.getList("ForgeForced", Tag.TAG_COMPOUND);
        for (int i = 0; i < forcedChunks.size(); i++) {
            CompoundTag forcedEntry = forcedChunks.getCompound(i);
            String modId = forcedEntry.getString("Mod");
            if (FabricLoader.getInstance().isModLoaded(modId)) {
                ListTag modForced = forcedEntry.getList("ModForced", Tag.TAG_COMPOUND);
                for (int j = 0; j < modForced.size(); j++) {
                    CompoundTag modEntry = modForced.getCompound(j);
                    long chunkPos = modEntry.getLong("Chunk");
                    readBlockForcedChunks(modId, chunkPos, modEntry, "Blocks", blockForcedChunks.chunks);
                    readBlockForcedChunks(modId, chunkPos, modEntry, "TickingBlocks", blockForcedChunks.tickingChunks);
                    readEntityForcedChunks(modId, chunkPos, modEntry, "Entities", entityForcedChunks.chunks);
                    readEntityForcedChunks(modId, chunkPos, modEntry, "TickingEntities", entityForcedChunks.tickingChunks);
                }
            } else {
                LOGGER.warn("Found chunk loading data for mod {} which is currently not available or active - it will be removed from the level save.", modId);
            }
        }
    }

    /**
     * Reads the forge block forced chunks.
     */
    private static void readBlockForcedChunks(String modId, long chunkPos, CompoundTag modEntry, String key, Map<TicketOwner<BlockPos>, LongSet> blockForcedChunks) {
        ListTag forcedBlocks = modEntry.getList(key, Tag.TAG_COMPOUND);
        for (int k = 0; k < forcedBlocks.size(); k++) {
            blockForcedChunks.computeIfAbsent(new TicketOwner<>(modId, NbtUtils.readBlockPos(forcedBlocks.getCompound(k))), owner -> new LongOpenHashSet()).add(chunkPos);
        }
    }

    /**
     * Reads the forge entity forced chunks.
     */
    private static void readEntityForcedChunks(String modId, long chunkPos, CompoundTag modEntry, String key, Map<TicketOwner<UUID>, LongSet> entityForcedChunks) {
        ListTag forcedEntities = modEntry.getList(key, Tag.TAG_INT_ARRAY);
        for (Tag uuid : forcedEntities) {
            entityForcedChunks.computeIfAbsent(new TicketOwner<>(modId, NbtUtils.loadUUID(uuid)), owner -> new LongOpenHashSet()).add(chunkPos);
        }
    }

    @FunctionalInterface
    public interface LoadingValidationCallback {
        /**
         * Called back when tickets are about to be loaded and reinstated to allow mods to invalidate and remove specific tickets that may no longer be valid.
         *
         * @param level        The level
         * @param ticketHelper Ticket helper to remove any invalid tickets.
         */
        void validateTickets(ServerLevel level, TicketHelper ticketHelper);
    }

    public static TicketTracker<BlockPos> getBlockForcedChunks(ForcedChunksSavedData savedData) {
        return ((AdditionalForcedChunksSavedData) savedData).getBlockForcedChunks();
    }

    public static TicketTracker<UUID> getEntityForcedChunks(ForcedChunksSavedData savedData) {
        return ((AdditionalForcedChunksSavedData) savedData).getEntityForcedChunks();
    }
}