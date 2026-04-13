package com.createoptimizedtrains.mixin.client;

import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin CLIENT no CarriageContraptionEntity para eliminar o stutter de posicionamento
 * quando carruagens aparecem pela primeira vez no ecrã do jogador.
 *
 * Problema:
 * Quando uma carruagem entra numa loaded chunk, a entidade é enviada ao cliente
 * com a posição do server. Nos primeiros ticks, o sistema do Create reposiciona
 * a entidade no trilho (alignEntity). Se a posição muda entre ticks, o renderer
 * interpola entre a posição antiga (xo/yo/zo) e a nova (x/y/z), criando um
 * "slide" visual ou "ghost" que parece teletransporte.
 *
 * Correção:
 * Nos primeiros 5 ticks após spawn, forçar xo=x, yo=y, zo=z no fim de cada tick.
 * Isto elimina a interpolação errada e a carruagem renderiza na posição exacta.
 * Após os 5 ticks, o comportamento normal é restaurado (movimento suave).
 */
@OnlyIn(Dist.CLIENT)
@Mixin(CarriageContraptionEntity.class)
public abstract class CarriageEntityClientMixin {

    @Unique
    private int cot$ticksSinceSpawn = 0;

    @Unique
    private static final int SNAP_TICKS = 5;

    /**
     * No fim de cada tick, se a entidade é nova (primeiros 5 ticks),
     * forçar posição antiga = posição atual para eliminar interpolação errada.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void cot$snapPositionOnNewEntity(CallbackInfo ci) {
        CarriageContraptionEntity self = (CarriageContraptionEntity) (Object) this;

        if (!self.level().isClientSide) return;
        if (cot$ticksSinceSpawn >= SNAP_TICKS) return;

        cot$ticksSinceSpawn++;

        // Forçar posição de interpolação = posição real
        // Isto previne o "slide" visual de posição errada para posição correcta
        self.xo = self.getX();
        self.yo = self.getY();
        self.zo = self.getZ();
        self.xRotO = self.getXRot();
        self.yRotO = self.getYRot();
    }
}
