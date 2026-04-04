package com.createoptimizedtrains.mixin;

import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Backport da otimização de colisão de contraptions do Create 6.0.9.
 *
 * No Create 6.0.8, quando portas abrem/fecham numa contraption (ex: comboio na estação),
 * invalidateColliders() chama gatherBBsOffThread() que:
 *   1. Combina TODOS os blocos num único VoxelShape com Shapes.joinUnoptimized() — O(n²)
 *   2. Chama .optimize() no shape gigante
 *   3. Chama .toAabbs() para extrair AABBs
 *
 * No Create 6.0.9, isto foi substituído por recolha direta de AABBs de cada bloco
 * individualmente — O(n) e sem alocações desnecessárias.
 *
 * Este mixin aplica a mesma otimização ao 6.0.8.
 */
@Mixin(value = Contraption.class, remap = false)
public abstract class ContraptionCollisionMixin {

    @Shadow
    public Optional<List<AABB>> simplifiedEntityColliders;

    @Shadow
    private CompletableFuture<Void> simplifiedEntityColliderProvider;

    @Shadow
    protected Map<BlockPos, StructureTemplate.StructureBlockInfo> blocks;

    /**
     * getContraptionWorld() retorna ContraptionWorld (extends WrappedLevel da lib catnip).
     * Não podemos usar @Shadow porque o tipo ContraptionWorld requer catnip no classpath
     * de compilação, e o descriptor não bateria com Object.
     * Usamos reflexão com cache — só é chamado quando portas abrem/fecham, não a cada tick.
     */
    @Unique
    private static Method cachedGetContraptionWorld;

    @Unique
    private BlockGetter invokeGetContraptionWorld() {
        try {
            if (cachedGetContraptionWorld == null) {
                cachedGetContraptionWorld = Contraption.class.getDeclaredMethod("getContraptionWorld");
                cachedGetContraptionWorld.setAccessible(true);
            }
            return (BlockGetter) cachedGetContraptionWorld.invoke(this);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Substitui gatherBBsOffThread() com algoritmo O(n) em vez do O(n²) original.
     * Mantém a execução ASSÍNCRONA para não bloquear o main thread quando
     * entidades são criadas (ex: carruagens mudam de chunk).
     * O algoritmo recolhe AABBs diretamente de cada bloco em vez de combinar
     * VoxelShapes com joinUnoptimized() + optimize() + toAabbs().
     * Baseado no commit "Separating boxes" de Jozufozu no Create 6.0.9.
     */
    @Inject(method = "gatherBBsOffThread", at = @At("HEAD"), cancellable = true)
    private void optimizedGatherBBs(CallbackInfo ci) {
        // Garantir que o ContraptionWorld existe e obter como BlockGetter
        BlockGetter level = invokeGetContraptionWorld();
        if (level == null) {
            // Fallback: não cancelar, deixar o método original correr
            return;
        }
        ci.cancel();

        // Cancelar qualquer provider anterior
        if (simplifiedEntityColliderProvider != null) {
            simplifiedEntityColliderProvider.cancel(false);
        }

        // Capturar referência ao mapa de blocos para usar na thread async
        final Map<BlockPos, StructureTemplate.StructureBlockInfo> blocksRef = this.blocks;
        final BlockGetter levelRef = level;

        // Executar assíncronamente como o original, mas com algoritmo O(n)
        simplifiedEntityColliderProvider = CompletableFuture
                .supplyAsync(() -> {
                    List<AABB> result = new ArrayList<>();
                    for (Map.Entry<BlockPos, StructureTemplate.StructureBlockInfo> entry : blocksRef.entrySet()) {
                        BlockPos pos = entry.getKey();
                        StructureTemplate.StructureBlockInfo info = entry.getValue();
                        BlockState state = info.state();

                        VoxelShape collisionShape = state.getCollisionShape(
                                levelRef, pos, CollisionContext.empty());

                        if (!collisionShape.isEmpty()) {
                            double ox = pos.getX();
                            double oy = pos.getY();
                            double oz = pos.getZ();
                            collisionShape.forAllBoxes((x1, y1, z1, x2, y2, z2) ->
                                    result.add(new AABB(x1 + ox, y1 + oy, z1 + oz,
                                                        x2 + ox, y2 + oy, z2 + oz)));
                        }
                    }
                    return result;
                })
                .thenAccept(r -> simplifiedEntityColliders = Optional.of(r));
    }
}
