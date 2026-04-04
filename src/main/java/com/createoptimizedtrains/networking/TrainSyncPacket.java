package com.createoptimizedtrains.networking;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Pacote agrupado de sincronização para comboios otimizados.
 * Envia apenas dados delta (o que mudou) em vez do estado completo.
 */
public class TrainSyncPacket {

    private final UUID trainId;
    private final double speed;
    private final double posX;
    private final double posY;
    private final double posZ;
    private final byte flags; // Bit flags: 0=speed, 1=pos, 2=direction, 3=stopped

    public TrainSyncPacket(UUID trainId, double speed, double posX, double posY, double posZ, byte flags) {
        this.trainId = trainId;
        this.speed = speed;
        this.posX = posX;
        this.posY = posY;
        this.posZ = posZ;
        this.flags = flags;
    }

    public static void encode(TrainSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.trainId);
        buf.writeByte(packet.flags);

        if ((packet.flags & 0x01) != 0) {
            buf.writeDouble(packet.speed);
        }
        if ((packet.flags & 0x02) != 0) {
            buf.writeDouble(packet.posX);
            buf.writeDouble(packet.posY);
            buf.writeDouble(packet.posZ);
        }
    }

    public static TrainSyncPacket decode(FriendlyByteBuf buf) {
        UUID trainId = buf.readUUID();
        byte flags = buf.readByte();

        double speed = 0;
        double posX = 0, posY = 0, posZ = 0;

        if ((flags & 0x01) != 0) {
            speed = buf.readDouble();
        }
        if ((flags & 0x02) != 0) {
            posX = buf.readDouble();
            posY = buf.readDouble();
            posZ = buf.readDouble();
        }

        return new TrainSyncPacket(trainId, speed, posX, posY, posZ, flags);
    }

    public static void handle(TrainSyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // Aplicar estado recebido ao proxy/comboio local
            // Isto é processado no cliente
        });
        ctx.get().setPacketHandled(true);
    }

    // Getters

    public UUID getTrainId() {
        return trainId;
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

    public byte getFlags() {
        return flags;
    }

    public boolean hasSpeedUpdate() {
        return (flags & 0x01) != 0;
    }

    public boolean hasPositionUpdate() {
        return (flags & 0x02) != 0;
    }
}
