package net.consensys.wittgenstein.protocols;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.json.ListNodeConverter;
import net.consensys.wittgenstein.core.messages.Message;
import net.consensys.wittgenstein.core.utils.MoreMath;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import net.consensys.wittgenstein.server.WParameters;
import org.apache.tomcat.jni.Registry;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

@SuppressWarnings("WeakerAccess")
public class Handel implements Protocol {
  private final HandelParameters params;
  private final Network<HNode> network = new Network<>();


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
      this.nodeCount = 32768 / 1024;
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
      if (Integer.bitCount(nodeCount) != 1) {
        throw new IllegalArgumentException("We support only power of two nodes in this simulation");
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
        .setNetworkLatency(RegistryNetworkLatencies.singleton.getByName(params.networkLatencyName));
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
      if (sigs.isEmpty() || sigs.cardinality() > l.size) {
        throw new IllegalStateException("bad level: " + l.level);
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
    final int[] receptionRanks = new int[params.nodeCount];


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

    public int level(HNode dest) {
      for (int i = levels.size() - 1; i >= 0; i--) {
        if (levels.get(i).waitedSigs.get(dest.nodeId)) {
          return i;
        }
      }
      throw new IllegalStateException();
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
      final int size;

      @JsonSerialize(converter = ListNodeConverter.class)
      final List<HNode> peers = new ArrayList<>(); // peers, sorted in emission order

      // The peers when we have all signatures for this level.
      final BitSet waitedSigs = new BitSet(); // 1 for the signatures we should have at this level

      final BitSet lastAggVerified = new BitSet();
      // The aggregate signatures verified in this level
      final BitSet totalIncoming = new BitSet();
      // The merge of the individual & the last agg verified
      final BitSet verifiedIndSignatures = new BitSet();
      // The individual signatures verified in this level

      final ArrayList<SigToVerify> toVerifyAgg = new ArrayList<>();
      final BitSet toVerifyInd = new BitSet(); // The individual signatures received

      public BitSet finishedPeers = new BitSet();
      // The list of peers who told us they had finished this level.

      final BitSet totalOutgoing = new BitSet(); // The signatures we're sending for this level
      public boolean outgoingFinished = false;
      // all our peers are complete: no need to send anything for this level

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
        size = 0;
        outgoingFinished = true;
        lastAggVerified.set(nodeId);
        verifiedIndSignatures.set(nodeId);
        totalIncoming.set(nodeId);
      }

      /**
       * Build a level on top of the previous one.
       */
      HLevel(HLevel previousLevel, BitSet allPreviousNodes) {
        level = previousLevel.level + 1;

        // Signatures needed to finish the current level are:
        //  sigs of the previous level + peers of the previous level.
        //  If we have all this we have finished this level
        waitedSigs.or(allSigsAtLevel(this.level));
        waitedSigs.andNot(allPreviousNodes);
        totalOutgoing.set(nodeId);
        size = waitedSigs.cardinality();
      }

      /**
       * That's the number of signatures we have if we have all of them. It's also the number of
       * signatures we're going to send.
       */
      int expectedSigs() {
        return size;
      }

      /**
       * We start a level if we reached the time out or if we have all the signatures.
       */
      boolean isOpen() {
        if (outgoingFinished) {
          return false;
        }

        if (network.time >= (level - 1) * params.timeoutPerLevelMs) {
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

      void buildEmissionList(ArrayList<HNode>[][][] emissions) {
        assert peers.isEmpty();
        for (ArrayList<HNode> ranks : emissions[nodeId][level]) {
          if (ranks != null && !ranks.isEmpty()) {
            if (ranks.size() > 1) {
              Collections.shuffle(ranks, network.rd);
            }
            peers.addAll(ranks);
          }
        }
      }

      public boolean incomingComplete() {
        return waitedSigs.equals(totalIncoming);
      }

      public boolean outgoingComplete() {
        return totalOutgoing.cardinality() == size;
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
    void updateVerifiedSignatures(SigToVerify vs) {
      HLevel vsl = levels.get(vs.level);

      vsl.toVerifyInd.set(vs.from, false);
      vsl.toVerifyAgg.remove(vs);

      vsl.verifiedIndSignatures.set(vs.from);

      boolean improved = false;
      if (!vsl.totalIncoming.get(vs.from)) {
        vsl.totalIncoming.set(vs.from);
        improved = true;
      }

      BitSet all = (BitSet) vs.sig.clone();
      all.or(vsl.verifiedIndSignatures);
      if (all.cardinality() > vsl.verifiedIndSignatures.cardinality()) {
        improved = true;

        if (vsl.lastAggVerified.intersects(vs.sig)) {
          vsl.lastAggVerified.clear();
        }
        vsl.lastAggVerified.or(vs.sig);

        vsl.totalIncoming.clear();
        vsl.totalIncoming.or(vsl.lastAggVerified);
        vsl.totalIncoming.or(vsl.verifiedIndSignatures);
      }

      if (!improved) {
        return;
      }

      boolean justCompleted = vsl.incomingComplete();

      BitSet cur = new BitSet();
      for (HLevel l : levels) {
        if (l.level > vsl.level) {
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

      l.toVerifyAgg.add(new SigToVerify(from.nodeId, l.level, receptionRanks[from.nodeId], cs));
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

  @Override
  public Handel copy() {
    return new Handel(params);
  }

  public void init() {
    NodeBuilder nb = RegistryNodeBuilders.singleton.getByName(params.nodeBuilderName);

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

    List<HNode> an = new ArrayList<>(network.allNodes);
    int nLevel = MoreMath.log2(params.nodeCount);

    @SuppressWarnings("unchecked")
    ArrayList<HNode>[][][] emissions =
        new ArrayList[params.nodeCount][nLevel + 1][params.nodeCount];

    for (HNode n : network.allNodes) {
      Collections.shuffle(an, network.rd);
      int[] pos = new int[nLevel + 1];
      for (HNode sentBy : an) {
        if (sentBy != n) {
          int dl = n.level(sentBy);
          n.receptionRanks[sentBy.nodeId] = pos[dl];
          if (!sentBy.isDown()) {
            // No need to build the emission list of the dead nodes
            if (emissions[sentBy.nodeId][dl][pos[dl]] == null) {
              emissions[sentBy.nodeId][dl][pos[dl]] = new ArrayList<>();
            }
            emissions[sentBy.nodeId][dl][pos[dl]].add(n);
          }
          pos[dl]++;
        }
      }
    }

    for (HNode n : network.allNodes) {
      if (!n.isDown()) {
        for (HNode.HLevel l : n.levels) {
          l.buildEmissionList(emissions);
        }
      }
    }
  }

  @Override
  public Network<HNode> network() {
    return network;
  }

  public static void sigsPerTime() {
    int nodeCt = 32768 / 16;

    String nb = RegistryNodeBuilders.name(true, false, 0);
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
      System.out.println("min/avg/max speedRatio=" + ss.min + "/" + ss.avg + "/" + ss.max);

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
