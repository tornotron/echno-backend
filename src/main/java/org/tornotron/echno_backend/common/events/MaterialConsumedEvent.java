package org.tornotron.echno_backend.common.events;

import org.springframework.context.ApplicationEvent;
import org.tornotron.echno_backend.materialConsumption.MaterialConsumption;

public class MaterialConsumedEvent extends ApplicationEvent {

    private final MaterialConsumption materialConsumption;

    public MaterialConsumedEvent(Object source, MaterialConsumption materialConsumption) {
        super(source);
        this.materialConsumption = materialConsumption;
    }

    public MaterialConsumption getMaterialConsumption() {
        return materialConsumption;
    }
}
