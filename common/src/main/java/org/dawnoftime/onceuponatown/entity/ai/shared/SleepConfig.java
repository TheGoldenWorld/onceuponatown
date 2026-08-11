package org.dawnoftime.onceuponatown.entity.ai.shared;

import java.util.List;

public interface SleepConfig {
    int getBedtime();
    int getWakeupTime();
    List<String> getRestBuildings();
    double getWalkSpeed();
}
