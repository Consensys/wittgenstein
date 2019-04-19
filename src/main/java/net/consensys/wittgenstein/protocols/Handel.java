package net.consensys.wittgenstein.protocols;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.json.ListNodeConverter;
import net.consensys.wittgenstein.core.messages.Message;
import net.consensys.wittgenstein.core.utils.MoreMath;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import net.consensys.wittgenstein.core.utils.Utils;
import net.consensys.wittgenstein.server.WParameters;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

@SuppressWarnings("WeakerAccess")
public class Handel implements Protocol {
  private final HandelParameters params;
  private final Network<HNode> network = new Network<>();

  private HNode[][][] receptions;
  private int[][] receptionRanks;

  @SuppressWarnings("WeakerAccess")
  public static class HandelParameters extends WParameters {
    /**
     * The number of nodes in the network
     */
    final int nodeCount;

    /**
     * The number of signatures to reach to finish the protocol.
     */
    final int threshold;

    /**
     * The minimum time it takes to do a pairing for a node.
     */
    final int pairingTime;

    final int timeoutPerLevelMs;
    final int periodDurationMs;
    final int acceleratedCallsCount;

    final int nodesDown;
    final String nodeBuilderName;
    final String networkLatencyName;

    // Used for json / http server
    @SuppressWarnings("unused")
    public HandelParameters() {
      this.nodeCount = 32768 / 1024;;
      this.threshold = (int) (nodeCount * (0.99));
      this.pairingTime = 3;
      this.timeoutPerLevelMs = 50;
      this.periodDurationMs = 10;
      this.acceleratedCallsCount = 10;
      this.nodesDown = 0;
      this.nodeBuilderName = null;
      this.networkLatencyName = null;
    }

    public HandelParameters(int nodeCount, int threshold, int pairingTime, int timeoutPerLevelMs,
        int periodDurationMs, int acceleratedCallsCount, int nodesDown, String nodeBuilderName,
        String networkLatencyName) {
      if (nodesDown >= nodeCount || nodesDown < 0 || threshold > nodeCount
          || (nodesDown + threshold > nodeCount)) {
        throw new IllegalArgumentException("nodeCount=" + nodeCount + ", threshold=" + threshold);
      }
      this.nodeCount = nodeCount;
      this.threshold = threshold;
      this.pairingTime = pairingTime;
      this.timeoutPerLevelMs = timeoutPerLevelMs;
      this.periodDurationMs = periodDurationMs;
      this.acceleratedCallsCount = acceleratedCallsCount;
      this.nodesDown = nodesDown;
      this.nodeBuilderName = nodeBuilderName;
      this.networkLatencyName = networkLatencyName;
    }

    public HandelParameters(int nodeCount, double ratioThreshold, int pairingTime,
        int timeoutPerLevelMs, int periodDurationMs, int acceleratedCallsCount,
        double ratioNodesDown, String nodeBuilderName, String networkLatencyName) {
      this(nodeCount, (int) (ratioThreshold * nodeCount), pairingTime, timeoutPerLevelMs,
          periodDurationMs, acceleratedCallsCount, (int) (ratioNodesDown * nodeCount),
          nodeBuilderName, networkLatencyName);
    }
  }

  public Handel(HandelParameters params) {
    this.params = params;
    this.network
        .setNetworkLatency(new RegistryNetworkLatencies().getByName(params.networkLatencyName));
  }


  @Override
  public String toString() {
    return "Handel, " + "nodes=" + params.nodeCount + ", threshold=" + params.threshold
        + ", pairing=" + params.pairingTime + "ms, level timeout=" + params.timeoutPerLevelMs
        + "ms, period=" + params.periodDurationMs + "ms, acceleratedCallsCount="
        + params.acceleratedCallsCount + ", dead nodes=" + params.nodesDown + ", network="
        + network.networkLatency.getClass().getSimpleName();
  }

  static class SendSigs extends Message<HNode> {
    final int level;
    final BitSet sigs;
    final boolean levelFinished;
    final int size;

    public SendSigs(BitSet sigs, HNode.HLevel l) {
      this.sigs = (BitSet) sigs.clone();
      this.level = l.level;
      this.size = 1 + l.expectedSigs() / 8 + 96; // Size = level + bit field + the signatures included + our own sig
      this.levelFinished = l.incomingComplete();
      if (sigs.isEmpty() || sigs.cardinality() > l.waitedSigs.cardinality()) {
        throw new IllegalStateException("bad level");
      }
    }

    @Override
    public int size() {
      return size;
    }

    @Override
    public void action(Network<HNode> network, HNode from, HNode to) {
      to.onNewSig(from, this);
    }
  }


  public class HNode extends Node {
    final List<HLevel> levels = new ArrayList<>();
    final int nodePairingTime = (int) (params.pairingTime * speedRatio);

    boolean done = false;
    int sigChecked = 0;
    int sigQueueSize = 0;

    HNode(NodeBuilder nb) {
      super(network.rd, nb);
    }

    public void initLevel() {
      int roundedPow2NodeCount = MoreMath.roundPow2(params.nodeCount);
      BitSet allPreviousNodes = new BitSet(params.nodeCount);
      HNode.HLevel last = new HNode.HLevel();
      levels.add(last);
      for (int l = 1; Math.pow(2, l) <= roundedPow2NodeCount; l++) {
        allPreviousNodes.or(last.waitedSigs);
        last = new HNode.HLevel(last, allPreviousNodes);
        levels.add(last);
      }
    }

    void dissemination() {
      for (HLevel sfl : levels) {
        sfl.doCycle();
      }
    }

    boolean hasSigToVerify() {
      for (HLevel l : levels) {
        if (l.hasSigToVerify()) {
          return true;
        }
      }
      return false;
    }


    int totalSigSize() {
      HLevel last = levels.get(levels.size() - 1);
      return last.totalOutgoing.cardinality() + last.totalIncoming.cardinality();
    }

    /**
     * At a given level, we send the signatures received from the previous level and we expect the
     * equivalent from our peers of this level.<br/>
     * If nodeId == 0<br/>
     * l0: 0 => send to 1 <br/>
     * l1: 0 1 => send to 2 3 <br/>
     * l2: 0 1 2 3 => send to 4 5 6 7 <br/>
     * l3: 0 1 2 3 4 5 6 7 => send to 8 9 10 11 12 13 14 15 <br/>
     */
    public class HLevel {
      final int level;
      @JsonSerialize(converter = ListNodeConverter.class)
      final List<HNode> peers = new ArrayList<>();

      // The peers when we have all signatures for this level.
      final BitSet waitedSigs; // 1 for the signatures we should have at this level

      final BitSet lastAggVerified = new BitSet(); // The aggregate signatures verified in this level
      final BitSet totalIncoming = new BitSet(); // The merge of the individual & the last agg verified
      final BitSet verifiedIndSignatures = new BitSet(); // The individual signatures verified in this level

      final ArrayList<SigToVerify> toVerifyAgg = new ArrayList<>();
      final BitSet toVerifyInd = new BitSet(); // The individual signatures received

      public BitSet finishedPeers = new BitSet();

      final BitSet totalOutgoing = new BitSet(); // The signatures we're sending for this level
      public boolean outgoingFinished = false; // all our peers are complete

      /**
       * We're going to contact all nodes, one after the other. That's our position in the peers'
       * list.
       */
      int posInLevel = 0;


      /**
       * Build a level 0 object. At level 0 need (and have) only our own signature. We have only one
       * peer to contact.
       */
      HLevel() {
        level = 0;
        outgoingFinished = true;
        waitedSigs = new BitSet();
        waitedSigs.set(nodeId);
        lastAggVerified.set(nodeId);
        totalIncoming.set(nodeId);
        verifiedIndSignatures.set(nodeId);
      }

      /**
       * Build a level on top of the previous one.
       */
      HLevel(HLevel previousLevel, BitSet allPreviousNodes) {
        this.level = previousLevel.level + 1;

        // Signatures needed to finish the current level are:
        //  sigs of the previous level + peers of the previous level.
        //  If we have all this we have finished this level
        waitedSigs = allSigsAtLevel(this.level);
        waitedSigs.andNot(allPreviousNodes);
        totalOutgoing.set(nodeId);
      }

      /**
       * That's the number of signatures we have if we have all of them. It's also the number of
       * signatures we're going to send.
       */
      int expectedSigs() {
        return waitedSigs.cardinality();
      }

      /**
       * We start a level if we reached the time out or if we have all the signatures.
       */
      boolean isOpen() {
        if (outgoingFinished) {
          return false;
        }

        if (network.time >= (level) * params.timeoutPerLevelMs) {
          return true;
        }

        if (outgoingComplete()) {
          return true;
        }

        return false;
      }

      void doCycle() {
        if (!isOpen()) {
          return;
        }

        List<HNode> dest = getRemainingPeers(1);
        if (!dest.isEmpty()) {
          SendSigs ss = new SendSigs(totalOutgoing, this);
          network.send(ss, HNode.this, dest.get(0));
        }
      }

      List<HNode> getRemainingPeers(int peersCt) {
        List<HNode> res = new ArrayList<>(peersCt);

        int start = posInLevel;
        while (peersCt > 0 && !outgoingFinished) {

          HNode p = peers.get(posInLevel++);
          if (posInLevel >= peers.size()) {
            posInLevel = 0;
          }

          if (!finishedPeers.get(p.nodeId)) {
            res.add(p);
            peersCt--;
          } else {
            if (posInLevel == start) {
              outgoingFinished = true;
            }
          }
        }

        return res;
      }

      void buildEmissionList() {
        assert peers.isEmpty();
        for (int pi = 0; pi < waitedSigs.cardinality(); pi++) {
          for (int ni = 0; ni < params.nodeCount; ni++) {
            if (receptions[ni][level][pi] == HNode.this) { // This node 'ni' puts us at rank 'pi'
              HNode dest = network.getNodeById(ni);
              assert waitedSigs.get(dest.nodeId);
              peers.add(dest);
              assert receptionRanks[nodeId][dest.nodeId] == 0;
              receptionRanks[nodeId][dest.nodeId] = pi;
            }
          }
        }
      }

      public boolean incomingComplete() {
        return waitedSigs.equals(totalIncoming);
      }

      public boolean outgoingComplete() {
        return totalOutgoing.cardinality() == waitedSigs.cardinality();
      }


      int sizeIfIncluded(SigToVerify sig) {
        BitSet c = (BitSet) sig.sig.clone();
        c.or(verifiedIndSignatures);
        return c.cardinality();
      }

      public SigToVerify bestToVerify() {
        List<SigToVerify> curatedList = new ArrayList<>();
        int curSize = totalIncoming.cardinality();
        SigToVerify best = null;
        boolean removed = false;
        for (SigToVerify stv : toVerifyAgg) {
          int s = sizeIfIncluded(stv);
          if (s > curSize) {
            curatedList.add(stv);
            if (best == null || stv.rank < best.rank) {
              best = stv;
            }
          } else {
            removed = true;
          }
        }
        if (removed) {
          toVerifyAgg.clear();
          toVerifyAgg.addAll(curatedList);
        }

        return best;
      }

      public boolean hasSigToVerify() {
        return !toVerifyAgg.isEmpty() || !toVerifyInd.isEmpty();
      }
    }

    /**
     * @return all the signatures you should have when this round is finished.
     */
    BitSet allSigsAtLevel(int round) {
      if (round < 1) {
        throw new IllegalArgumentException("round=" + round);
      }
      BitSet res = new BitSet(params.nodeCount);
      int cMask = (1 << round) - 1;
      int start = (cMask | nodeId) ^ cMask;
      int end = nodeId | cMask;
      end = Math.min(end, params.nodeCount - 1);
      res.set(start, end + 1);
      res.set(nodeId, false);

      return res;
    }


    /**
     * If the state has changed we send a message to all. If we're done, we update all our peers: it
     * will be our last message.
     */
    void updateVerifiedSignatures(SigToVerify sigs) {
      HLevel sfl = levels.get(sigs.level);

      sfl.toVerifyInd.set(sigs.from, false);
      sfl.toVerifyAgg.remove(sigs);

      sfl.verifiedIndSignatures.set(sigs.from);

      boolean improved = false;
      if (!sfl.totalIncoming.get(sigs.from)) {
        sfl.totalIncoming.or(sfl.verifiedIndSignatures);
        improved = true;
      }

      BitSet all = (BitSet) sigs.sig.clone();
      all.or(sfl.verifiedIndSignatures);
      if (all.cardinality() > sfl.verifiedIndSignatures.cardinality()) {
        improved = true;

        if (sfl.lastAggVerified.intersects(sigs.sig)) {
          sfl.lastAggVerified.clear();
        }
        sfl.lastAggVerified.or(sigs.sig);

        sfl.totalIncoming.clear();
        sfl.totalIncoming.or(sfl.lastAggVerified);
        sfl.totalIncoming.or(sfl.verifiedIndSignatures);

      }

      if (!improved) {
        return;
      }

      boolean justCompleted = sfl.incomingComplete();

      BitSet cur = new BitSet();
      for (HLevel l : levels) {
        if (l.level > sfl.level) {
          l.totalOutgoing.clear();
          l.totalOutgoing.or(cur);
          if (justCompleted && params.acceleratedCallsCount > 0 && !l.outgoingFinished
              && l.outgoingComplete()) {
            List<HNode> peers = l.getRemainingPeers(params.acceleratedCallsCount);
            SendSigs sendSigs = new SendSigs(l.totalOutgoing, l);
            network.send(sendSigs, this, peers);
          }
        }
        cur.or(l.totalIncoming);
      }

      if (doneAt == 0 && cur.cardinality() >= params.threshold) {
        doneAt = network.time;
      }
    }

    /**
     * Nothing much to do when we receive a sig set: we just add it to our toVerify list.
     */
    void onNewSig(HNode from, SendSigs ssigs) {
      HLevel l = levels.get(ssigs.level);

      BitSet cs = (BitSet) ssigs.sigs.clone();
      cs.and(l.waitedSigs);
      if (!cs.equals(ssigs.sigs) || ssigs.sigs.isEmpty()) {
        throw new IllegalStateException("bad message");
      }

      if (ssigs.levelFinished) {
        l.finishedPeers.set(from.nodeId);
      }

      if (!l.verifiedIndSignatures.get(from.nodeId)) {
        l.toVerifyInd.set(from.nodeId);
      }

      l.toVerifyAgg
          .add(new SigToVerify(from.nodeId, l.level, receptionRanks[nodeId][from.nodeId], cs));
    }

    void checkSigs() {
      ArrayList<SigToVerify> byLevels = new ArrayList<>();

      for (HLevel l : levels) {
        SigToVerify ss = l.bestToVerify();
        if (ss != null) {
          byLevels.add(ss);
        }
      }

      if (byLevels.isEmpty()) {
        return;
      }

      final SigToVerify best = byLevels.get(network.rd.nextInt(byLevels.size()));
      sigChecked++;
      network.registerTask(() -> HNode.this.updateVerifiedSignatures(best),
          network.time + nodePairingTime, HNode.this);
    }
  }

  static class SigToVerify {
    final int from;
    final int level;
    final int rank;
    final BitSet sig;

    SigToVerify(int from, int level, int rank, BitSet sig) {
      this.from = from;
      this.level = level;
      this.rank = rank;
      this.sig = sig;
    }
  }


  private HNode[] randomSubset(BitSet nodes) {
    HNode[] res = new HNode[nodes.cardinality()];
    int target = 0;
    for (int pos, cur = nodes.nextSetBit(0); cur >= 0; pos = cur + 1, cur = nodes.nextSetBit(pos)) {
      res[target++] = network.getNodeById(cur);
    }

    Utils.shuffle(res, network.rd);
    return res;
  }


  @Override
  public Handel copy() {
    return new Handel(params);
  }

  public void init() {
    NodeBuilder nb = new RegistryNodeBuilders().getByName(params.nodeBuilderName);

    for (int i = 0; i < params.nodeCount; i++) {
      final HNode n = new HNode(nb);
      network.addNode(n);
    }

    for (int setDown = 0; setDown < params.nodesDown;) {
      int down = network.rd.nextInt(params.nodeCount);
      Node n = network.allNodes.get(down);
      if (!n.isDown() && down != 1) {
        // We keep the node 1 up to help on debugging
        n.stop();
        setDown++;
      }
    }

    for (HNode n : network.allNodes) {
      n.initLevel();
      if (!n.isDown()) {
        network.registerPeriodicTask(n::dissemination, 1, params.periodDurationMs, n);
        network.registerConditionalTask(n::checkSigs, 1, n.nodePairingTime, n, n::hasSigToVerify,
            () -> !n.done);
      }
    }

    int nLevel = MoreMath.log2(params.nodeCount);
    receptions = new HNode[params.nodeCount][nLevel + 1][0];
    receptionRanks = new int[params.nodeCount][params.nodeCount];
    for (HNode n : network.allNodes) {
      for (HNode.HLevel l : n.levels) {
        receptions[n.nodeId][l.level] = randomSubset(l.waitedSigs);
      }
    }

    for (HNode n : network.allNodes) {
      for (HNode.HLevel l : n.levels) {
        l.buildEmissionList();
      }
    }
  }

  @Override
  public Network<HNode> network() {
    return network;
  }

  /*
  round=0, GSFSignature, nodes=2048, threshold=1536, pairing=4ms, level timeout=50ms, period=20ms, acceleratedCallsCount=10, dead nodes=409, network=AwsRegionNetworkLatency
  min/avg/max speedRatio (GeneralizedParetoDistributionSpeed, max=3.0, ξ=1.0, μ=0.2, σ=0.4)=1/1/1
  min/avg/max sigChecked=111/134/152
  min/avg/max queueSize=0/6/20
  bytes sent: min: 22016, max:25455, avg:23582
  bytes rcvd: min: 12612, max:21185, avg:16778
  msg sent: min: 176, max:211, avg:192
  msg rcvd: min: 108, max:174, avg:138
  done at: min: 577, max:892, avg:702
  Simulation execution time: 2s
  Number of nodes that are down: 409
  Total Number of peers 2048
  
  round=0, Handel, nodes=2048, threshold=1536, pairing=4ms, level timeout=50ms, period=20ms, acceleratedCallsCount=10, dead nodes=0, network=AwsRegionNetworkLatency
  min/avg/max speedRatio (GeneralizedParetoDistributionSpeed, max=3.0, ξ=1.0, μ=0.2, σ=0.4)=1/1/1
  min/avg/max sigChecked=17/21/28
  min/avg/max queueSize=0/0/0
  bytes sent: min: 25047, max:35659, avg:31932
  bytes rcvd: min: 19419, max:31700, avg:25352
  msg sent: min: 210, max:300, avg:266
  msg rcvd: min: 177, max:278, avg:220
  done at: min: 287, max:394, avg:318
  Simulation execution time: 2s
  Number of nodes that are down: 0
  Total Number of peers 2048
  
  
  round=0, Handel, nodes=2048, threshold=1536, pairing=4ms, level timeout=50ms, period=20ms, acceleratedCallsCount=10, dead nodes=409, network=AwsRegionNetworkLatency
  min/avg/max speedRatio (GeneralizedParetoDistributionSpeed, max=3.0, ξ=1.0, μ=0.2, σ=0.4)=1/1/1
  min/avg/max sigChecked=33/49/71
  min/avg/max queueSize=0/0/0
  bytes sent: min: 28357, max:36806, avg:32442
  bytes rcvd: min: 15625, max:29556, avg:21122
  msg sent: min: 246, max:333, avg:288
  msg rcvd: min: 133, max:270, avg:187
  done at: min: 588, max:860, avg:695
  Simulation execution time: 2s
  Number of nodes that are down: 409
  Total Number of peers 2048
  
   */

  public static void sigsPerTime() {
    int nodeCt = 32768 / 16;

    final Node.SpeedModel sm = new Node.ParetoSpeed(1, 0.2, 0.4, 3);
    String nb = RegistryNodeBuilders.AWS_SITE;
    String nl = NetworkLatency.AwsRegionNetworkLatency.class.getSimpleName();

    int ts = (int) (.75 * nodeCt);
    int dead = (int) (.20 * nodeCt);
    HandelParameters params = new HandelParameters(nodeCt, ts, 4, 50, 20, 10, dead, nb, nl);
    Handel p = new Handel(params);

    StatsHelper.StatsGetter sg = new StatsHelper.StatsGetter() {
      final List<String> fields = new StatsHelper.SimpleStats(0, 0, 0).fields();

      @Override
      public List<String> fields() {
        return fields;
      }

      @Override
      public StatsHelper.Stat get(List<? extends Node> liveNodes) {
        return StatsHelper.getStatsOn(liveNodes, n -> ((HNode) n).totalSigSize());
      }
    };

    ProgressPerTime.OnSingleRunEnd cb = p12 -> {
      StatsHelper.SimpleStats ss =
          StatsHelper.getStatsOn(p12.network().liveNodes(), n -> (int) ((HNode) n).speedRatio);
      System.out
          .println("min/avg/max speedRatio (" + sm + ")=" + ss.min + "/" + ss.avg + "/" + ss.max);

      ss = StatsHelper.getStatsOn(p12.network().liveNodes(), n -> ((HNode) n).sigChecked);
      System.out.println("min/avg/max sigChecked=" + ss.min + "/" + ss.avg + "/" + ss.max);

      ss = StatsHelper.getStatsOn(p12.network().liveNodes(),
          n -> ((HNode) n).sigQueueSize / ((HNode) n).sigChecked);
      System.out.println("min/avg/max queueSize=" + ss.min + "/" + ss.avg + "/" + ss.max);
    };

    ProgressPerTime ppt =
        new ProgressPerTime(p, "", "number of signatures", sg, 1, cb, 10, TimeUnit.MILLISECONDS);

    Predicate<Protocol> contIf = p1 -> {
      for (Node n : p1.network().allNodes) {
        HNode gn = (HNode) n;
        // All up nodes must have reached the threshold, so if one live
        //  node has not reached it we continue
        if (!n.isDown() && gn.totalSigSize() < p.params.threshold) {
          return true;
        }
      }
      return false;
    };

    ppt.run(contIf);
  }

  public static void main(String... args) {
    sigsPerTime();
  }
}
