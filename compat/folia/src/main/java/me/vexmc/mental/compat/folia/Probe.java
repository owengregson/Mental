package me.vexmc.mental.compat.folia;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;

final class Probe {
    private Probe() {}

    static Class<?>[] required() {
        return new Class<?>[] {
            AsyncScheduler.class, EntityScheduler.class,
            GlobalRegionScheduler.class, RegionScheduler.class
        };
    }
}
