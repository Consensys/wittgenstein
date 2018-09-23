package net.consensys.wittgenstein.core;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("WeakerAccess")
public abstract class BlockChainNode<TB extends Block> extends Node {
    protected final @NotNull TB genesis;

    protected final Map<Long, TB> blocksReceivedByBlockId = new HashMap<>();
    protected final Map<Long, Set<TB>> blocksReceivedByFatherId = new HashMap<>();
    protected final Map<Integer, TB> blocksReceivedByHeight = new HashMap<>();

    public @NotNull TB head;

    public BlockChainNode(@NotNull NodeBuilder nb, boolean byzantine, @NotNull TB genesis) {
        super(nb, byzantine);
        this.genesis = genesis;
        this.head = genesis;
        this.blocksReceivedByBlockId.put(genesis.id, genesis);
    }

    /**
     * @return true if it's a new block, false if the block in invalid or if we have already received it.
     */
    public boolean onBlock(@NotNull TB b) {
        if (!b.valid) return false;

        if (this.blocksReceivedByBlockId.put(b.id, b) != null) {
            return false; // If we have already received this block
        }
        Set<TB> pa = this.blocksReceivedByFatherId.computeIfAbsent(b.parent.id, k -> new HashSet<>());
        pa.add(b);
        blocksReceivedByHeight.put(b.height, b);

        head = best(head, b);

        return true;
    }

    public abstract @NotNull TB best(@NotNull TB cur, @NotNull TB alt);
}
