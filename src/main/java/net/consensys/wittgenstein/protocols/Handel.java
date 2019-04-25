package net.consensys.wittgenstein.protocols;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.json.ListNodeConverter;
import net.consensys.wittgenstein.core.messages.Message;
import net.consensys.wittgenstein.core.utils.MoreMath;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import net.consensys.wittgenstein.core.utils.BitSetUtils;
import net.consensys.wittgenstein.server.WParameters;
import net.consensys.wittgenstein.tools.NodeDrawer;
import java.io.File;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@SuppressWarnings("WeakerAccess")
public class Handel implements Protocol {
  private final HandelParameters params;
  private final Network<HNode> network = new Network<>();


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

    final int levelWaitTime;
    final int periodDurationMs;
    final int acceleratedCallsCount;

    final int nodesDown;
    final String nodeBuilderName;
    final String networkLatencyName;

    /**
     * Allows to test what happens when all the nodes are not starting at the same time. If the
     * value is different than 0, all the nodes are starting at a time uniformly distributed between
     * zero and 'desynchronizedStart'
     */
    final int desynchronizedStart;

    /**
     * A Byzantine scenario where all nodes starts
     */
    final boolean byzantineSuicide;
    final boolean hiddenByzantine;

    /**
     * parameters related to the window-ing technique - null if not set
     */
    public WindowParameters window;

    // Used for json / http server
    @SuppressWarnings("unused")
    public HandelParameters() {
      this.nodeCount = 32768 / 1024;
      this.threshold = (int) (nodeCount * (0.99));
      this.pairingTime = 3;
      this.levelWaitTime = 50;
      this.periodDurationMs = 10;
      this.acceleratedCallsCount = 10;
      this.nodesDown = 0;
      this.nodeBuilderName = null;
      this.networkLatencyName = null;
      this.desynchronizedStart = 0;
      this.byzantineSuicide = false;

      this.hiddenByzantine = false;

    }

    public HandelParameters(int nodeCount, int threshold, int pairingTime, int levelWaitTime,
        int periodDurationMs, int acceleratedCallsCount, int nodesDown, String nodeBuilderName,
        String networkLatencyName, int desynchronizedStart, boolean byzantineSuicide,
        boolean hiddenByzantine) {


      if (nodesDown >= nodeCount || nodesDown < 0 || threshold > nodeCount
          || (nodesDown + threshold > nodeCount)) {
        throw new IllegalArgumentException("nodeCount=" + nodeCount + ", threshold=" + threshold);
      }
      if (Integer.bitCount(nodeCount) != 1) {
        throw new IllegalArgumentException("We support only power of two nodes in this simulation");
      }

      if (byzantineSuicide && hiddenByzantine) {
        throw new IllegalArgumentException("Only one attack at a time");
      }

      this.nodeCount = nodeCount;
      this.threshold = threshold;
      this.pairingTime = pairingTime;
      this.levelWaitTime = levelWaitTime;
      this.periodDurationMs = periodDurationMs;
      this.acceleratedCallsCount = acceleratedCallsCount;
      this.nodesDown = nodesDown;
      this.nodeBuilderName = nodeBuilderName;
      this.networkLatencyName = networkLatencyName;
      this.desynchronizedStart = desynchronizedStart;
      this.byzantineSuicide = byzantineSuicide;
      this.hiddenByzantine = hiddenByzantine;

    }

    @Override
    public String toString() {
      return "HandelParameters{" + "nodeCount=" + nodeCount + ", threshold=" + threshold
          + ", pairingTime=" + pairingTime + ", levelWaitTime=" + levelWaitTime
          + ", periodDurationMs=" + periodDurationMs + ", acceleratedCallsCount="
          + acceleratedCallsCount + ", nodesDown=" + nodesDown + ", nodeBuilderName='"
          + nodeBuilderName + '\'' + ", networkLatencyName='" + networkLatencyName + '\''
          + ", desynchronizedStart=" + desynchronizedStart + ", byzantineSuicide="
          + byzantineSuicide + ", hiddenByzantine=" + hiddenByzantine + '}';

    }
  }

  @FunctionalInterface
  public interface CongestionWindow {
    int newSize(int currentSize, boolean correctVerification);
  }

  static class WindowParameters {
    public final static String FIXED = "FIXED";
    public final static String VARIABLE = "VARIABLE";
    public String type;
    // for fixed type
    public int size;
    // for variable type
    public int initial; // initial window size
    public int minimum; // minimum window size at all times
    public int maximum; // maximum window size at all times
    // what type "congestion" algorithm do we want to us
    public CongestionWindow congestion;
    public boolean moving; // is it a moving window or not -> moving sets the beginning of the window to the lowest-rank unverified signature

    public int newSize(int currentWindowSize, boolean correct) {
      int updatedSize = congestion.newSize(currentWindowSize, correct);
      if (updatedSize > maximum) {
        return maximum;
      } else if (updatedSize < minimum) {
        return minimum;
      }
      return updatedSize;
    }
  }

  static class CongestionLinear implements CongestionWindow {
    public int delta; // window is increased/decreased by delta

    public CongestionLinear(int delta) {
      this.delta = delta;
    }

    public int newSize(int curr, boolean correct) {
      if (correct) {
        return curr + delta;
      } else {
        return curr - delta;
      }
    }

    @Override
    public String toString() {
      return "Linear{" + "delta=" + delta + '}';
    }
  }

  static class CongestionExp implements CongestionWindow {
    public double increaseFactor;
    public double decreaseFactor;

    public CongestionExp(double increaseFactor, double decreaseFactor) {
      this.increaseFactor = increaseFactor;
      this.decreaseFactor = decreaseFactor;
    }

    public int newSize(int curr, boolean correct) {
      if (correct) {
        return (int) ((double) curr * increaseFactor);
      } else {
        return (int) ((double) curr * decreaseFactor);
      }
    }

    @Override
    public String toString() {
      return "CExp{" + "increase=" + increaseFactor + ", decrease=" + decreaseFactor + '}';
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
        + ", pairing=" + params.pairingTime + "ms, levelWaitTime=" + params.levelWaitTime
        + "ms, period=" + params.periodDurationMs + "ms, acceleratedCallsCount="
        + params.acceleratedCallsCount + ", dead nodes=" + params.nodesDown + ", builder="
        + params.nodeBuilderName;
  }

  static class SendSigs extends Message<HNode> {
    final int level;
    final BitSet sigs;
    final boolean levelFinished;
    final int size;
    final boolean badSig;

    public SendSigs(BitSet sigs, HNode.HLevel l) {
      this.sigs = (BitSet) sigs.clone();
      this.level = l.level;
      this.size = 1 + l.expectedSigs() / 8 + 96 * 2; // Size = level + bit field + the signatures included + our own sig
      this.levelFinished = l.incomingComplete();
      this.badSig = false;
      if (sigs.isEmpty() || sigs.cardinality() > l.size) {
        throw new IllegalStateException("bad level: " + l.level);
      }
    }

    private SendSigs(HNode from, HNode to) {
      this.level = to.level(from);
      this.sigs = to.levels.get(level).waitedSigs;
      this.levelFinished = false;
      this.size = 1;
      this.badSig = true;
    }


    @Override
    public int size() {
      return size;
    }

    @Override
    public void action(Network<HNode> network, HNode from, HNode to) {
      if (!to.suicidalAttackDone) {
        fillWithFalseSigs(network, to);
        to.suicidalAttackDone = true;
      }

      to.onNewSig(from, this);
    }

    public void fillWithFalseSigs(Network<HNode> network, HNode honest) {
      List<HNode> byz = network.allNodes.stream().filter(Node::isDown).collect(Collectors.toList());
      for (HNode b : byz) {
        SendSigs ss = new SendSigs(b, honest);
        honest.onNewSig(b, ss);
      }
    }
  }


  public class HNode extends Node {
    final int startAt;
    final List<HLevel> levels = new ArrayList<>();
    final int nodePairingTime = (int) (Math.max(1, params.pairingTime * speedRatio));
    final int[] receptionRanks = new int[params.nodeCount];
    final BitSet blacklist = new BitSet();

    boolean suicidalAttackDone;
    final HiddenByzantine hiddenByzantine;

    boolean done = false;
    int sigChecked = 0;
    int sigQueueSize = 0;

    HNode(int startAt, NodeBuilder nb) {
      super(network.rd, nb);
      this.startAt = startAt;
      this.suicidalAttackDone = !params.byzantineSuicide;
      this.hiddenByzantine = params.hiddenByzantine ? new HiddenByzantine() : null;
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
      return sigQueueSize != 0;
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

    public class HLevel {
      final int level;
      final int size;

      @JsonSerialize(converter = ListNodeConverter.class)
      final List<HNode> peers = new ArrayList<>(); // peers, sorted in emission order

      // The peers when we have all signatures for this level.
      final BitSet waitedSigs = new BitSet(); // 1 for the signatures we should get at this level

      final BitSet lastAggVerified = new BitSet();
      // The aggregate signatures verified in this level
      final BitSet totalIncoming = new BitSet();
      // The merge of the individual & the last agg verified
      final BitSet verifiedIndSignatures = new BitSet();
      // The individual signatures verified in this level

      final List<SigToVerify> toVerifyAgg = new ArrayList<>();
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
       * Window-related fields
       */
      int currWindowSize = 0; // what's the size of the window currently


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

        if (network.time >= (level - 1) * params.levelWaitTime) {
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

          if (!finishedPeers.get(p.nodeId) && !blacklist.get(p.nodeId)) {
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
        if (toVerifyAgg.size() == 0) {
          return null;
        }
        if (Handel.this.params.window != null) {
          return bestToVerifyWithWindow();
        }
        List<SigToVerify> curatedList = new ArrayList<>();
        int curSize = totalIncoming.cardinality();
        SigToVerify best = null;
        boolean removed = false;
        for (SigToVerify stv : toVerifyAgg) {
          int s = sizeIfIncluded(stv);
          if (!blacklist.get(stv.from) && s > curSize) {
            // only add signatures that can result in a better aggregate signature
            curatedList.add(stv);
            if (best == null || stv.rank < best.rank) {
              // take the signature with the highest rank in the reception list
              best = stv;
            }
          } else {
            removed = true;
          }
        }
        if (removed) {
          int oldSize = toVerifyAgg.size();
          toVerifyAgg.clear();
          toVerifyAgg.addAll(curatedList);
          int newSize = toVerifyAgg.size();
          sigQueueSize -= oldSize;
          sigQueueSize += newSize;
          if (sigQueueSize < 0) {
            throw new IllegalStateException("sigQueueSize=" + sigQueueSize);
          }
        }

        return best;
      }

      public SigToVerify bestToVerifyWithWindow() {
        WindowParameters window = Handel.this.params.window;
        switch (window.type) {
          case WindowParameters.FIXED:
            return bestToVerifyWithWindowFIXED();
          case WindowParameters.VARIABLE:
            return bestToVerifyWithWindowVARIABLE();
        }
        return null;
      }



      /**
       * This method uses a window that has a variable size depending on whether the node has
       * received invalid contributions or not. Within the window, it evaluates with a scoring
       * function. Outside it evaluates with the rank.
       * 
       * @return
       */
      public SigToVerify bestToVerifyWithWindowVARIABLE() {

        WindowParameters params = Handel.this.params.window;
        if (this.currWindowSize == 0) {
          this.currWindowSize = params.initial;
        }

        int windowIndex = 0;
        int windowSize = this.currWindowSize;
        if (params.moving) {
          // we set the window index to the rank of the first unverified signature
          windowIndex =
              toVerifyAgg.stream().min(Comparator.comparingInt(SigToVerify::getRank)).get().rank;
        }
        int curSignatureSize = totalIncoming.cardinality();
        SigToVerify bestOutside = null; // best signature outside the window - rank based decision
        SigToVerify bestInside = null; // best signature inside the window - ranking
        int bestScoreInside = 0; // score associated to the best sig. inside the window

        int removed = 0;
        List<SigToVerify> curatedList = new ArrayList<>();
        for (SigToVerify stv : toVerifyAgg) {
          int s = sizeIfIncluded(stv);
          if (!blacklist.get(stv.from) && s > curSignatureSize) {
            // only add signatures that can result in a better aggregate signature
            // select the high priority one from the low priority on
            curatedList.add(stv);
            if (stv.rank <= windowIndex + windowSize) {

              int score = evaluateSig(this, stv.sig);
              if (score > bestScoreInside) {
                bestScoreInside = score;
                bestInside = stv;
              }
            } else {
              if (bestOutside == null || bestOutside.rank > stv.rank) {
                bestOutside = stv;
              }
            }
          } else {
            removed++;
          }
        }

        if (removed > 0) {
          toVerifyAgg.addAll(curatedList);
          int oldSize = toVerifyAgg.size();
          toVerifyAgg.clear();
          toVerifyAgg.addAll(curatedList);
          int newSize = toVerifyAgg.size();
          sigQueueSize -= oldSize;
          sigQueueSize += newSize;
        }


        SigToVerify toVerify = null;
        if (bestInside != null) {
          toVerify = bestInside;
        } else if (bestOutside != null) {
          toVerify = bestOutside;
        } else {
          return null;
        }

        this.currWindowSize = params.newSize(this.currWindowSize, toVerify.badSig);
        return toVerify;
      }

      /**
       * This method uses a simple fixed window of given length in the window paramters. Within the
       * window, it evaluates randomly.
       * 
       * @return
       */
      public SigToVerify bestToVerifyWithWindowFIXED() {
        List<SigToVerify> lowPriority = new ArrayList<>();
        List<SigToVerify> highPriority = new ArrayList<>();
        int window = Handel.this.params.window.size;
        SigToVerify best = null;

        int curSize = totalIncoming.cardinality();
        boolean removed = false;


        for (SigToVerify stv : toVerifyAgg) {
          int s = sizeIfIncluded(stv);
          if (!blacklist.get(stv.from) && s > curSize) {
            // only add signatures that can result in a better aggregate signature
            // select the high priority one from the low priority one
            if (stv.rank <= window) {
              highPriority.add(stv);
            } else {
              lowPriority.add(stv);
              if (best == null || best.rank > stv.rank) {
                best = stv;
              }
            }
          } else {
            removed = true;
          }
        }
        if (removed) {
          int oldSize = toVerifyAgg.size();
          toVerifyAgg.clear();
          toVerifyAgg.addAll(highPriority);
          toVerifyAgg.addAll(lowPriority);
          int newSize = toVerifyAgg.size();
          sigQueueSize -= oldSize;
          sigQueueSize += newSize;
        }
        // take highest priority signatures randomly
        if (highPriority.size() > 0) {
          int idx = network.rd.nextInt(highPriority.size());
          return highPriority.get(idx);
        }
        if (lowPriority.size() > 0) {
          // take the lowest rank from the rest of the stream
          return best;
        }

        return null;
      }
    }

    /**
     * Evaluate the interest to verify a signature by setting a score The higher the score the more
     * interesting the signature is. 0 means the signature is not interesting and can be discarded.
     */
    private int evaluateSig(HLevel l, BitSet sig) {
      int newTotal = 0; // The number of signatures in our new best
      int addedSigs = 0; // The number of sigs we add with our new best compared to the existing one. Can be negative
      int combineCt = 0; // The number of sigs in our new best that come from combining it with individual sigs

      if (l.lastAggVerified.cardinality() >= l.expectedSigs()) {
        return 0;
      }

      BitSet withIndiv = (BitSet) l.verifiedIndSignatures.clone();
      withIndiv.or(sig);

      if (l.lastAggVerified.cardinality() == 0) {
        // the best is the new multi-sig combined with the ind. sigs
        newTotal = sig.cardinality();
        addedSigs = newTotal;
        combineCt = 0;
      } else {
        if (sig.intersects(l.lastAggVerified)) {
          // We can't merge, it's a replace
          newTotal = withIndiv.cardinality();
          addedSigs = newTotal - l.lastAggVerified.cardinality();
          combineCt = newTotal;
        } else {
          // We can merge our current best and the new ms. We also add individual
          //  signatures that we previously verified
          withIndiv.or(l.lastAggVerified);
          newTotal = withIndiv.cardinality();
          addedSigs = newTotal - l.lastAggVerified.cardinality();
          combineCt = newTotal;
        }
      }

      if (addedSigs <= 0) {
        if (sig.cardinality() == 1 && !sig.intersects(l.verifiedIndSignatures)) {
          return 1;
        }
        return 0;
      }

      if (newTotal == l.expectedSigs()) {
        // This completes a level! That's the best options for us. We give
        //  a greater value to the first levels/
        return 1000000 - l.level * 10;
      }

      // It adds value, but does not complete a level. We
      //  favorize the older level but take into account the number of sigs we receive as well.
      return 100000 - l.level * 100 + addedSigs;
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
      if (vs.badSig) {
        blacklist.set(vs.from);
        return;
      }

      HLevel vsl = levels.get(vs.level);
      if (!BitSetUtils.include(vsl.waitedSigs, vs.sig)) {
        throw new IllegalStateException("bad signature received");
      }


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
      if (network.time < startAt || blacklist.get(from.nodeId)) {
        return;
      }

      HLevel l = levels.get(ssigs.level);

      if (!BitSetUtils.include(l.waitedSigs, ssigs.sigs)) {
        throw new IllegalStateException("bad signatures received");
      }

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

      sigQueueSize++;
      l.toVerifyAgg.add(
          new SigToVerify(from.nodeId, l.level, receptionRanks[from.nodeId], cs, ssigs.badSig));
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

      SigToVerify best = byLevels.get(network.rd.nextInt(byLevels.size()));
      sigChecked++;

      if (hiddenByzantine != null && best.level == levels.size() - 1) {
        best = hiddenByzantine.attack(this, best);
      }

      final SigToVerify fBest = best;
      network.registerTask(() -> HNode.this.updateVerifiedSignatures(fBest),
          network.time + nodePairingTime, HNode.this);
    }
  }


  class HiddenByzantine {
    HNode firstByzantine = null;
    boolean noByzantinePeers = false;
    SigToVerify last = null;

    HNode firstByzantine(HNode t, HNode.HLevel l) {
      HNode best = null;
      int bestRank = Integer.MAX_VALUE;

      for (HNode p : l.peers) {
        if (p.isDown() && t.receptionRanks[p.nodeId] < bestRank) {
          bestRank = t.receptionRanks[p.nodeId];
          best = p;
          if (bestRank == 0) {
            return p;
          }
        }
      }
      return best; // can be null if this node has no byzantine peer.
    }

    /**
     * We're trying to flood the last level with valid but not really useful signatures.
     */
    SigToVerify attack(HNode target, SigToVerify currentBest) {
      if (noByzantinePeers) {
        return currentBest;
      }

      HNode.HLevel l = target.levels.get(currentBest.level);
      if (firstByzantine == null) {
        firstByzantine = firstByzantine(target, l);
        if (firstByzantine == null) {
          noByzantinePeers = true;
          return currentBest;
        }
      }

      if (last == currentBest) {
        // A previous attack finally worked. Good.
        last = null;
        return currentBest;
      }
      if (last != null) {
        if (l.toVerifyAgg.contains(last)) {
          // ok, we plugged our low quality sig but we're still in the list. The attack failed... this time.
          return currentBest;
        }
        // Our signature has been pruned.
        last = null;
      }

      // Ok, we're going to push a bad signature and check if we can trick the score function
      BitSet cur = (BitSet) l.totalIncoming.clone();
      boolean found = !cur.get(firstByzantine.nodeId);
      cur.set(firstByzantine.nodeId);

      if (found && cur.cardinality() >= currentBest.sig.cardinality()) {
        return currentBest;
      }

      for (int i = 0; !found && i < l.peers.size(); i++) {
        HNode p = l.peers.get(i);
        if (!cur.get(p.nodeId)) {
          cur.set(p.nodeId);
          found = true;
        }
      }

      if (!found) {
        return currentBest;
      }

      SigToVerify bad = new SigToVerify(firstByzantine.nodeId, l.level,
          target.receptionRanks[firstByzantine.nodeId], cur, false);
      l.toVerifyAgg.add(bad);
      target.sigQueueSize++;
      SigToVerify newBest = l.bestToVerify();
      if (newBest != bad) {
        last = bad;
      }
      return newBest;
    }
  }

  static class SigToVerify {
    final int from;
    final int level;

    public int getRank() {
      return rank;
    }

    final int rank;
    final BitSet sig;
    final boolean badSig;

    SigToVerify(int from, int level, int rank, BitSet sig, boolean badSig) {
      this.from = from;
      this.level = level;
      this.rank = rank;
      this.sig = sig;
      this.badSig = badSig;
    }
  }

  @Override
  public Handel copy() {
    return new Handel(params);
  }


  public void init() {
    NodeBuilder nb = RegistryNodeBuilders.singleton.getByName(params.nodeBuilderName);

    for (int i = 0; i < params.nodeCount; i++) {
      int startAt =
          params.desynchronizedStart == 0 ? 0 : network.rd.nextInt(params.desynchronizedStart);
      final HNode n = new HNode(startAt, nb);
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
        network.registerPeriodicTask(n::dissemination, n.startAt + 1, params.periodDurationMs, n);
        network.registerConditionalTask(n::checkSigs, n.startAt + 1, n.nodePairingTime, n,
            n::hasSigToVerify, () -> !n.done);
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


  class HNodeStatus implements NodeDrawer.NodeStatus {
    @Override
    public int getMax() {
      return params.nodeCount;
    }

    @Override
    public int getMin() {
      return 1;
    }

    @Override
    public int getVal(Node n) {
      return ((HNode) n).totalSigSize();
    }

    @Override
    public boolean isSpecial(Node n) {
      return n.extraLatency > 0;
    }
  }

  public static Predicate<Handel> newContIf() {
    return p -> {
      for (HNode n : p.network().liveNodes()) {
        if (n.doneAt == 0) {
          return true;
        }
      }
      return false;
    };
  }

  public static Handel newProtocol() {
    int nodeCt = 32768 / 8;
    double deadR = 0.10;
    double tsR = .85;

    String nb = RegistryNodeBuilders.name(true, false, 0.33);
    String nl = NetworkLatency.AwsRegionNetworkLatency.class.getSimpleName();

    int ts = (int) (tsR * nodeCt);
    int dead = (int) (deadR * nodeCt);
    HandelParameters params =
        new HandelParameters(nodeCt, ts, 4, 50, 20, 10, dead, nb, nl, 0, false, false);

    return new Handel(params);
  }

  public static void drawImgs() {
    Handel p = newProtocol();
    Predicate<Handel> contIf = newContIf();

    p.init();
    int freq = 10;
    try (NodeDrawer nd =
        new NodeDrawer(p.new HNodeStatus(), new File("/tmp/handel_anim.gif"), freq)) {
      int i = 0;
      do {
        p.network.runMs(freq);

        nd.drawNewState(p.network.time, TimeUnit.MILLISECONDS, p.network.liveNodes());
        if (i % 100 == 0) {
          nd.writeLastToGif(new File("/tmp/img_" + i + ".gif"));
        }
        i++;

      } while (contIf.test(p));
    }
  }

  public static void sigsPerTime() {
    Handel p = newProtocol();
    Predicate<Handel> contIf = newContIf();

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

    ppt.run(contIf);
  }

  public static void main(String... args) {
    sigsPerTime();
  }
}
