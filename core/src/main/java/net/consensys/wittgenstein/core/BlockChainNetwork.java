package net.consensys.wittgenstein.core;

import java.util.*;
import net.consensys.wittgenstein.core.messages.Message;

/**
 * Adds some concept to a standard network: it's about to send blocks between nodes. Blockchain
 * nodes have a head, a method to choose between blocks and so on.
 */
@SuppressWarnings({"SameParameterValue", "FieldCanBeLocal", "unused"})
public class BlockChainNetwork<TB extends Block, TN extends BlockChainNode<TB>>
    extends Network<TN> {
  /** The node we use as an observer for the final stats */
  public TN observer;

  public void addObserver(TN observer) {
    this.observer = observer;
    addNode(observer);
  }

  public static class SendBlock<TB extends Block, TN extends BlockChainNode<TB>>
      extends Message<TN> {
    final TB toSend;

    public SendBlock(TB toSend) {
      this.toSend = toSend;
    }

    @Override
    public void action(Network<TN> network, TN fromNode, TN toNode) {
      toNode.onBlock(toSend);
    }

    @Override
    public String toString() {
      return "SendBlock{" + "toSend=" + toSend.id + '}';
    }
  }

  /**
   * On a blockchain network all the blocks are exchanged all the time. We simulate this with a full
   * resent after each partition.
   */
  @Override
  public void endPartition() {
    super.endPartition();

    for (TN n : allNodes) {
      SendBlock<TB, TN> sb = new BlockChainNetwork.SendBlock<>(n.head);
      sendAll(sb, n);
    }
  }

  public void printStat(boolean small) {
    HashMap<Integer, Set<Block>> productionCount = new HashMap<>();
    Set<BlockChainNode<?>> blockProducers = new HashSet<>();

    Block cur = observer.head;

    int blockInChain = 0;
    while (cur != observer.genesis) {
      assert cur != null;
      assert cur.producer != null;
      if (!small) System.out.println("block: " + cur.toString());
      blockInChain++;

      productionCount.putIfAbsent(cur.producer.nodeId, new HashSet<>());
      productionCount.get(cur.producer.nodeId).add(cur);
      blockProducers.add(cur.producer);

      cur = cur.parent;
    }

    if (!small) {
      System.out.println(
          "block count:"
              + blockInChain
              + " on "
              + Block.getLastBlockId()
              + ", all tx: "
              + observer.head.lastTxId);
    }
    List<BlockChainNode> bps = new ArrayList<>(blockProducers);
    bps.sort(Comparator.comparingInt(o -> o.nodeId));

    for (BlockChainNode<?> bp : bps) {
      int bpTx = 0;
      for (Block b : productionCount.get(bp.nodeId)) {
        bpTx += b.txCount();
      }
      if (!small | bp.byzantine) {
        System.out.println(
            bp
                + "; "
                + productionCount.get(bp.nodeId).size()
                + "; "
                + bpTx
                + "; "
                + bp.msgSent
                + "; "
                + bp.msgReceived);
      }
    }
  }
}
