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
import java.util.stream.Collectors;

/**
 * A p2p protocol for BLS signature aggregation.
 * <p>
 * A node: Sends its states to all its direct peers whenever it changes Keeps the list of the states
 * of its direct peers Sends, every x milliseconds, to one of its peers a set of missing signatures
 * Runs in parallel a task to validate the signatures sets it has received.
 */
@SuppressWarnings("WeakerAccess")
public class GSFSignature {
  /**
   * The number of nodes in the network
   */
  final int nodeCount;

  /**
   * The number of signatures to reach to finish the protocol.
   */
  final int threshold;

  /**
   * The time it takes to do a pairing for a node.
   */
  final int pairingTime;

  final public int timeoutPerLevelMs;
  final public int periodDurationMs;
  final int acceleratedCallsCount;

  final int nodesDown;

  final Network<GSFNode> network;
  final Node.NodeBuilder nb;

  public GSFSignature(int nodeCount, double ratioThreshold, int pairingTime, int timeoutPerLevelMs,
      int periodDurationMs, int acceleratedCallsCount, double ratioNodesDown) {
    if (ratioNodesDown >= 1 || ratioNodesDown < 0 || ratioThreshold > 1 || ratioThreshold <= 0
        || (ratioNodesDown + ratioThreshold > 1)) {
      throw new IllegalArgumentException(
          "ratioNodesDown=" + ratioNodesDown + ", ratioThreshold=" + ratioThreshold);
    }
    this.nodeCount = nodeCount;
    this.pairingTime = pairingTime;
    this.timeoutPerLevelMs = timeoutPerLevelMs;
    this.periodDurationMs = periodDurationMs;
    this.acceleratedCallsCount = acceleratedCallsCount;
    this.nodesDown = (int) (ratioNodesDown * nodeCount);
    this.threshold = (int) (ratioThreshold * nodeCount);

    this.network = new Network<>();
    this.nb = new Node.NodeBuilderWithRandomPosition(network.rd);
  }

  @Override
  public String toString() {
    return "GSFSignature, " + "nodes=" + nodeCount + ", threshold=" + threshold + ", pairing="
        + pairingTime + "ms, level timeout=" + timeoutPerLevelMs + "ms, period=" + periodDurationMs
        + "ms, acceleratedCallsCount=" + acceleratedCallsCount + ", dead nodes=" + nodesDown;
  }

  static class SendSigs extends Network.Message<GSFNode> {
    final BitSet sigs;
    final int level;
    final boolean levelFinished;
    final int size;

    public SendSigs(BitSet sigs, GSFNode.SFLevel l, boolean levelFinished) {
      this.sigs = (BitSet) sigs.clone();
      this.level = l.level;
      // Size = level + bit field + the signatures included
      this.size = 1 + l.expectedSigs() / 8 + 48;
      this.levelFinished = levelFinished;
    }


    @Override
    public int size() {
      return size;
    }


    @Override
    public void action(GSFNode from, GSFNode to) {
      to.onNewSig(from, this);
    }
  }


  public class GSFNode extends Node {
    final Set<SendSigs> toVerify = new HashSet<>();
    final List<SFLevel> levels = new ArrayList<>();
    final BitSet verifiedSignatures = new BitSet(nodeCount);

    boolean done = false;
    long doneAt = 0;

    GSFNode() {
      super(nb);
      verifiedSignatures.set(nodeId);
    }

    public void initLevel() {
      int roundedPow2NodeCount = MoreMath.roundPow2(nodeCount);
      BitSet allPreviousNodes = new BitSet(nodeCount);
      SFLevel last = new SFLevel();
      levels.add(last);
      for (int l = 1; Math.pow(2, l) <= roundedPow2NodeCount; l++) {
        allPreviousNodes.or(last.waitedSigs);
        last = new SFLevel(last, allPreviousNodes);
        levels.add(last);
      }
    }

    public void doCycle() {
      BitSet toSend = new BitSet(nodeCount);
      for (SFLevel sfl : levels) {
        sfl.doCycle(toSend); // We send what we collected at the previous levels
        toSend.or(sfl.verifiedSignatures);
      }
    }

    /**
     * At a given level, we send the signatures received from the previous level and we expect the
     * equivalent from our peers of this level.<br/>
     * If nodeId == 0<br/>
     * l0: 0 => send to 1 <br/>
     * l1: 0 1 => send to 2 3 <br/>
     * l2: 0 1 2 3 => send to 4 5 6 7 <br/>
     * l3: 0 1 2 3 4 5 6 7 => send to 8 9 10 11 12 13 14 15 16 <br/>
     */
    public class SFLevel {
      final int level;
      final List<GSFNode> peers; // The peers when we have all signatures for this level.
      final BitSet waitedSigs; // 1 for the signatures we should have at this level
      BitSet verifiedSignatures = new BitSet(); // The signatures verified in this level
      final Set<GSFNode> finishedPeers = new HashSet<>();

      /**
       * We're going to contact all nodes, one after the other. That's our position in the peers'
       * list.
       */
      int posInLevel = 0;

      /**
       * Number of message to send for each new update.
       */
      int remainingCalls;

      /**
       * Build a level 0 object. At level 0 need (and have) only our own signature. We have only one
       * peer to contact.
       */
      public SFLevel() {
        level = 0;
        waitedSigs = new BitSet(nodeId + 1);
        waitedSigs.set(nodeId);
        verifiedSignatures = waitedSigs;
        peers = Collections.emptyList();
        remainingCalls = 0;
      }

      /**
       * Build a level on top of the previous one.
       */
      public SFLevel(SFLevel previousLevel, BitSet allPreviousNodes) {
        this.level = previousLevel.level + 1;

        // Signatures needed to finish the current level are:
        //  sigs of the previous level + peers of the previous level.
        //  If we have all this we have finished this
        waitedSigs = sanFerminPeers(this.level);
        waitedSigs.andNot(allPreviousNodes);
        if (waitedSigs.cardinality() != expectedSigs()) {
          throw new IllegalStateException("level=" + level + ", expectedSigs()=" + expectedSigs()
              + ", " + waitedSigs.cardinality());
        }
        peers = randomSubset(waitedSigs, Integer.MAX_VALUE);
        remainingCalls = peers.size();
      }

      /**
       * That's the number of signatures we have if we have all of them.
       */
      int expectedSigs() {
        return expectedSigs(level);
      }

      int expectedSigs(int level) {
        if (level <= 1) {
          return 1;
        }
        return (int) Math.pow(2, level - 1);
      }

      /**
       * We start a level if we reached the time out or if we have all the signatures.
       */
      boolean hasStarted(BitSet toSend) {
        if (toSend.cardinality() > expectedSigs()) {
          throw new IllegalArgumentException();
        }
        return network.time > (level - 1) * timeoutPerLevelMs
            || toSend.cardinality() == expectedSigs();
      }

      void doCycle(BitSet toSend) {
        if (remainingCalls == 0 || !hasStarted(toSend)) {
          return;
        }

        List<GSFNode> dest = getRemainingPeers(toSend, 1);
        if (!dest.isEmpty()) {
          SendSigs ss = new SendSigs(toSend, this, verifiedSignatures.equals(waitedSigs));
          network.send(ss, GSFNode.this, dest.get(0));
        }
      }

      List<GSFNode> getRemainingPeers(BitSet toSend, int peersCt) {
        List<GSFNode> res = new ArrayList<>(peersCt);

        while (peersCt > 0 && remainingCalls > 0) {
          remainingCalls--;

          GSFNode p = peers.get(posInLevel++);
          if (!finishedPeers.contains(p)) {
            res.add(p);
            peersCt--;
          }
          if (posInLevel >= peers.size()) {
            posInLevel = 0;
          }
        }

        return res;
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
    void updateVerifiedSignatures(int level, BitSet sigs) {
      SFLevel sfl = levels.get(level);

      if (sigs.cardinality() > sfl.expectedSigs()) {
        throw new IllegalArgumentException("level=" + level + ", sigs.cardinality()="
            + sigs.cardinality() + ", sfl.expectedSigs()=" + sfl.expectedSigs());
      }

      if (sfl.verifiedSignatures.cardinality() > 0 && !sigs.intersects(sfl.verifiedSignatures)) {
        // If the new sigs stricly intersect with the previous one we can aggregate then.
        // Note that technically we could do much more, i.e. searching the history of
        // sigs we have that could intersect
        sigs.or(sfl.verifiedSignatures);
      }

      if (sigs.cardinality() > sfl.verifiedSignatures.cardinality()) {
        for (int i = sfl.level; i < levels.size(); i++) {
          // We have some new sigs. This is interesting for our level and all the
          //  levels above. So we reset the remainingCalls variable.
          levels.get(i).remainingCalls = levels.get(i).peers.size();
        }

        // We're going to update our common set only if we have more sigs.
        // It's a replacement, not a completion.
        sfl.verifiedSignatures.andNot(sfl.waitedSigs);
        sfl.verifiedSignatures.or(sigs);

        verifiedSignatures.andNot(sfl.waitedSigs);
        verifiedSignatures.or(sigs);

        if (acceleratedCallsCount > 0 && sigs.cardinality() == sfl.expectedSigs()) {
          // We completed this level. Does it finishes something?
          // If so, we're going to send immediately an update to 'acceleratedCallsCount' nodes
          // It makes the protocol faster for the branches that are complete.
          BitSet toSend = (BitSet) sigs.clone();
          for (int i = 0; i < sfl.level; i++) {
            toSend.or(levels.get(i).verifiedSignatures);
          }

          while (toSend.cardinality() == sfl.expectedSigs(sfl.level + 1)
              && sfl.level < levels.size() - 1) {
            sfl = levels.get(sfl.level + 1);
            SendSigs sendSigs =
                new SendSigs(toSend, sfl, sfl.verifiedSignatures.equals(sfl.waitedSigs));
            List<GSFNode> peers =
                sfl.getRemainingPeers(sfl.verifiedSignatures, acceleratedCallsCount);
            if (!peers.isEmpty()) {
              network.send(sendSigs, this, peers);
            }
            toSend.or(sfl.verifiedSignatures);
          }
        }
        if (doneAt == 0 && verifiedSignatures.cardinality() >= threshold) {
          doneAt = network.time;

          // todo: in a byzantine context we need to continue longer to send what we
          //  have to all nodes
          // done = true;
        }
      }
    }

    private List<GSFNode> randomSubset(BitSet nodes, int nodeCt) {
      List<GSFNode> res = new ArrayList<>(nodes.cardinality());
      for (int pos, cur = nodes.nextSetBit(0); cur >= 0; pos = cur + 1, cur =
          nodes.nextSetBit(pos)) {
        res.add(network.getNodeById(cur));
      }

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
    void onNewSig(GSFNode from, SendSigs ssigs) {
      SFLevel sfl = levels.get(ssigs.level);
      if (sfl.verifiedSignatures.cardinality() < ssigs.sigs.cardinality()) {
        toVerify.add(ssigs);
      }
      if (ssigs.levelFinished) {
        sfl.finishedPeers.add(from);
      }
    }

    public void checkSigs() {
      SendSigs best = null;
      boolean found = false;
      Iterator<SendSigs> it = toVerify.iterator();

      while (it.hasNext() && !found) {
        SendSigs cur = it.next();
        SFLevel l = levels.get(cur.level);
        if (cur.sigs.cardinality() > l.verifiedSignatures.cardinality()
            || !cur.sigs.intersects(l.verifiedSignatures)) {
          best = cur;
          if (cur.sigs.cardinality() == l.expectedSigs()
              || (!cur.sigs.intersects(l.verifiedSignatures) && cur.sigs.cardinality()
                  + l.verifiedSignatures.cardinality() == l.expectedSigs())) {
            found = true; // if it allows us to finish a level, we select it.
          }
        } else {
          it.remove();
        }
      }

      if (best != null) {
        toVerify.remove(best);
        final SendSigs tBest = best;
        network.registerTask(() -> GSFNode.this.updateVerifiedSignatures(tBest.level, tBest.sigs),
            network.time + pairingTime * 2, GSFNode.this);
      }
    }


    @Override
    public String toString() {
      return "GSFNode{" + "nodeId=" + nodeId + ", doneAt=" + doneAt + ", sigs="
          + verifiedSignatures.cardinality() + ", msgReceived=" + msgReceived + ", msgSent="
          + msgSent + ", KBytesSent=" + bytesSent / 1024 + ", KBytesReceived="
          + bytesReceived / 1024 + '}';
    }
  }

  void init() {
    for (int i = 0; i < nodeCount; i++) {
      final GSFNode n = new GSFNode();
      network.addNode(n);
      if (i < nodesDown) {
        // n.down = true;
      }
    }

    for (int setDown = 0; setDown < nodesDown;) {
      int down = network.rd.nextInt(nodeCount);
      Node n = network.allNodes.get(down);
      if (!n.down) {
        n.down = true;
        setDown++;
      }
    }

    for (GSFNode n : network.allNodes) {
      if (!n.down) {
        n.initLevel();
        network.registerPeriodicTask(n::doCycle, 1, periodDurationMs, n);
        network.registerConditionalTask(n::checkSigs, 1, pairingTime, n,
            () -> !n.toVerify.isEmpty(), () -> !n.done);
      }
    }
  }

  public static void sigsPerTime() {
    NetworkLatency.NetworkLatencyByDistance nl = new NetworkLatency.NetworkLatencyByDistance();
    int nodeCt = 32768 / 4;
    GSFSignature ps1 = new GSFSignature(nodeCt, .8, 3, 50, 10, 10, 0.2);
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

    long startAt = System.currentTimeMillis();
    StatsHelper.SimpleStats s;
    do {
      ps1.network.runMs(10);
      s = StatsHelper.getStatsOn(
          ps1.network.allNodes.stream().filter(n -> !n.down).collect(Collectors.toList()),
          n -> ((GSFNode) n).verifiedSignatures.cardinality());
      series1min.addLine(new Graph.ReportLine(ps1.network.time, s.min));
      series1max.addLine(new Graph.ReportLine(ps1.network.time, s.max));
      series1avg.addLine(new Graph.ReportLine(ps1.network.time, s.avg));
      if (ps1.network.time % 10000 == 0) {
        System.out.println(" " + s.avg);
      }
    } while (s.min < ps1.threshold);
    long endAt = System.currentTimeMillis();

    try {
      graph.save(new File("/tmp/graph.png"));
    } catch (IOException e) {
      System.err.println("Can't generate the graph: " + e.getMessage());
    }

    System.out
        .println("bytes sent: " + StatsHelper.getStatsOn(ps1.network.allNodes, Node::getBytesSent));
    System.out.println(
        "bytes rcvd: " + StatsHelper.getStatsOn(ps1.network.allNodes, Node::getBytesReceived));
    System.out
        .println("msg sent: " + StatsHelper.getStatsOn(ps1.network.allNodes, Node::getMsgSent));
    System.out
        .println("msg rcvd: " + StatsHelper.getStatsOn(ps1.network.allNodes, Node::getMsgReceived));
    System.out.println(
        "done at: " + StatsHelper.getStatsOn(ps1.network.allNodes, n -> ((GSFNode) n).doneAt));
    System.out.println("Simulation execution time: " + ((endAt - startAt) / 1000) + "s");
  }


  public static void main(String... args) {
    NetworkLatency nl = new NetworkLatency.NetworkLatencyByDistance();
    System.out.println("" + nl);

    sigsPerTime();
  }
}
