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
   * Average number of peers
   */
  private final int peersCount;

  /**
   * How long we wait between peers when we forward a message to our peers.
   */
  private final int delayBetweenSends;

  private final P2PNetwork network;
  private final Node.NodeBuilder nb = new Node.NodeBuilderWithRandomPosition();

  class P2PFloodNode extends P2PNode {
    /**
     * @param down - if the node is marked down, it won't send/receive messages, but will still be
     *        included in the peers. As such it's a byzantine behavior: officially available but
     *        actually not participating.
     */
    P2PFloodNode(NodeBuilder nb, boolean down) {
      super(network.rd, nb, down);
      this.down = down;
    }

    @Override
    protected void onFlood(P2PNode from, P2PNetwork.FloodMessage floodMessage) {
      if (received.size() == msgCount) {
        doneAt = network.time;
      }
    }
  }

  private P2PFlood(int nodeCount, int deadNodeCount, int delayBeforeResent, int msgCount,
      int peersCount, int delayBetweenSends, NetworkLatency nl) {
    this.nodeCount = nodeCount;
    this.deadNodeCount = deadNodeCount;
    this.delayBeforeResent = delayBeforeResent;
    this.msgCount = msgCount;
    this.peersCount = peersCount;
    this.delayBetweenSends = delayBetweenSends;
    this.network = new P2PNetwork(peersCount, true);
    this.network.setNetworkLatency(nl);
  }

  @Override
  public String toString() {
    return "nodes=" + nodeCount + ", deadNodes=" + deadNodeCount + ", delayBeforeResent="
        + delayBeforeResent + "ms, msgSent=" + msgCount + ", peers(minimum)=" + peersCount
        + ", peers(avg)=" + network.avgPeers() + ", delayBetweenSends=" + delayBetweenSends
        + "ms, latency=" + network.networkLatency.getClass().getSimpleName();
  }

  @Override
  public Protocol copy() {
    return new P2PFlood(nodeCount, deadNodeCount, delayBeforeResent, msgCount, peersCount,
        delayBetweenSends, network.networkLatency);
  }

  public void init() {
    for (int i = 0; i < nodeCount; i++) {
      network.addNode(new P2PFloodNode(nb, i < deadNodeCount));
    }
    network.setPeers();

    Set<Integer> senders = new HashSet<>(msgCount);
    while (senders.size() < msgCount) {
      int nodeId = network.rd.nextInt(nodeCount);
      P2PFloodNode from = (P2PFloodNode) network.getNodeById(nodeId);
      if (!from.down && senders.add(nodeId)) {
        P2PNetwork.FloodMessage m =
            network.new FloodMessage(1, delayBeforeResent, delayBetweenSends);
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
    P2PFlood p = new P2PFlood(4500, 4000, 500, 1, 50, 0, new NetworkLatency.IC3NetworkLatency());

    Predicate<Protocol> contIf = p1 -> {
      if (p1.network().time > 30000) {
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

    new ProgressPerTime(p, "", "node count", sg, 1000).run(contIf);
  }

  public static void main(String... args) {
    floodTime();
  }
}
