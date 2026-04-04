package com.createoptimizedtrains.priority;

public enum TrainPriority {
    /**
     * Comboios de emergência / express - máxima prioridade
     */
    EXPRESS(0),

    /**
     * Comboios de passageiros - alta prioridade
     */
    PASSENGER(1),

    /**
     * Comboios de carga normal
     */
    FREIGHT(2),

    /**
     * Comboios de baixa prioridade (manutenção, vazio)
     */
    LOW(3);

    private final int level;

    TrainPriority(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }

    /**
     * Retorna true se esta prioridade é mais alta (menor número = maior prioridade).
     */
    public boolean isHigherThan(TrainPriority other) {
        return this.level < other.level;
    }

    /**
     * Retorna uma prioridade baseada no número de passageiros/carga.
     */
    public static TrainPriority fromContext(boolean hasPassengers, boolean isEmpty) {
        if (hasPassengers) {
            return PASSENGER;
        }
        if (isEmpty) {
            return LOW;
        }
        return FREIGHT;
    }
}
