package net.consensys.wittgenstein.core;

import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class P2PNode extends Node {
    public final Set<P2PNode> peers = new HashSet<>();
    final int x = 0;
    final int y = 0;

    public P2PNode(@NotNull AtomicInteger ids) {
        super(ids);
    }

    int dist(P2PNode n) {
        return (int) Math.sqrt(Math.pow(x - n.x, 2) + Math.pow(y - n.y, 2));
    }


    protected P2PNode getRandomPeer(@NotNull Random rd) {
        int size = peers.size();
        int item = rd.nextInt(size);
        int i = 0;
        for (P2PNode n : peers) {
            if (i++ == item) return n;
        }
        throw new IllegalStateException();
    }
}
