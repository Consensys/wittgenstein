package net.consensys.wittgenstein.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("WeakerAccess")
public abstract class BlockChainNode<TB extends Block> extends Node {
  protected final TB genesis;

  /**
   * We keep some data that should be useful for all implementations
   */
  protected final Map<Long, TB> blocksReceivedByBlockId = new HashMap<>();
  protected final Map<Long, Set<TB>> blocksReceivedByFatherId = new HashMap<>();
  protected final Map<Integer, TB> blocksReceivedByHeight = new HashMap<>();

  public TB head;

  public BlockChainNode(NodeBuilder nb, boolean byzantine, TB genesis) {
    super(nb, byzantine);
    this.genesis = genesis;
    this.head = genesis;
    this.blocksReceivedByBlockId.put(genesis.id, genesis);
  }

  /**
   * @return true if it's a new block, false if the block in invalid or if we have already received
   *         it.
   */
  public boolean onBlock(TB b) {
    if (!b.valid)
      return false;

    if (this.blocksReceivedByBlockId.put(b.id, b) != null) {
      return false; // If we have already received this block
    }
    Set<TB> pa = this.blocksReceivedByFatherId.computeIfAbsent(b.parent.id, k -> new HashSet<>());
    pa.add(b);
    blocksReceivedByHeight.put(b.height, b);

    head = best(head, b);

    return true;
  }

  /**
   * This describes how to choose the head between two blocks.
   */
  public abstract TB best(TB cur, TB alt);
}
