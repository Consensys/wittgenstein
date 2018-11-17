package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A simple flood protocol. When a node receives a message it has not yet received, it sends it to
 * all its peer. That's the "Flood routing" protocol documented here:
 * https://github.com/libp2p/specs/tree/master/pubsub/gossipsub
 */
public class P2PFlood implements Protocol {
  /**
   * The total number of nodes in the network
   */
  private final int nodeCount;

  /**
   * The total number of dead nodes: they won't be connected.
   */
  private final int deadNodeCount;

  /**
   * The time a node wait before resending a message to its peers. This can be used to represent the
   * validation time or an extra delay if the message is supposed to be big (i.e. a block in a block
   * chain for example
   */
  private final int delayBeforeResent;

  /**
   * The number of nodes sending a message
   */
  private final int msgCount;

  /**
   *
   */
  private final int peersCount = 15;


  private final P2PNetwork network = new P2PNetwork(peersCount);
  private final Node.NodeBuilder nb = new Node.NodeBuilderWithRandomPosition();

  class P2PFloodNode extends P2PNode {

    P2PFloodNode(NodeBuilder nb) {
      super(network.rd, nb);
    }

    P2PFloodNode(NodeBuilder nb, boolean byzantine) {
      super(network.rd, nb, byzantine);
    }

    @Override
    protected void onFlood(P2PNode from, P2PNetwork.FloodMessage floodMessage) {
      if (received.size() == msgCount) {
        doneAt = network.time;
      }
    }
  }

  class ByzP2PFloodNode extends P2PFloodNode {

    ByzP2PFloodNode(NodeBuilder nb) {
      super(nb, true);
      down = true;
    }

    protected void onFlood(P2PNode from, P2PNetwork.FloodMessage floodMessage) {
      // don't participate in the protocol
    }
  }

  private P2PFlood(int nodeCount, int deadNodeCount, int delayBeforeResent, int msgCount) {
    this.nodeCount = nodeCount;
    this.deadNodeCount = deadNodeCount;
    this.delayBeforeResent = delayBeforeResent;
    this.msgCount = msgCount;
  }

  @Override
  public String toString() {
    return "P2PFlood{" + "nodeCount=" + nodeCount + ", deadNodeCount=" + deadNodeCount
        + ", delayBeforeResent=" + delayBeforeResent + ", msgCount=" + msgCount + ", peersCount="
        + peersCount + '}';
  }

  @Override
  public Protocol copy() {
    return new P2PFlood(nodeCount, deadNodeCount, delayBeforeResent, msgCount);
  }

  public void init() {
    for (int i = 0; i < nodeCount; i++) {
      if (i < deadNodeCount) {
        network.addNode(new ByzP2PFloodNode(nb));
      } else {
        network.addNode(new P2PFloodNode(nb));
      }
    }
    network.setPeers();

    Set<Integer> senders = new HashSet<>(msgCount);
    while (senders.size() < msgCount) {
      int nodeId = network.rd.nextInt(nodeCount);
      P2PFloodNode from = (P2PFloodNode) network.getNodeById(nodeId);
      if (!from.down && senders.add(nodeId)) {
        P2PNetwork.FloodMessage m = network.new FloodMessage(48, delayBeforeResent);
        m.kickoff(from);
        if (msgCount == 1) {
          from.doneAt = 1;
        }
      }
    }
  }

  @Override
  public Network<?> network() {
    return network;
  }

  private static void floodTime() {
    P2PFlood p = new P2PFlood(4500, 4000, 500, 1);
    p.network.setNetworkLatency(new NetworkLatency.IC3NetworkLatency());

    Predicate<Protocol> contIf = p1 -> {
      if (p1.network().time > 40000) {
        return false;
      }

      for (Node n : p1.network().allNodes) {
        if (!n.down && n.getDoneAt() == 0) {
          return true;
        }
      }
      return false;
    };

    StatsHelper.StatsGetter sg = new StatsHelper.StatsGetter() {
      final List<String> fields = new StatsHelper.Counter(0).fields();

      @Override
      public List<String> fields() {
        return fields;
      }

      @Override
      public StatsHelper.Stat get(List<? extends Node> liveNodes) {
        return new StatsHelper.Counter(liveNodes.stream().filter(n -> n.getDoneAt() > 0).count());
      }
    };

    new ProgressPerTime(p, "flood with " + p.nodeCount + " nodes", "node count", sg, 1).run(contIf);
  }

  public static void main(String... args) {
    floodTime();
  }
}
