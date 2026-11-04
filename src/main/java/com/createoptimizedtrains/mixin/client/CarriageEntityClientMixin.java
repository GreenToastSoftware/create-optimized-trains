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
 * Mixin CLIENT no CarriageContraptionEntity: corrigir saltos de reposicionamento
 * sem estragar a interpolação do movimento normal.
 *
 * O aparecimento de uma carruagem já é tratado pelo Create: não renderiza enquanto
 * os pontos do comboio não forem sincronizados (pointsInitialised / validForRender) e,
 * na primeira atualização, alignEntity iguala a posição anterior à atual.
 *
 * A versão anterior deste mixin forçava xo=x durante os primeiros 5 ticks de TODAS as
 * carruagens novas. Isso desligava a interpolação entre ticks: a carruagem andava aos
 * saltos a 20 Hz (a 40 bl/s, 2 blocos por salto) sempre que aparecia — o engasgo
 * visível quando um comboio entra no campo de visão.
 *
 * Agora só se elimina a interpolação quando há um salto anormal num único tick
 * (ressincronização com o servidor, correção de posição), em qualquer altura.
 */
@OnlyIn(Dist.CLIENT)
@Mixin(CarriageContraptionEntity.class)
public abstract class CarriageEntityClientMixin {

    // Salto mínimo (blocos) para ser considerado correção e não movimento
    @Unique
    private static final double COT$MIN_SNAP_DISTANCE = 8.0;
    // Um tick com deslocamento acima de N× o tick anterior também é correção
    @Unique
    private static final double COT$SNAP_FACTOR = 3.0;

    @Unique
    private double cot$lastStepSq = -1;

    @Inject(method = "tick", at = @At("TAIL"))
    private void cot$snapOnlyLargeCorrections(CallbackInfo ci) {
        CarriageContraptionEntity self = (CarriageContraptionEntity) (Object) this;
        if (!self.level().isClientSide) return;

        // Ainda não visível: o Create trata do primeiro posicionamento
        if (!self.validForRender) {
            cot$lastStepSq = -1;
            return;
        }

        double dx = self.getX() - self.xo;
        double dy = self.getY() - self.yo;
        double dz = self.getZ() - self.zo;
        double stepSq = dx * dx + dy * dy + dz * dz;

        double minSq = COT$MIN_SNAP_DISTANCE * COT$MIN_SNAP_DISTANCE;
        boolean anomalous = stepSq > minSq
            && (cot$lastStepSq < 0 || stepSq > cot$lastStepSq * COT$SNAP_FACTOR * COT$SNAP_FACTOR);

        if (anomalous) {
            // Correção de posição: mostrar já na posição certa em vez de deslizar
            self.xo = self.getX();
            self.yo = self.getY();
            self.zo = self.getZ();
            // Não usar o salto como referência de velocidade
            return;
        }
        cot$lastStepSq = stepSq;
    }
}
