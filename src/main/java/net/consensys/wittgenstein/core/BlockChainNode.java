package net.consensys.wittgenstein.core;

import java.util.*;

@SuppressWarnings("WeakerAccess")
public abstract class BlockChainNode<TB extends Block> extends Node {
  protected final TB genesis;

  /** We keep some data that should be useful for all implementations */
  protected final Map<Long, TB> blocksReceivedByBlockId = new HashMap<>();

  public final Map<Long, Set<TB>> blocksReceivedByFatherId = new HashMap<>();
  public final Map<Integer, Set<TB>> blocksReceivedByHeight = new HashMap<>();

  public TB head;

  public BlockChainNode(Random rd, NodeBuilder nb, boolean byzantine, TB genesis) {
    super(rd, nb, byzantine);
    this.genesis = genesis;
    this.head = genesis;
    this.blocksReceivedByBlockId.put(genesis.id, genesis);
  }

  /**
   * @return true if it's a new block, false if the block in invalid or if we have already received
   *     it.
   */
  public boolean onBlock(TB b) {
    if (!b.valid) {
      return false;
    }

    if (this.blocksReceivedByBlockId.put(b.id, b) != null) {
      return false; // If we have already received this block
    }
    Set<TB> pa = this.blocksReceivedByFatherId.computeIfAbsent(b.parent.id, k -> new HashSet<>());
    pa.add(b);
    Set<TB> ub = this.blocksReceivedByHeight.computeIfAbsent(b.height, k -> new HashSet<>());
    ub.add(b);
    head = best(head, b);

    return true;
  }

  /** This describes how to choose the head between two blocks. */
  public abstract TB best(TB cur, TB alt);

  /**
   * Counts the transactions included in a block created by this node in the chain starting at this
   * head.
   */
  public int txsCreatedInChain(Block<?> head) {
    int txs = 0;
    Block<?> cur = head;
    while (cur != null) {
      if (cur.producer == this) {
        txs += cur.txCount();
      }
      cur = cur.parent;
    }
    return txs;
  }

  /** Counts the number of blocks created by this node in the chain starting at this head. */
  public int blocksCreatedInChain(Block<?> head) {
    int blocks = 0;
    Block<?> cur = head;
    while (cur != null) {
      if (cur.producer == this) {
        blocks++;
      }
      cur = cur.parent;
    }
    return blocks;
  }
}
