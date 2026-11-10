package javapower.core;

/** A group of instructions that can add itself to a {@link DecodeTable}. */
@FunctionalInterface
public interface InsnSet {
    void register(DecodeTable table);
}
