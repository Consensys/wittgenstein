package net.consensys.wittgenstein.core;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class P2PNode extends Node {
    public final List<P2PNode> peers = new ArrayList<>();

    public P2PNode(@NotNull NodeBuilder nb) {
        super(nb);
    }
}
