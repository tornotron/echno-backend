package org.tornotron.echno_backend.common.compression;

import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@EqualsAndHashCode(callSuper = true)
public class HuffLeaf extends HuffNode{
    private final char character;

    public HuffLeaf(char character, int frequency) {
        super(frequency);
        this.character = character;
    }
}
