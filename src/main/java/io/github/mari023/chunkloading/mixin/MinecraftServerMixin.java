package io.github.mari023.chunkloading.mixin;

import io.github.mari023.chunkloading.ForgeChunkManager;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {

    @Final
    @Shadow
    private Map<ResourceKey<Level>, ServerLevel> levels;

    @Inject(method = "prepareLevels", at = @At("RETURN"))
    private void prepareLevels(ChunkProgressListener chunkProgressListener, CallbackInfo ci) {
        for (ServerLevel serverLevel : this.levels.values()) {
            ForcedChunksSavedData forcedChunksSavedData = serverLevel.getDataStorage().get(ForcedChunksSavedData::load, "chunks");
            if (forcedChunksSavedData == null) continue;
            ForgeChunkManager.reinstatePersistentChunks(serverLevel, forcedChunksSavedData);
        }
    }
}
