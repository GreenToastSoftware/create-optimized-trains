package com.createoptimizedtrains.grouping;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TrainGroup {

    private final UUID trainId;
    private double totalLength;
    private double speed;
    private double posX, posY, posZ;
    private double directionX, directionZ;
    private int carriageCount;
    private final List<CompoundTag> carriageData;
    private boolean collapsed;
    private long collapsedAtTick;

    public TrainGroup(UUID trainId) {
        this.trainId = trainId;
        this.carriageData = new ArrayList<>();
        this.collapsed = false;
    }

    public void collapse(List<CompoundTag> carriageSnapshots, double totalLength,
                          double speed, double posX, double posY, double posZ,
                          double dirX, double dirZ) {
        this.carriageData.clear();
        this.carriageData.addAll(carriageSnapshots);
        this.carriageCount = carriageSnapshots.size();
        this.totalLength = totalLength;
        this.speed = speed;
        this.posX = posX;
        this.posY = posY;
        this.posZ = posZ;
        this.directionX = dirX;
        this.directionZ = dirZ;
        this.collapsed = true;
    }

    public void updatePosition(double speed, long ticksSinceCollapse) {
        // Simulação simplificada: mover ao longo da direção
        this.speed = speed;
        this.posX += directionX * speed * 0.05; // Aproximação
        this.posZ += directionZ * speed * 0.05;
    }

    public CompoundTag serialize() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("TrainId", trainId);
        tag.putDouble("TotalLength", totalLength);
        tag.putDouble("Speed", speed);
        tag.putDouble("PosX", posX);
        tag.putDouble("PosY", posY);
        tag.putDouble("PosZ", posZ);
        tag.putDouble("DirX", directionX);
        tag.putDouble("DirZ", directionZ);
        tag.putInt("CarriageCount", carriageCount);
        tag.putBoolean("Collapsed", collapsed);

        ListTag carriageList = new ListTag();
        for (CompoundTag carriageDatum : carriageData) {
            carriageList.add(carriageDatum);
        }
        tag.put("Carriages", carriageList);

        return tag;
    }

    public static TrainGroup deserialize(CompoundTag tag) {
        UUID id = tag.getUUID("TrainId");
        TrainGroup group = new TrainGroup(id);
        group.totalLength = tag.getDouble("TotalLength");
        group.speed = tag.getDouble("Speed");
        group.posX = tag.getDouble("PosX");
        group.posY = tag.getDouble("PosY");
        group.posZ = tag.getDouble("PosZ");
        group.directionX = tag.getDouble("DirX");
        group.directionZ = tag.getDouble("DirZ");
        group.carriageCount = tag.getInt("CarriageCount");
        group.collapsed = tag.getBoolean("Collapsed");

        ListTag carriageList = tag.getList("Carriages", Tag.TAG_COMPOUND);
        for (int i = 0; i < carriageList.size(); i++) {
            group.carriageData.add(carriageList.getCompound(i));
        }

        return group;
    }

    // Getters

    public UUID getTrainId() {
        return trainId;
    }

    public double getTotalLength() {
        return totalLength;
    }

    public double getSpeed() {
        return speed;
    }

    public double getPosX() {
        return posX;
    }

    public double getPosY() {
        return posY;
    }

    public double getPosZ() {
        return posZ;
    }

    public int getCarriageCount() {
        return carriageCount;
    }

    public List<CompoundTag> getCarriageData() {
        return carriageData;
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
    }
}
