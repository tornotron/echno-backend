package org.tornotron.echno_backend.common.compression;

import lombok.Getter;
import lombok.RequiredArgsConstructor;


@Getter
@RequiredArgsConstructor
public class HuffNode implements Comparable<HuffNode>{
    private final int frequency;
    private HuffNode leftNode;
    private HuffNode rightNode;

    public HuffNode(HuffNode leftNode, HuffNode rightNode) {
        this.frequency = leftNode.getFrequency() + rightNode.getFrequency();
        this.leftNode = leftNode;
        this.rightNode = rightNode;
    }

    @Override
    public int compareTo(HuffNode node) {
        return Integer.compare(frequency, node.getFrequency());
    }
}
