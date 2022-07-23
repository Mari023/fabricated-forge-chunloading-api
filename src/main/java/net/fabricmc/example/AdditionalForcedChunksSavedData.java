package net.fabricmc.example;

public interface AdditionalForcedChunksSavedData {
    TicketTracker<net.minecraft.core.BlockPos> getBlockForcedChunks();
    TicketTracker<java.util.UUID> getEntityForcedChunks();
}
