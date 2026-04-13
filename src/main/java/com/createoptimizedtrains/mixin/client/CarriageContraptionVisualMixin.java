package com.createoptimizedtrains.mixin.client;

import com.createoptimizedtrains.rendering.RenderOptimizer;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionVisual;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Mixin no CarriageContraptionVisual (pipeline Flywheel) para otimizar
 * rendering GPU-instanced de comboios distantes.
 *
 * No Create 6.x, o Flywheel está incorporado e CarriageContraptionVisual
 * é o caminho PRINCIPAL de renderização (não o EntityRenderer vanilla).
 *
 * Interceta:
 * - beginFrame(): atualização visual por frame (matrizes, light sections, child visuals)
 * - animate(): animação de bogeys via Flywheel instancing
 */
@Mixin(value = CarriageContraptionVisual.class, remap = false)
public abstract class CarriageContraptionVisualMixin {

    /**
     * Campo 'entity' está em AbstractEntityVisual (Flywheel JiJ'd dentro do Create).
     * O Mixin não consegue resolver @Shadow através de classes JiJ'd, então usamos reflexão.
     */
    @Unique
    private static Field cachedEntityField;

    @Unique
    private Entity getEntity() {
        try {
            if (cachedEntityField == null) {
                // Subir a hierarquia até encontrar o campo 'entity'
                Class<?> clazz = this.getClass();
                while (clazz != null) {
                    try {
                        cachedEntityField = clazz.getDeclaredField("entity");
                        cachedEntityField.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException e) {
                        clazz = clazz.getSuperclass();
                    }
                }
            }
            return cachedEntityField != null ? (Entity) cachedEntityField.get(this) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Intercertar beginFrame() para saltar atualização visual de comboios fantasma.
     * Chamado a cada frame pelo Flywheel para cada entidade visível — saltar isto
     * evita recalcular matrizes de transformação, light sections, e child visuals.
     */
    @Inject(method = "beginFrame", at = @At("HEAD"), cancellable = true)
    private void onBeginFrame(CallbackInfo ci) {
        UUID trainId = getTrainId();
        if (trainId != null) {
            // Registar visibilidade
            RenderOptimizer.shouldDeferForWarmup(trainId);

            // Skip Flywheel updates para LOD distantes
            if (RenderOptimizer.shouldSkipFlywheelUpdate(trainId)) {
                ci.cancel();
            }
        }
    }

    /**
     * Intercertar animate() para saltar animações de bogeys em comboios distantes.
     * animate() calcula posições de bogeys e atualiza BogeyVisuals no GPU.
     * Para comboios em LOD médio+ podemos saltar esta lógica por completo.
     */
    @Inject(method = "animate", at = @At("HEAD"), cancellable = true)
    private void onAnimate(float partialTick, CallbackInfo ci) {
        UUID trainId = getTrainId();
        if (trainId != null && !RenderOptimizer.shouldAnimate(trainId)) {
            ci.cancel();
        }
    }

    private UUID getTrainId() {
        Entity ent = getEntity();
        if (ent instanceof CarriageContraptionEntity cce) {
            Carriage carriage = cce.getCarriage();
            if (carriage != null && carriage.train != null) {
                return carriage.train.id;
            }
        }
        return null;
    }
}
