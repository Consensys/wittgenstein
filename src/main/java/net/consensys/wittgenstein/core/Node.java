package net.consensys.wittgenstein.core;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicInteger;

public class Node {
    public final int nodeId;
    public final boolean byzantine;

    protected long msgReceived = 0;
    protected long msgSent = 0;


    public Node(@NotNull AtomicInteger ids, boolean byzantine) {
        this.nodeId = ids.getAndIncrement();
        this.byzantine = byzantine;
    }

    public Node(@NotNull AtomicInteger ids) {
        this(ids, false);
    }
}
