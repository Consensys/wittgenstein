package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A simple flood protocol. When a node receives a message it has not yet received, it sends it to
 * all its peer. That's the "Flood routing" protocol documented here:
 * https://github.com/libp2p/specs/tree/master/pubsub/gossipsub
 */
public class P2PFlood implements Protocol {
  /**
   * The number of nodes in the network
   */
  private final int nodeCount;

  /**
   * The number of nodes sending a message
   */
  private final int msgCount;


  private final P2PNetwork network = new P2PNetwork(15);
  private final Node.NodeBuilder nb = new Node.NodeBuilderWithRandomPosition(network.rd);

  class P2PFloodNode extends P2PNode {

    P2PFloodNode(NodeBuilder nb) {
      super(nb);
    }

    protected void onFlood(P2PNode from, P2PNetwork.FloodMessage floodMessage) {
      if (received.size() == msgCount) {
        doneAt = network.time;
      }
    }
  }

  private P2PFlood(int nodeCount, int msgCount) {
    this.nodeCount = nodeCount;
    this.msgCount = msgCount;
  }

  @Override
  public String toString() {
    return "P2PFlood{" + "nodeCount=" + nodeCount + ", msgCount=" + msgCount + '}';
  }

  @Override
  public Protocol copy() {
    return new P2PFlood(nodeCount, msgCount);
  }

  public void init() {
    for (int i = 0; i < nodeCount; i++) {
      network.addNode(new P2PFloodNode(nb));
    }
    network.setPeers();

    Set<Integer> senders = new HashSet<>(msgCount);
    while (senders.size() < msgCount) {
      int nodeId = network.rd.nextInt(nodeCount);
      if (senders.add(nodeId)) {
        P2PFloodNode from = (P2PFloodNode) network.getNodeById(nodeId);
        P2PNetwork.FloodMessage m = network.new FloodMessage(48);
        from.onFlood(from, m);
        network.send(m, from, from.peers);
      }
    }
  }

  @Override
  public Network<?> network() {
    return network;
  }

  private static void floodTime() {
    P2PFlood p = new P2PFlood(1000, 300);

    Predicate<Protocol> endWhen = p1 -> {
      for (Node n : p1.network().allNodes) {
        if (!n.down && n.getDoneAt() == 0) {
          return true;
        }
      }
      return false;
    };

    StatsHelper.SimpleStatsGetter sg =
        liveNodes -> StatsHelper.getStatsOn(liveNodes, n -> ((P2PNode) n).received.size());

    new ProgressPerTime(p, "flood with " + p.nodeCount + " nodes", "msg count", sg).run(endWhen);
  }

  public static void main(String... args) {
    floodTime();
  }
}
