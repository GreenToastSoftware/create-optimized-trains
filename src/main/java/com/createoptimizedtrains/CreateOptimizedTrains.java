package com.createoptimizedtrains;

import com.createoptimizedtrains.chunks.ChunkLoadManager;
import com.createoptimizedtrains.config.ModConfig;
import com.createoptimizedtrains.events.TrainEventHandler;
import com.createoptimizedtrains.grouping.TrainGroupManager;
import com.createoptimizedtrains.lod.LODSystem;
import com.createoptimizedtrains.monitor.PerformanceMonitor;
import com.createoptimizedtrains.networking.NetworkOptimizer;
import com.createoptimizedtrains.physics.PhysicsOptimizer;
import com.createoptimizedtrains.priority.PriorityScheduler;
import com.createoptimizedtrains.proxy.ProxyEntityManager;
import com.createoptimizedtrains.rendering.RenderOptimizer;
import com.createoptimizedtrains.threading.AsyncTaskManager;
import com.createoptimizedtrains.throttling.TickThrottler;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(CreateOptimizedTrains.MOD_ID)
public class CreateOptimizedTrains {

    public static final String MOD_ID = "create_optimized_trains";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static CreateOptimizedTrains instance;

    private LODSystem lodSystem;
    private TrainGroupManager groupManager;
    private AsyncTaskManager asyncTaskManager;
    private TickThrottler tickThrottler;
    private ProxyEntityManager proxyEntityManager;
    private ChunkLoadManager chunkLoadManager;
    private PhysicsOptimizer physicsOptimizer;
    private NetworkOptimizer networkOptimizer;
    private PriorityScheduler priorityScheduler;
    private PerformanceMonitor performanceMonitor;

    public CreateOptimizedTrains() {
        instance = this;

        var modBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModLoadingContext.get().registerConfig(Type.COMMON, ModConfig.SPEC, MOD_ID + "-common.toml");

        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onClientSetup);
        modBus.addListener(this::onRegisterRenderers);

        // Registar entidades (Proxy Train)
        ProxyEntityManager.ENTITIES.register(modBus);

        MinecraftForge.EVENT_BUS.register(new TrainEventHandler());

        LOGGER.info("Create Optimized Trains a inicializar...");
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            performanceMonitor = new PerformanceMonitor();
            lodSystem = new LODSystem(performanceMonitor);
            asyncTaskManager = new AsyncTaskManager();
            tickThrottler = new TickThrottler(lodSystem);
            groupManager = new TrainGroupManager(lodSystem);
            proxyEntityManager = new ProxyEntityManager(lodSystem);
            chunkLoadManager = new ChunkLoadManager();
            physicsOptimizer = new PhysicsOptimizer(lodSystem);
            networkOptimizer = new NetworkOptimizer(lodSystem);
            priorityScheduler = new PriorityScheduler(asyncTaskManager);

            LOGGER.info("Create Optimized Trains: todos os sistemas inicializados.");
        });
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            RenderOptimizer.init(lodSystem);
            LOGGER.info("Create Optimized Trains: renderização otimizada ativa.");
        });
    }

    private void onRegisterRenderers(final EntityRenderersEvent.RegisterRenderers event) {
        // Proxy train é invisível — usar NoopRenderer para evitar NPE no EntityRenderDispatcher
        event.registerEntityRenderer(ProxyEntityManager.PROXY_TRAIN.get(), NoopRenderer::new);
    }

    public static CreateOptimizedTrains getInstance() {
        return instance;
    }

    public LODSystem getLODSystem() {
        return lodSystem;
    }

    public TrainGroupManager getGroupManager() {
        return groupManager;
    }

    public AsyncTaskManager getAsyncTaskManager() {
        return asyncTaskManager;
    }

    public TickThrottler getTickThrottler() {
        return tickThrottler;
    }

    public ProxyEntityManager getProxyEntityManager() {
        return proxyEntityManager;
    }

    public ChunkLoadManager getChunkLoadManager() {
        return chunkLoadManager;
    }

    public PhysicsOptimizer getPhysicsOptimizer() {
        return physicsOptimizer;
    }

    public NetworkOptimizer getNetworkOptimizer() {
        return networkOptimizer;
    }

    public PriorityScheduler getPriorityScheduler() {
        return priorityScheduler;
    }

    public PerformanceMonitor getPerformanceMonitor() {
        return performanceMonitor;
    }

    public void shutdown() {
        if (asyncTaskManager != null) {
            asyncTaskManager.shutdown();
        }
        LOGGER.info("Create Optimized Trains: encerrado.");
    }
}
