package com.createoptimizedtrains.rendering;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;

import java.lang.reflect.Field;

public final class BufferSourceResolver {

    private static volatile Field cachedMainBufferField;
    private static volatile boolean fieldLookupDone = false;

    private BufferSourceResolver() {}

    public static MultiBufferSource.BufferSource getRawMainBufferSource() {
        RenderBuffers renderBuffers = Minecraft.getInstance().renderBuffers();
        Field field = cachedMainBufferField;

        if (!fieldLookupDone) {
            field = resolveField(renderBuffers.getClass());
            cachedMainBufferField = field;
            fieldLookupDone = true;
        }

        if (field != null) {
            try {
                Object value = field.get(renderBuffers);
                if (value instanceof MultiBufferSource.BufferSource casted) {
                    return casted;
                }
            } catch (IllegalAccessException ignored) {
            }
        }

        return renderBuffers.bufferSource();
    }

    private static Field resolveField(Class<?> clazz) {
        String[] candidates = {"bufferSource", "f_110094_"};
        for (String name : candidates) {
            try {
                Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }
}