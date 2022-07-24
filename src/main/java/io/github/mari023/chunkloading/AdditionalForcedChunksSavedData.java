package io.github.mari023.chunkloading;

public interface AdditionalForcedChunksSavedData {
    TicketTracker<net.minecraft.core.BlockPos> getBlockForcedChunks();
    TicketTracker<java.util.UUID> getEntityForcedChunks();
}
