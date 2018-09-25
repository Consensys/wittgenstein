package net.consensys.wittgenstein.core;

import org.jetbrains.annotations.NotNull;

import java.util.*;

public class P2PNode extends Node {
    public final List<P2PNode> peers = new ArrayList<>();


    public P2PNode(@NotNull NodeBuilder nb) {
        super(nb);
    }


    protected @NotNull P2PNode getRandomPeer(@NotNull Random rd) {
        int size = peers.size();
        int item = rd.nextInt(size);
        int i = 0;
        for (P2PNode n : peers) {
            if (i++ == item) return n;
        }
        throw new IllegalStateException();
    }
}
