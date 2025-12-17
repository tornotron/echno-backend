package org.tornotron.echno_backend.common.events;

import org.springframework.context.ApplicationEvent;
import org.tornotron.echno_backend.goodsReceivedNote.GoodsReceivedNote;

public class GrnCreatedEvent extends ApplicationEvent {

    private final GoodsReceivedNote goodsReceivedNote;

    public GrnCreatedEvent(Object source, GoodsReceivedNote goodsReceivedNote) {
        super(source);
        this.goodsReceivedNote = goodsReceivedNote;
    }

    public GoodsReceivedNote getGoodsReceivedNote() {
        return goodsReceivedNote;
    }
}
