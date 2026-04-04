package com.createoptimizedtrains.proxy;

import com.createoptimizedtrains.CreateOptimizedTrains;
import com.createoptimizedtrains.config.ModConfig;
import com.createoptimizedtrains.lod.LODLevel;
import com.createoptimizedtrains.lod.LODSystem;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ProxyEntityManager {

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, CreateOptimizedTrains.MOD_ID);

    public static final RegistryObject<EntityType<ProxyTrainEntity>> PROXY_TRAIN =
            ENTITIES.register("proxy_train", () ->
                    EntityType.Builder.of(ProxyTrainEntity::new, MobCategory.MISC)
                            .sized(1.0f, 1.0f)
                            .noSummon()
                            .fireImmune()
                            .clientTrackingRange(10)
                            .updateInterval(20)
                            .build("proxy_train")
            );

    private final LODSystem lodSystem;
    private final Map<UUID, ProxyTrainEntity> activeProxies = new ConcurrentHashMap<>();

    public ProxyEntityManager(LODSystem lodSystem) {
        this.lodSystem = lodSystem;
    }

    /**
     * Criar ou atualizar proxy para um comboio se necessário.
     */
    public void updateProxy(Train train, ServerLevel level) {
        if (!ModConfig.PROXY_ENABLED.get()) {
            return;
        }

        LODLevel lod = lodSystem.getTrainLOD(train.id);

        if (lod.isAtLeast(LODLevel.LOW)) {
            // Precisa de proxy
            createOrUpdateProxy(train, level);
        } else {
            // Não precisa mais de proxy
            removeProxy(train.id);
        }
    }

    private void createOrUpdateProxy(Train train, ServerLevel level) {
        ProxyTrainEntity proxy = activeProxies.get(train.id);

        double posX = 0, posY = 0, posZ = 0;
        double dirX = 0, dirZ = 0;

        // Create 6.x: usar anyAvailableEntity() para posição
        if (!train.carriages.isEmpty()) {
            var firstCarriage = train.carriages.get(0);
            var entity = firstCarriage.anyAvailableEntity();
            if (entity != null) {
                posX = entity.getX();
                posY = entity.getY();
                posZ = entity.getZ();
            }
        }

        if (proxy == null) {
            // Criar novo proxy
            proxy = PROXY_TRAIN.get().create(level);
            if (proxy == null) return;

            proxy.setPos(posX, posY, posZ);
            proxy.setTrainData(train.id, train.speed, dirX, dirZ, train.carriages.size());
            level.addFreshEntity(proxy);
            activeProxies.put(train.id, proxy);

            CreateOptimizedTrains.LOGGER.debug("Proxy criado para comboio {}", train.id);
        } else {
            // Atualizar proxy existente
            proxy.setPos(posX, posY, posZ);
            proxy.updateSpeed(train.speed);
            proxy.updateDirection(dirX, dirZ);
        }
    }

    public void removeProxy(UUID trainId) {
        ProxyTrainEntity proxy = activeProxies.remove(trainId);
        if (proxy != null && proxy.isAlive()) {
            proxy.discard();
            CreateOptimizedTrains.LOGGER.debug("Proxy removido para comboio {}", trainId);
        }
    }

    public void removeAllProxies() {
        for (var entry : activeProxies.entrySet()) {
            if (entry.getValue().isAlive()) {
                entry.getValue().discard();
            }
        }
        activeProxies.clear();
    }

    public boolean hasProxy(UUID trainId) {
        return activeProxies.containsKey(trainId);
    }

    public ProxyTrainEntity getProxy(UUID trainId) {
        return activeProxies.get(trainId);
    }
}
