package net.fabricmc.example.mixin;

import net.fabricmc.example.AdditionalForcedChunksSavedData;
import net.fabricmc.example.ForgeChunkManager;
import net.fabricmc.example.TicketTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ForcedChunksSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ForcedChunksSavedData.class)
public class ForcedChunksSavedDataMixin implements AdditionalForcedChunksSavedData {
    @Inject(at = @At("RETURN"), method = "load")
    private static void load(CompoundTag compoundTag, CallbackInfoReturnable<ForcedChunksSavedData> cir) {
        ForcedChunksSavedDataMixin savedData = (ForcedChunksSavedDataMixin) (Object) cir.getReturnValue();
        ForgeChunkManager.readForgeForcedChunks(compoundTag, savedData.getBlockForcedChunks(), savedData.getEntityForcedChunks());
    }

    @Inject(at = @At("HEAD"), method = "save")
    public void save(CompoundTag compoundTag, CallbackInfoReturnable<CompoundTag> cir) {
        ForgeChunkManager.writeForgeForcedChunks(compoundTag, this.blockForcedChunks, this.entityForcedChunks);
    }

    private final TicketTracker<BlockPos> blockForcedChunks = new TicketTracker<>();
    private final TicketTracker<java.util.UUID> entityForcedChunks = new TicketTracker<>();

    public TicketTracker<net.minecraft.core.BlockPos> getBlockForcedChunks() {
        return this.blockForcedChunks;
    }

    public TicketTracker<java.util.UUID> getEntityForcedChunks() {
        return this.entityForcedChunks;
    }
}
