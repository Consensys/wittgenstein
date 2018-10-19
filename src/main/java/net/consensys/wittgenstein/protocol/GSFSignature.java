package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.NetworkLatency;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.utils.MoreMath;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import net.consensys.wittgenstein.tools.Graph;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * A p2p protocol for BLS signature aggregation.
 * <p>
 * A node: Sends its states to all its direct peers whenever it changes Keeps the list of the states
 * of its direct peers Sends, every x milliseconds, to one of its peers a set of missing signatures
 * Runs in parallel a task to validate the signatures sets it has received.
 */
@SuppressWarnings("WeakerAccess") public class GSFSignature {
  /**
   * The nuumber of nodes in the network
   */
  final int nodeCount;

  /**
   * The number of signatures to reach to finish the protocol.
   */
  final int threshold;

  /**
   * The typical number of peers a peer has. It can be less (but at least 3) or more.
   */
  final int connectionCount;

  /**
   * The time it takes to do a pairing for a node.
   */
  final int pairingTime;

  /**
   */
  final boolean doubleAggregateStrategy;


  public int timeoutPerLevelMs = 50;
  public int periodDurationMs = 20;

  final Network<GSFNode> network;
  final Node.NodeBuilder nb;

  public GSFSignature(int nodeCount, int threshold, int connectionCount, int pairingTime,
    boolean doubleAggregateStrategy) {
    this.nodeCount = nodeCount;
    this.threshold = threshold;
    this.connectionCount = connectionCount;
    this.pairingTime = pairingTime;
    this.doubleAggregateStrategy = doubleAggregateStrategy;

    this.network = new Network<>();
    this.nb = new Node.NodeBuilderWithRandomPosition(network.rd);
  }

  @Override public String toString() {
    return "GSFSignature, " + "nodes=" + nodeCount + ", threshold=" + threshold
      + ", connections=" + connectionCount + ", pairing=" + pairingTime
      + ", doubleAggregate=" + doubleAggregateStrategy + ", timeout="
      + timeoutPerLevelMs + "ms, periodTime=" + periodDurationMs + "ms";
  }

  static class SendSigs extends Network.Message<GSFNode> {
    final BitSet sigs;
    final int size;

    public SendSigs(BitSet sigs, int sigCount) {
      this.sigs = (BitSet) sigs.clone();
      // Size = bit field + the signatures included
      this.size = sigs.length() / 8 + sigCount * 48;
    }


    @Override public int size() {
      return size;
    }


    @Override public void action(GSFNode from, GSFNode to) {
      to.onNewSig(sigs);
    }
  }


  public class GSFNode extends Node {
    final BitSet verifiedSignatures = new BitSet(nodeCount);
    final Set<BitSet> toVerify = new HashSet<>();
    final List<SFLevel> levels = new ArrayList<>();

    boolean done = false;
    long doneAt = 0;

    GSFNode() {
      super(nb);

      // We start with a single verified signature: our signature.
      verifiedSignatures.set(nodeId, true);
    }

    public void initLevel() {
      SFLevel last = new SFLevel();
      levels.add(last);
      for (int l = 0; l < MoreMath.log2(nodeCount); l++) {
        SFLevel sfl = new SFLevel(last);
        levels.add(sfl);
        last = sfl;
      }
    }

    public void doCycle() {
      for (SFLevel sfl : levels) {
        sfl.doCycle();
      }
    }

    // If nodeId == 0
    // l0: 0               => send to 1
    // l1: 0 1             => send to 2 3
    // l2: 0 1 2 3         => send to 4 5 6 7
    // l3: 0 1 2 3 4 5 6 7 => send to 8 9 10 11 12 13 14 15 16
    public class SFLevel {
      final int level;
      final List<GSFNode> peers; // The peers when we have all signatures for this level.
      final BitSet allSigsInLevel; // 1 for the signatures we should have at this level
      BitSet verifiedSignatures = new BitSet(); // The signatures verified in this level

      /**
       * We're going to contact all nodes, one after the other. That's our position
       *  in the peers' list.
       */
      int posInLevel = 0;

      /**
       * Number of message to send with the full set of signature. When it's zero, we're done.
       */
      int objective;

      /**
       * Build a level 0 object. At level 0 need (and have) only our own signature.
       * We have only one peer to contact.
       */
      public SFLevel() {
        level = 0;
        allSigsInLevel = new BitSet(nodeId + 1);
        allSigsInLevel.set(nodeId);
        verifiedSignatures = (BitSet) allSigsInLevel.clone();
        peers = randomSubset(sanFerminPeers(1), 1);

        objective = 1; // We have only 1 peer
      }

      /**
       * Build a level on top of the previous one.
       */
      public SFLevel(SFLevel previousLevel) {
        this.level = previousLevel.level + 1;

        // Sigs needed to finish the current levels are:
        //  sigs of the previous level + peers of the previous level
        allSigsInLevel = (BitSet) previousLevel.allSigsInLevel.clone();
        allSigsInLevel.or(sanFerminPeers(this.level));
        BitSet others = sanFerminPeers(level + 1);
        peers = randomSubset(others, Integer.MAX_VALUE);
        objective = Math.min(peers.size(), 10);
      }

      int maxSigsInLevel() {
        return (int) Math.pow(2, level);
      }

      boolean hasStarted() {
        return network.time > level * timeoutPerLevelMs
          || verifiedSignatures.cardinality() == maxSigsInLevel();
      }

      void doCycle() {
        if (objective == 0 || !hasStarted()) {
          return;
        }
        if (verifiedSignatures.cardinality() == maxSigsInLevel()) {
          objective--;
        }

        GSFNode dest = peers.get(posInLevel);
        if (++posInLevel >= peers.size()) {
          posInLevel = 0;
        }

        assert verifiedSignatures.cardinality() <= maxSigsInLevel();
        SendSigs ss = new SendSigs(verifiedSignatures, 1);
        network.send(ss, GSFNode.this, dest);
      }
    }


    public BitSet sanFerminPeers(int round) {
      if (round < 1) {
        throw new IllegalArgumentException("round=" + round);
      }
      BitSet res = new BitSet(nodeCount);
      int cMask = (1 << round) - 1;
      int start = (cMask | nodeId) ^ cMask;
      int end = nodeId | cMask;
      end = Math.min(end, nodeCount - 1);
      res.set(start, end + 1);
      res.set(nodeId, false);

      return res;
    }


    /**
     * If the state has changed we send a message to all. If we're done, we update all our peers: it
     * will be our last message.
     */
    void updateVerifiedSignatures(BitSet sigs) {
      int oldCard = verifiedSignatures.cardinality();
      verifiedSignatures.or(sigs);
      int newCard = verifiedSignatures.cardinality();

      if (newCard > oldCard) {
        if (!done && verifiedSignatures.cardinality() >= threshold) {
          doneAt = network.time;
          done = true;
        }

        for (SFLevel sfl : levels) {
          if (sfl.objective > 0 && sfl.verifiedSignatures.cardinality() < sfl.maxSigsInLevel()) {
            // TODO: on doit se limiter au niveau courant, on ne peut pas propager au niveau précédent...
            //  on améliore artificiellement les perfs avec ca.
            BitSet newSigs = (BitSet) verifiedSignatures.clone();
            newSigs.and(sfl.allSigsInLevel);
            if (newSigs.cardinality() > sfl.verifiedSignatures.cardinality()) {
              sfl.verifiedSignatures = newSigs;
            }
          }
        }
      }
    }

    private List<GSFNode> randomSubset(BitSet nodes, int nodeCt) {
      List<GSFNode> res = new ArrayList<>();
      int pos = 0;
      do {
        int cur = nodes.nextSetBit(pos);
        if (cur >= 0) {
          res.add(network.getNodeById(cur));
          pos = cur + 1;
        } else {
          break;
        }
      } while (true);

      Collections.shuffle(res, network.rd);
      if (res.size() > nodeCt) {
        return res.subList(0, nodeCt);
      } else {
        return res;
      }
    }


    /**
     * Nothing much to do when we receive a sig set: we just add it to our toVerify list.
     */
    void onNewSig(BitSet sigs) {
      toVerify.add(sigs);
    }

    public void checkSigs() {
      if (doubleAggregateStrategy) {
        checkSigs2();
      } else {
        checkSigs1();
      }
    }


    /**
     * Strategy 1: we select the set of signatures which contains the most new signatures. As we
     * send a message to all our peers each time our state change we send more messages with this
     * strategy.
     */
    protected void checkSigs1() {
      BitSet best = null;
      int bestV = 0;
      Iterator<BitSet> it = toVerify.iterator();
      while (it.hasNext()) {
        BitSet o1 = it.next();
        BitSet oo1 = ((BitSet) o1.clone());
        oo1.andNot(verifiedSignatures);
        int v1 = oo1.cardinality();

        if (v1 == 0) {
          it.remove();
        } else {
          if (v1 > bestV) {
            bestV = v1;
            best = o1;
          }
        }
      }

      if (best != null) {
        toVerify.remove(best);
        final BitSet tBest = best;
        network.registerTask(() -> GSFNode.this.updateVerifiedSignatures(tBest),
          network.time + pairingTime * 2, GSFNode.this);
      }
    }

    /**
     * Strategy 2: we aggregate all signatures together
     */
    protected void checkSigs2() {
      BitSet agg = null;
      for (BitSet o1 : toVerify) {
        if (agg == null) {
          agg = o1;
        } else {
          agg.or(o1);
        }
      }
      toVerify.clear();

      if (agg != null) {
        BitSet oo1 = ((BitSet) agg.clone());
        oo1.andNot(verifiedSignatures);

        if (oo1.cardinality() > 0) {
          // There is at least one signature we don't have yet
          final BitSet tBest = agg;
          network.registerTask(() -> GSFNode.this.updateVerifiedSignatures(tBest),
            network.time + pairingTime * 2, GSFNode.this);
        }
      }
    }


    @Override public String toString() {
      return "P2PSigNode{" + "nodeId=" + nodeId + ", doneAt=" + doneAt + ", sigs="
        + verifiedSignatures.cardinality() + ", msgReceived=" + msgReceived + ", msgSent=" + msgSent
        + ", KBytesSent=" + bytesSent / 1024 + ", KBytesReceived=" + bytesReceived / 1024 + '}';
    }
  }

  void init() {
    for (int i = 0; i < nodeCount; i++) {
      final GSFNode n = new GSFNode();
      network.addNode(n);
    }
    for (GSFNode n : network.allNodes) {
      n.initLevel();
      network.registerPeriodicTask(n::doCycle, 1, periodDurationMs, n);
      network.registerConditionalTask(n::checkSigs, 1, pairingTime, n, () -> !n.toVerify.isEmpty(),
        () -> !n.done);
    }
  }


  public static void sigsPerTime() {
    NetworkLatency.NetworkLatencyByDistance nl = new NetworkLatency.NetworkLatencyByDistance();
    int nodeCt = 1024;
    GSFSignature ps1 = new GSFSignature(nodeCt, nodeCt, 15, 3, true);
    ps1.network.setNetworkLatency(nl);
    String desc = ps1.toString();
    Graph graph = new Graph("number of signatures per time (" + desc + ")", "time in ms",
      "number of signatures");
    Graph.Series series1min = new Graph.Series("signatures count - worse node");
    Graph.Series series1max = new Graph.Series("signatures count - best node");
    Graph.Series series1avg = new Graph.Series("signatures count - average");
    // graph.addSerie(series1min);
    graph.addSerie(series1max);
    graph.addSerie(series1avg);

    System.out.println(nl + " " + desc);
    ps1.init();

    StatsHelper.SimpleStats s;
    do {
      ps1.network.runMs(10);
      s = StatsHelper.getStatsOn(ps1.network.allNodes,
        n -> ((GSFNode) n).verifiedSignatures.cardinality());
      series1min.addLine(new Graph.ReportLine(ps1.network.time, s.min));
      series1max.addLine(new Graph.ReportLine(ps1.network.time, s.max));
      series1avg.addLine(new Graph.ReportLine(ps1.network.time, s.avg));
    } while (s.min != ps1.nodeCount);

    try {
      graph.save(new File("/tmp/graph.png"));
    } catch (IOException e) {
      System.err.println("Can't generate the graph: " + e.getMessage());
    }

    System.out.println(
      "bytes sent: " + StatsHelper.getStatsOn(ps1.network.allNodes, Node::getBytesSent));
    System.out.println(
      "bytes rcvd: " + StatsHelper.getStatsOn(ps1.network.allNodes, Node::getBytesReceived));
    System.out.println(
      "msg sent: " + StatsHelper.getStatsOn(ps1.network.allNodes, Node::getMsgSent));
    System.out.println(
      "msg rcvd: " + StatsHelper.getStatsOn(ps1.network.allNodes, Node::getMsgReceived));
    System.out.println(
      "done at: " + StatsHelper.getStatsOn(ps1.network.allNodes, n -> ((GSFNode) n).doneAt));
  }


  public static void main(String... args) {
    NetworkLatency nl = new NetworkLatency.NetworkLatencyByDistance();
    System.out.println("" + nl);

    sigsPerTime();
  }
}
