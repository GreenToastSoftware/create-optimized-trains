package com.createoptimizedtrains.proxy;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;

import java.util.UUID;

/**
 * Entidade leve que representa um comboio inteiro.
 * Substitui dezenas de contraptions por uma única entidade com:
 * - posição
 * - velocidade
 * - direção
 * - UUID do comboio original
 *
 * Recria carruagens reais quando necessário (jogador aproxima-se).
 */
public class ProxyTrainEntity extends Entity {

    private UUID originalTrainId;
    private double trainSpeed;
    private double directionX;
    private double directionZ;
    private int carriageCount;

    public ProxyTrainEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    public void setTrainData(UUID trainId, double speed, double dirX, double dirZ, int carriages) {
        this.originalTrainId = trainId;
        this.trainSpeed = speed;
        this.directionX = dirX;
        this.directionZ = dirZ;
        this.carriageCount = carriages;
    }

    @Override
    public void tick() {
        super.tick();
        // Mover proxy ao longo da direção com velocidade simplificada
        if (trainSpeed != 0) {
            double moveX = directionX * trainSpeed * 0.05;
            double moveZ = directionZ * trainSpeed * 0.05;
            this.setPos(getX() + moveX, getY(), getZ() + moveZ);
        }
    }

    @Override
    protected void defineSynchedData() {
        // Sem dados sincronizados extra
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.hasUUID("OriginalTrainId")) {
            this.originalTrainId = tag.getUUID("OriginalTrainId");
        }
        this.trainSpeed = tag.getDouble("TrainSpeed");
        this.directionX = tag.getDouble("DirX");
        this.directionZ = tag.getDouble("DirZ");
        this.carriageCount = tag.getInt("CarriageCount");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (originalTrainId != null) {
            tag.putUUID("OriginalTrainId", originalTrainId);
        }
        tag.putDouble("TrainSpeed", trainSpeed);
        tag.putDouble("DirX", directionX);
        tag.putDouble("DirZ", directionZ);
        tag.putInt("CarriageCount", carriageCount);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    // Getters

    public UUID getOriginalTrainId() {
        return originalTrainId;
    }

    public double getTrainSpeed() {
        return trainSpeed;
    }

    public int getCarriageCount() {
        return carriageCount;
    }

    public void updateSpeed(double speed) {
        this.trainSpeed = speed;
    }

    public void updateDirection(double dirX, double dirZ) {
        this.directionX = dirX;
        this.directionZ = dirZ;
    }
}
