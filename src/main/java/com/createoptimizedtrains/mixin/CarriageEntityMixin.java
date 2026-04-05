package com.createoptimizedtrains.mixin;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/**
 * Mixin na CarriageContraptionEntity para corrigir a ordem de carregamento
 * de entidades vs comboios ao carregar o mundo.
 *
 * Problema no Create 6.0.8:
 * Quando o mundo carrega, CarriageContraptionEntity é desserializada do chunk
 * ANTES do Train estar registado no GlobalRailwayManager. O campo 'carriage'
 * é transiente (não salvo em NBT), portanto é null.
 *
 * No tickContraption() original do Create:
 *   - Cliente: chama bindCarriage() (tenta ligar ao comboio)
 *   - Servidor: chama discard() IMEDIATAMENTE
 *
 * Isto força a destruição e recriação da entidade, causando:
 *   1. Condutores/passageiros visíveis sem comboio por 1+ ticks
 *   2. Overhead de serializar, destruir e recriar a entidade
 *   3. carriageWaitingForChunks fica ativo → speed = 0 → solavancos
 *
 * Correção: no servidor, em vez de descartar imediatamente, tenta ligar-se
 * ao comboio (como o cliente faz) com um período de graça de 2 segundos.
 * Se o comboio carregar nesse tempo, a entidade sobrevive (sem recreação).
 * Se não carregar, descarta normalmente.
 */
@Mixin(value = CarriageContraptionEntity.class)
public abstract class CarriageEntityMixin {

    /**
     * Ticks de graça restantes antes de descartar no servidor.
     * -1 = não ativo (carriage já está ligada ou nunca foi necessário).
     */
    @Unique
    private int serverBindGraceTicks = -1;

    @Unique
    private static final int MAX_GRACE_TICKS = 40; // 2 segundos

    @Unique
    private static Method cachedBindCarriage;

    /**
     * Intercepta tickContraption() para dar período de graça ao servidor.
     *
     * No código original do Create, tickContraption faz:
     *   if (carriage == null) {
     *       if (isClientSide) bindCarriage();
     *       else discard();
     *       return;
     *   }
     *
     * Com este mixin, no servidor:
     *   if (carriage == null) {
     *       bindCarriage();  // tenta ligar (como o cliente)
     *       if (carriage != null) continue;  // sucesso!
     *       if (graceTicks < 40) { graceTicks++; return; }  // espera
     *       else discard();  // timeout — comboio não existe
     *   }
     */
    @Inject(method = "tickContraption", at = @At("HEAD"), remap = false, cancellable = true)
    private void graceBeforeDiscard(CallbackInfo ci) {
        CarriageContraptionEntity self = (CarriageContraptionEntity) (Object) this;

        // Se já tem carriage, resetar graça e deixar código original correr
        if (self.getCarriage() != null) {
            serverBindGraceTicks = -1;
            return;
        }

        Level level = self.level();

        // Cliente já faz bindCarriage() — não interferir
        if (level.isClientSide) return;

        // Servidor: carriage é null — ativar período de graça
        if (serverBindGraceTicks == -1) {
            serverBindGraceTicks = 0;
        }

        // Tentar ligar ao comboio via reflexão (bindCarriage é private)
        tryBindCarriage(self);

        // Se conseguiu ligar, perfeito — resetar e reconciliar com o DCE
        if (self.getCarriage() != null) {
            serverBindGraceTicks = -1;
            reconcileWithDimensionalEntity(self);
            return;
        }

        // Ainda sem carriage — verificar se ainda estamos no período de graça
        serverBindGraceTicks++;
        if (serverBindGraceTicks <= MAX_GRACE_TICKS) {
            // Cancelar tickContraption para evitar o discard() imediato
            ci.cancel();
            return;
        }

        // Período de graça expirou — deixar código original descartar
        // (return sem cancel = método original corre = discard)
    }

    @Unique
    private void tryBindCarriage(CarriageContraptionEntity self) {
        try {
            if (cachedBindCarriage == null) {
                cachedBindCarriage = CarriageContraptionEntity.class
                        .getDeclaredMethod("bindCarriage");
                cachedBindCarriage.setAccessible(true);
            }
            cachedBindCarriage.invoke(self);
        } catch (Exception e) {
            // Silencioso — se falhar, o período de graça continua
        }
    }

    /**
     * Após bindCarriage() ter sucesso, reconciliar esta entidade com o
     * DimensionalCarriageEntity (DCE) do Create.
     *
     * Problema: Quando o mundo carrega, a entidade é desserializada do chunk
     * pelo Minecraft, mas o DCE do Create tem uma WeakReference vazia (transiente).
     * Durante o período de graça, manageEntities() pode ver entity.get() == null
     * no DCE e chamar createEntity() — criando uma SEGUNDA entidade para a mesma
     * carruagem. Isto causa o bug visual de "2 carruagens sobrepostas".
     *
     * Solução: Depois do bind ter sucesso:
     * - Se o DCE já tem outra entidade viva (manageEntities criou uma substituta),
     *   descartar ESTA entidade (a do chunk) — a substituta é a correcta.
     * - Se o DCE não tem entidade, registar esta entidade como a activa.
     */
    @Unique
    private void reconcileWithDimensionalEntity(CarriageContraptionEntity self) {
        try {
            Carriage carriage = self.getCarriage();
            if (carriage == null) return;

            // Obter o DimensionalCarriageEntity para esta dimensão
            Carriage.DimensionalCarriageEntity dce = carriage.getDimensional(self.level());
            if (dce == null) return;

            CarriageContraptionEntity existing = dce.entity.get();

            if (existing != null && existing != self && existing.isAlive()) {
                // manageEntities() já criou uma entidade substituta durante o
                // período de graça — descartar esta entidade (a do chunk antigo)
                // para evitar a duplicação visual.
                self.discard();
                return;
            }

            // Nenhuma outra entidade existe no DCE — registar esta entidade
            // para que manageEntities() não crie uma duplicada no próximo tick.
            dce.entity = new WeakReference<>(self);
        } catch (Exception e) {
            // Se falhar (API mudou), descartar por segurança para evitar duplicados
            self.discard();
        }
    }
}
