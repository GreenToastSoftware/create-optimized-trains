# Create Optimized Trains

Addon de otimização para comboios do **Create Mod** (Forge 1.20.1).

## Funcionalidades

### 1. LOD (Level of Detail) para Comboios
- **FULL** — Carruagens reais com física completa (jogador perto)
- **MEDIUM** — Física simplificada, renderização normal
- **LOW** — Sem física, sem colisões, contraption updates mínimos
- **GHOST** — Entidade fantasma, sem renderização, dados abstratos

### 2. Agrupamento / Abstração de Carruagens
- Transforma múltiplas carruagens em 1 entidade lógica (`TrainGroup`)
- Guarda apenas: comprimento, velocidade, posição, lista de carruagens como NBT
- Expande novamente quando o jogador se aproxima

### 3. Multi-threading Seguro
**Thread secundária (seguro):**
- Cálculo de rotas
- Previsão de colisões
- Otimização de horários
- Gestão de prioridades
- Decisão de LOD
- Simulação preditiva

**Main thread (obrigatório):**
- Movimento real das entidades
- Física do Create
- Renderização
- Interação com blocos

### 4. Tick Throttling (Atualização Parcial)
- FULL: cada tick
- MEDIUM: cada 3 ticks (configurável)
- LOW: cada 8 ticks
- GHOST: cada 20 ticks

### 5. Otimização de Renderização
- Desativa animações para comboios distantes
- Saltar renderização completa para LOD GHOST
- Reduz partículas e efeitos visuais
- Oculta detalhes internos quando não visíveis

### 6. Proxy Entities
- Entidade leve que representa o comboio inteiro
- Substitui dezenas de contraptions
- Mantém posição, velocidade e direção
- Recria carruagens reais quando necessário

### 7. Gestão Inteligente de Chunk Loading
- Carrega apenas chunks essenciais
- Descarrega chunks atrás do comboio mais cedo
- Pré-carrega chunks à frente
- Anti-thrashing (evita carregar/descarregar repetidamente)

### 8. Otimização de Física
- Física simplificada para carruagens distantes
- Desativa colisões entre carruagens em LOD baixo
- Reduz checks de bogies
- Desativa física interna de contraptions longe

### 9. Otimização de Networking
- Envia updates apenas quando velocidade/direção muda
- Agrupa pacotes (delta compression)
- Reduz frequência de sync para comboios distantes

### 10. Sistema de Prioridades
- Prioridade por tipo (EXPRESS > PASSENGER > FREIGHT > LOW)
- Resolução de conflitos em cruzamentos
- Reserva de segmentos de via
- Análise assíncrona de conflitos

### 11. Monitor de Performance Dinâmico
- **NORMAL** (TPS >= 18): fidelidade completa
- **DEGRADED** (15 <= TPS < 18): otimizações leves
- **CRITICAL** (TPS < 15): otimizações agressivas, distâncias LOD reduzidas

## Configuração

Ficheiro: `create_optimized_trains-common.toml`

Todas as funcionalidades são configuráveis individualmente com distâncias, intervalos e toggles.

## Dependências

- Minecraft 1.20.1
- Forge 47.1.33+
- Create Mod 6.0.8+
- Flywheel 1.0.6+ (incluído pelo Create)

## Build

```bash
./gradlew build
```

O JAR resultante estará em `build/libs/`.

## Estrutura do Projeto

```
src/main/java/com/createoptimizedtrains/
├── CreateOptimizedTrains.java     # Classe principal do mod
├── config/ModConfig.java          # Configuração TOML
├── lod/                           # Sistema LOD
│   ├── LODLevel.java
│   └── LODSystem.java
├── grouping/                      # Agrupamento de carruagens
│   ├── TrainGroup.java
│   └── TrainGroupManager.java
├── threading/                     # Multi-threading seguro
│   └── AsyncTaskManager.java
├── throttling/                    # Tick throttling
│   └── TickThrottler.java
├── rendering/                     # Otimização de renderização
│   └── RenderOptimizer.java
├── proxy/                         # Proxy entities
│   ├── ProxyTrainEntity.java
│   └── ProxyEntityManager.java
├── chunks/                        # Gestão de chunk loading
│   └── ChunkLoadManager.java
├── physics/                       # Otimização de física
│   └── PhysicsOptimizer.java
├── networking/                    # Otimização de rede
│   ├── NetworkOptimizer.java
│   └── TrainSyncPacket.java
├── priority/                      # Sistema de prioridades
│   ├── TrainPriority.java
│   └── PriorityScheduler.java
├── monitor/                       # Monitor de performance
│   └── PerformanceMonitor.java
├── events/                        # Event handlers Forge
│   └── TrainEventHandler.java
└── mixin/                         # Mixins no Create mod
    ├── TrainMixin.java
    ├── CarriageMixin.java
    ├── CarriageEntityMixin.java
    └── client/
        └── CarriageRendererMixin.java
```

## Licença

MIT
