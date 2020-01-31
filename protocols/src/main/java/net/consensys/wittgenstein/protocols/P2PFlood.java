package net.consensys.wittgenstein.protocols;

import static net.consensys.wittgenstein.core.utils.StatsHelper.getStatsOn;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.messages.FloodMessage;
import net.consensys.wittgenstein.core.utils.StatsHelper;

/**
 * A simple flood protocol. When a node receives a message it has not yet received, it sends it to
 * all its peers. That's the "Flood routing" protocol documented here:
 * https://github.com/libp2p/specs/tree/master/pubsub/gossipsub
 */
@SuppressWarnings("WeakerAccess")
public class P2PFlood implements Protocol {
  private final P2PFloodParameters params;
  private final P2PNetwork<P2PFloodNode> network;
  private final NodeBuilder nb;

  class P2PFloodNode extends P2PNode<P2PFloodNode> {
    /**
     * @param down - if the node is marked down, it won't send/receive messages, but will still be
     *     included in the peers. As such it's a byzantine behavior: officially available but
     *     actually not participating.
     */
    P2PFloodNode(NodeBuilder nb, boolean down) {
      super(network.rd, nb, down);
      if (down) {
        stop();
      }
    }

    @Override
    public void onFlood(P2PFloodNode from, FloodMessage floodMessage) {
      if (getMsgReceived(floodMessage.msgId()).size() == params.msgCount) {
        doneAt = network.time;
      }
    }
  }

  public static class P2PFloodParameters extends WParameters {
    /** The total number of nodes in the network */
    private final int nodeCount;

    /** The total number of dead nodes: they won't be connected. */
    private final int deadNodeCount;

    /**
     * The time a node wait before resending a message to its peers. This can be used to represent
     * the validation time or an extra delay if the message is supposed to be big (i.e. a block in a
     * block chain for example
     */
    private final int delayBeforeResent;

    /** The number of nodes sending a message (each one sending a different message) */
    private final int msgCount;

    /** The number of messages to receive for a node to consider the protocol finished */
    final int msgToReceive;

    /** Average number of peers */
    final int peersCount;

    /** How long we wait between peers when we forward a message to our peers. */
    private final int delayBetweenSends;

    final String nodeBuilderName;
    final String networkLatencyName;

    // For json
    @SuppressWarnings("unused")
    public P2PFloodParameters() {
      this.nodeCount = 100;
      this.deadNodeCount = 10;
      this.delayBeforeResent = 50;
      this.msgCount = 1;
      this.msgToReceive = 1;
      this.peersCount = 10;
      this.delayBetweenSends = 30;
      this.nodeBuilderName = null;
      this.networkLatencyName = null;
    }

    public P2PFloodParameters(
        int nodeCount,
        int deadNodeCount,
        int delayBeforeResent,
        int msgCount,
        int msgToReceive,
        int peersCount,
        int delayBetweenSends,
        String nodeBuilderName,
        String networkLatencyName) {
      this.nodeCount = nodeCount;
      this.deadNodeCount = deadNodeCount;
      this.delayBeforeResent = delayBeforeResent;
      this.msgCount = msgCount;
      this.msgToReceive = msgToReceive;
      this.peersCount = peersCount;
      this.delayBetweenSends = delayBetweenSends;
      this.nodeBuilderName = nodeBuilderName;
      this.networkLatencyName = networkLatencyName;
    }
  }

  public P2PFlood(P2PFloodParameters params) {
    this.params = params;
    this.network = new P2PNetwork<>(params.peersCount, true);
    this.nb = RegistryNodeBuilders.singleton.getByName(params.nodeBuilderName);
    this.network.setNetworkLatency(
        RegistryNetworkLatencies.singleton.getByName(params.networkLatencyName));
  }

  @Override
  public String toString() {
    return "nodes="
        + params.nodeCount
        + ", deadNodes="
        + params.deadNodeCount
        + ", delayBeforeResent="
        + params.delayBeforeResent
        + "ms, msgSent="
        + params.msgCount
        + ", msgToReceive="
        + params.msgToReceive
        + ", peers(minimum)="
        + params.peersCount
        + ", peers(avg)="
        + network.avgPeers()
        + ", delayBetweenSends="
        + params.delayBetweenSends
        + "ms, latency="
        + network.networkLatency.getClass().getSimpleName();
  }

  @Override
  public P2PFlood copy() {
    return new P2PFlood(params);
  }

  public void init() {
    for (int i = 0; i < params.nodeCount; i++) {
      network.addNode(new P2PFloodNode(nb, i < params.deadNodeCount));
    }
    network.setPeers();

    Set<Integer> senders = new HashSet<>(params.msgCount);
    while (senders.size() < params.msgCount) {
      int nodeId = network.rd.nextInt(params.nodeCount);
      P2PFloodNode from = network.getNodeById(nodeId);
      if (!from.isDown() && senders.add(nodeId)) {
        FloodMessage<P2PFloodNode> m =
            new FloodMessage<>(1, params.delayBeforeResent, params.delayBetweenSends);
        network.sendPeers(m, from);
        if (params.msgCount == 1) {
          from.doneAt = 1;
        }
      }
    }
  }

  @Override
  public Network<P2PFloodNode> network() {
    return network;
  }

  private static void floodTime() {
    int liveNodes = 64 * 128;
    final int threshold = (int) (0.99 * liveNodes);
    P2PFloodParameters params =
        new P2PFloodParameters(liveNodes, 0, 1, 1, threshold, 15, 1, null, null);
    P2PFlood p = new P2PFlood(params);

    Predicate<Protocol> contIf =
        p1 -> {
          if (p1.network().time > 50000) {
            return false;
          }

          for (Node n : p1.network().allNodes) {
            if (!n.isDown() && n.getDoneAt() == 0) {
              return true;
            }
          }
          return false;
        };

    StatsHelper.StatsGetter sg =
        new StatsHelper.StatsGetter() {
          final List<String> fields = new StatsHelper.Counter(0).fields();

          @Override
          public List<String> fields() {
            return fields;
          }

          @Override
          public StatsHelper.Stat get(List<? extends Node> liveNodes) {
            return new StatsHelper.Counter(
                liveNodes.stream().filter(n -> n.getDoneAt() > 0).count());
          }
        };

    new ProgressPerTime(p, "", "node count", sg, 1, null, 10, TimeUnit.MILLISECONDS).run(contIf);
  }

  static class BasicStats {
    final int doneAtMin;
    final int doneAtAvg;
    final int doneAtMax;

    final int msgRcvMin;
    final int msgRcvAvg;
    final int msgRcvMax;

    BasicStats(
        long doneAtMin,
        long doneAtAvg,
        long doneAtMax,
        long msgRcvMin,
        long msgRcvAvg,
        long msgRcvMax) {
      this.doneAtMin = (int) doneAtMin;
      this.doneAtAvg = (int) doneAtAvg;
      this.doneAtMax = (int) doneAtMax;
      this.msgRcvMin = (int) msgRcvMin;
      this.msgRcvAvg = (int) msgRcvAvg;
      this.msgRcvMax = (int) msgRcvMax;
    }

    @Override
    public String toString() {
      return doneAtAvg + ", " + msgRcvAvg;
    }
  }

  private static class SigsSentGetter extends StatsHelper.SimpleStatsGetter {

    @Override
    public StatsHelper.Stat get(List<? extends Node> liveNodes) {
      return getStatsOn(liveNodes, Node::getBytesReceived);
    }
  }

  private static BasicStats run(int rounds, P2PFloodParameters params) {
    List<StatsHelper.StatsGetter> stats =
        List.of(
            new StatsHelper.DoneAtStatGetter(),
            new StatsHelper.MsgReceivedStatGetter(),
            new SigsSentGetter());
    RunMultipleTimes<P2PFlood> rmt =
        new RunMultipleTimes<>(new P2PFlood(params), rounds, 0, stats, null);
    List<StatsHelper.Stat> res = rmt.run(RunMultipleTimes.contUntilDone());

    return new BasicStats(
        res.get(0).get("min"),
        res.get(0).get("avg"),
        res.get(0).get("max"),
        res.get(1).get("min"),
        res.get(1).get("avg"),
        res.get(1).get("max"));
  }

  public static void time() {
    for (int n = 128; n <= 1024; n *= 2) {
      P2PFloodParameters params = new P2PFloodParameters(n, 0, 1, n, 1, 13, 0, null, null);
      BasicStats bs = run(5, params);
      System.out.println(n + "," + n + ", " + bs);
      System.out.flush();
    }
  }

  public static void main(String... args) {
    time();
  }
}
