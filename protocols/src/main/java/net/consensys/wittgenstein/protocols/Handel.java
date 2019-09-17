package net.consensys.wittgenstein.protocols;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.*;
import java.util.function.Predicate;
import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.json.ListNodeConverter;
import net.consensys.wittgenstein.core.messages.Message;
import net.consensys.wittgenstein.core.utils.BitSetUtils;
import net.consensys.wittgenstein.core.utils.MoreMath;
import net.consensys.wittgenstein.tools.NodeDrawer;

@SuppressWarnings("WeakerAccess")
public class Handel implements Protocol {
  private final HandelParameters params;
  private final Network<HNode> network = new Network<>();

  public static class HandelParameters extends WParameters {
    /** The number of nodes in the network */
    final int nodeCount;

    /** The number of signatures to reach to finish the protocol. */
    final int threshold;

    /** The minimum time it takes to do a pairing for a node. */
    final int pairingTime;

    int levelWaitTime;
    final int periodDurationMs;
    int acceleratedCallsCount;

    final int nodesDown;
    final BitSet badNodes;

    final String nodeBuilderName;
    final String networkLatencyName;

    /**
     * Allows to test what happens when all the nodes are not starting at the same time. If the
     * value is different than 0, all the nodes are starting at a time uniformly distributed between
     * zero and 'desynchronizedStart'
     */
    int desynchronizedStart;

    /**
     * A Byzantine scenario where byzantine nodes sends invalid signatures. The strategy is: if an
     * honest node can verify a signature of another node, the adversary checks if there is a
     * byzantine node with a better rank. If so it generates an invalid signature from this node.
     * This signature will be checked instead of the honest one.
     */
    final boolean byzantineSuicide;

    final boolean hiddenByzantine;

    public String shuffle;
    static final String SHUFFLE_SQUARE = "SQUARE";
    static final String SHUFFLE_XOR = "XOR";

    /** parameters related to the window-ing technique - null if not set */
    public WindowParameters window;

    final String bestLevelFunction;
    static final String BESTLEVEL_RANDOM = "RANDOM";
    static final String BESTLEVEL_SCORE = "SCORE";

    // Used for json / http server
    @SuppressWarnings("unused")
    public HandelParameters() {
      this.bestLevelFunction = BESTLEVEL_RANDOM;
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
      this.shuffle = SHUFFLE_XOR;
      this.badNodes = null;
      this.window = new WindowParameters();
    }

    public HandelParameters(
        int nodeCount,
        int threshold,
        int pairingTime,
        int levelWaitTime,
        int periodDurationMs,
        int acceleratedCallsCount,
        int nodesDown,
        String nodeBuilderName,
        String networkLatencyName,
        int desynchronizedStart,
        boolean byzantineSuicide,
        boolean hiddenByzantine,
        String bestLevelFunction,
        BitSet badNodes) {
      this.bestLevelFunction =
          bestLevelFunction != null && !"".equals(bestLevelFunction)
              ? bestLevelFunction
              : BESTLEVEL_RANDOM;

      if (nodesDown >= nodeCount
          || nodesDown < 0
          || threshold > nodeCount
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
      this.shuffle = SHUFFLE_XOR;
      this.badNodes = badNodes;
      this.window = new WindowParameters();
    }

    @Override
    public String toString() {
      return "HandelParameters{"
          + "nodeCount="
          + nodeCount
          + ", threshold="
          + threshold
          + ", pairingTime="
          + pairingTime
          + ", levelWaitTime="
          + levelWaitTime
          + ", periodDurationMs="
          + periodDurationMs
          + ", acceleratedCallsCount="
          + acceleratedCallsCount
          + ", nodesDown="
          + nodesDown
          + ", nodeBuilderName='"
          + nodeBuilderName
          + '\''
          + ", networkLatencyName='"
          + networkLatencyName
          + '\''
          + ", desynchronizedStart="
          + desynchronizedStart
          + ", byzantineSuicide="
          + byzantineSuicide
          + ", hiddenByzantine="
          + hiddenByzantine
          + '}';
    }
  }

  public interface ScoringWindow {
    int newSize(int currentSize, boolean correctVerification);

    String name();
  }

  public static class WindowParameters {
    public final int initial; // initial window size
    public final int minimum; // minimum window size at all times
    public final int maximum; // maximum window size at all times
    // what type windows change algorithm do we want to us
    public final ScoringWindow window;

    public WindowParameters() {
      this(16, 1, 128, new ScoringExp());
    }

    private WindowParameters(int initial, int minimum, int maximum, ScoringWindow window) {
      this.initial = initial;
      this.minimum = minimum;
      this.maximum = maximum;
      this.window = window;
    }

    public int newSize(int currentWindowSize, boolean correct) {
      int updatedSize = window.newSize(currentWindowSize, correct);
      if (updatedSize > maximum) {
        return maximum;
      } else if (updatedSize < minimum) {
        return minimum;
      }
      return updatedSize;
    }
  }

  public static class ScoringExp implements ScoringWindow {
    public final double increaseFactor;
    public final double decreaseFactor;

    public ScoringExp(double increaseFactor, double decreaseFactor) {
      this.increaseFactor = increaseFactor;
      this.decreaseFactor = decreaseFactor;
    }

    public ScoringExp() {
      this(2, 4);
    }

    public int newSize(int curr, boolean correct) {
      if (correct) {
        // ceil -> rapidly increasing size
        return (int) Math.ceil((double) curr * increaseFactor);
      } else {
        // floor -> rapidly decreasing size
        return (int) Math.floor((double) curr / decreaseFactor);
      }
    }

    @Override
    public String toString() {
      return "CExp{" + "inc=" + increaseFactor + ",dec=" + decreaseFactor + '}';
    }

    public String name() {
      return "var-exp";
    }
  }

  public Handel(HandelParameters params) {
    this.params = params;
    this.network.setNetworkLatency(
        RegistryNetworkLatencies.singleton.getByName(params.networkLatencyName));
  }

  @Override
  public String toString() {
    return "Handel, "
        + "nodes="
        + params.nodeCount
        + ", threshold="
        + params.threshold
        + ", pairing="
        + params.pairingTime
        + "ms, levelWaitTime="
        + params.levelWaitTime
        + "ms, period="
        + params.periodDurationMs
        + "ms, acceleratedCallsCount="
        + params.acceleratedCallsCount
        + ", dead nodes="
        + params.nodesDown
        + ", builder="
        + params.nodeBuilderName;
  }

  static class SendSigs extends Message<HNode> {
    final int level;
    final BitSet sigs;
    /**
     * A flag to say that you have finished this level and that the receiver should not contact you.
     * It could also be used to signal that you reached the threshold or you're exiting for any
     * reason, i.e. the receiver is wasting his time if he tries to contact you
     */
    final boolean levelFinished;

    final int size;
    /** For simulating a bad signature that will be detected during the verification. */
    final boolean badSig;

    public SendSigs(BitSet sigs, HNode.HLevel l) {
      this.sigs = (BitSet) sigs.clone();
      this.level = l.level;
      this.size =
          1
              + l.expectedSigs() / 8
              + 96 * 2; // Size = level + bit field + the signatures included + our own sig
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
      to.onNewSig(from, this);
    }
  }

  public class HNode extends Node {

    final int startAt;
    final List<HLevel> levels = new ArrayList<>();
    final int nodePairingTime = (int) (Math.max(1, params.pairingTime * speedRatio));
    // reception ranks at this level. The indices represent the node ids and the value represent
    // rank *inside* the level.
    final int[] receptionRanks = new int[params.nodeCount];

    final BitSet blacklist = new BitSet();

    int currWindowSize = params.window.initial;

    final HiddenByzantine hiddenByzantine;

    boolean done = false;
    int sigChecked = 0;
    int sigQueueSize = 0;

    HNode(int startAt, NodeBuilder nb, boolean byzantine) {
      super(network.rd, nb, byzantine);
      this.startAt = startAt;
      this.hiddenByzantine = params.hiddenByzantine && !byzantine ? new HiddenByzantine() : null;
    }

    @Override
    public String toString() {
      String s = "HNode{" + nodeId + "}";
      return s;
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

    public void dissemination() {
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

      // peers, sorted in emission order
      @JsonSerialize(converter = ListNodeConverter.class)
      final List<HNode> peers;

      // The peers when we have all signatures for this level.
      final BitSet waitedSigs = new BitSet(); // 1 for the signatures we should get at this level

      // The aggregate signatures verified in this level
      final BitSet lastAggVerified = new BitSet();

      // The merge of the individual & the last agg verified
      final BitSet totalIncoming = new BitSet();

      // The individual signatures verified in this level
      final BitSet verifiedIndSignatures = new BitSet();

      // The aggregate signatures to verify in this level
      final List<SigToVerify> toVerifyAgg = new ArrayList<>();

      // The individual signatures received
      final BitSet toVerifyInd = new BitSet();

      // The list of peers who told us they had finished this level.
      public BitSet finishedPeers = new BitSet();

      // The signatures we're sending for this level
      final BitSet totalOutgoing = new BitSet();

      // all our peers are complete: no need to send anything for this level
      public boolean outgoingFinished = false;

      /**
       * We're going to contact all nodes, one after the other. That's our position in the peers'
       * list.
       */
      int posInLevel = 0;

      // A cache for the first suicidal byz node in our list (if any)
      int suicideBizAfter = params.byzantineSuicide ? 0 : -1;

      /** Build a level 0 object. At level 0 we need (and have) only our own signature. */
      HLevel() {
        level = 0;
        size = 1;
        outgoingFinished = true;
        lastAggVerified.set(nodeId);
        verifiedIndSignatures.set(nodeId);
        totalIncoming.set(nodeId);
        peers = Collections.emptyList();
      }

      /** Build a level on top of the previous one. */
      HLevel(HLevel previousLevel, BitSet allPreviousNodes) {
        level = previousLevel.level + 1;

        // Signatures needed to finish the current level are:
        //  sigs of the previous level + peers of the previous level.
        //  If we have all this we have finished this level
        waitedSigs.or(allSigsAtLevel(this.level));
        waitedSigs.andNot(allPreviousNodes);
        totalOutgoing.set(nodeId);
        size = waitedSigs.cardinality();
        peers = new ArrayList<>(size);
      }

      /**
       * That's the number of signatures we have if we have all of them. It's also the number of
       * signatures we're going to send.
       */
      public int expectedSigs() {
        return size;
      }

      /** The list of nodes we're waiting signatures from in this level */
      public List<HNode> expectedNodes() {
        List<HNode> expected = new ArrayList<>(size);

        for (int pos, cur = waitedSigs.nextSetBit(0);
            cur >= 0;
            pos = cur + 1, cur = waitedSigs.nextSetBit(pos)) {
          expected.add(network.getNodeById(cur));
        }
        return expected;
      }

      /** We start a level if we reached the time out or if we have all the signatures. */
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

      void buildEmissionList(List<HNode>[] emissions) {
        if (!peers.isEmpty()) {
          throw new IllegalStateException();
        }
        for (List<HNode> ranks : emissions) {
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
        if (!c.intersects(totalIncoming)) {
          c.or(totalIncoming);
        }
        c.or(verifiedIndSignatures);

        return c.cardinality();
      }

      SigToVerify createSuicideByzantineSig(int maxRank) {
        boolean reset = false;
        for (int i = suicideBizAfter; i < peers.size(); i++) {
          HNode p = peers.get(i);
          if (p.isDown() && !blacklist.get(p.nodeId)) {
            if (!reset) {
              suicideBizAfter = i;
              reset = true;
            }
            if (receptionRanks[p.nodeId] < maxRank) {
              return new SigToVerify(p.nodeId, level, receptionRanks[p.nodeId], waitedSigs, true);
            }
          }
        }

        if (!reset) {
          // No byzantine nodes left in this level
          suicideBizAfter = -1;
        }

        return null;
      }

      /**
       * This method uses a window that has a variable size depending on whether the node has
       * received invalid contributions or not. Within the window, it evaluates with a scoring
       * function. Outside it evaluates with the rank.
       */
      public SigToVerify bestToVerify() {
        if (toVerifyAgg.isEmpty()) {
          return null;
        }
        if (currWindowSize < 1) {
          throw new IllegalStateException();
        }

        int windowIndex =
            Collections.min(toVerifyAgg, Comparator.comparingInt(SigToVerify::getRank)).rank;

        if (suicideBizAfter >= 0) {
          SigToVerify bSig = createSuicideByzantineSig(windowIndex + currWindowSize);
          if (bSig != null) {
            toVerifyAgg.add(bSig);
            sigQueueSize++;
            return bSig;
          }
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
            if (stv.rank <= windowIndex + currWindowSize) {

              int score = evaluateSig(this, stv.sig);
              if (score > bestScoreInside) {
                bestScoreInside = score;
                bestInside = stv;
              }
            } else {
              if (bestOutside == null || stv.rank < bestOutside.rank) {
                bestOutside = stv;
              }
            }
          } else {
            removed++;
          }
        }

        if (removed > 0) {
          replaceToVerifyAgg(curatedList);
        }

        SigToVerify toVerify;
        if (bestInside != null) {
          toVerify = bestInside;
        } else if (bestOutside != null) {
          toVerify = bestOutside;
        } else {
          return null;
        }

        return toVerify;
      }

      private void replaceToVerifyAgg(List<SigToVerify> curatedList) {
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
    }

    /**
     * Evaluate the interest to verify a signature by setting a score The higher the score the more
     * interesting the signature is. 0 means the signature is not interesting and can be discarded.
     */
    private int evaluateSig(HLevel l, BitSet sig) {
      int newTotal = 0; // The number of signatures in our new best
      int addedSigs =
          0; // The number of sigs we add with our new best compared to the existing one. Can be
      // negative
      int combineCt =
          0; // The number of sigs in our new best that come from combining it with individual sigs

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

    /** @return all the signatures you should have when this round is finished. */
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

        if (!params.byzantineSuicide) {
          throw new IllegalStateException("We should not have invalid signatures in this scenario");
        }
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
          if (justCompleted
              && params.acceleratedCallsCount > 0
              && !l.outgoingFinished
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

    /** Nothing much to do when we receive a sig set: we just add it to our toVerify list. */
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

    SigToVerify chooseBestFromLevels(List<SigToVerify> bestByLevels) {
      switch (Handel.this.params.bestLevelFunction) {
        case HandelParameters.BESTLEVEL_RANDOM:
          return bestByLevels.get(network.rd.nextInt(bestByLevels.size()));
        case HandelParameters.BESTLEVEL_SCORE:
          int maxScore = 0;
          SigToVerify maxSig = null;
          for (SigToVerify s : bestByLevels) {
            int score = evaluateSig(this.levels.get(s.level), s.sig);
            if (score > maxScore) {
              maxScore = score;
              maxSig = s;
            }
          }
          return maxSig;
      }
      return null;
    }

    void checkSigs() {
      ArrayList<SigToVerify> byLevels = new ArrayList<>();

      for (HLevel l : levels) {
        SigToVerify ss = l.bestToVerify();
        if (ss == null) {
          continue;
        }

        byLevels.add(ss);
      }

      if (byLevels.isEmpty()) { // Nothing to check.
        return;
      }

      SigToVerify best = chooseBestFromLevels(byLevels);
      if (best == null) {
        throw new IllegalStateException("This should never happen");
      }

      if (hiddenByzantine != null && best.level == levels.size() - 1) {
        // We're trying to add a nearly useless signature in the list so that signatures
        //  with a lower rank are not retained.
        best = hiddenByzantine.attack(this, best);
      }

      HLevel l = levels.get(best.level);

      int newSize = params.window.newSize(currWindowSize, !best.badSig);
      currWindowSize = Math.min(newSize, l.size);

      // simply put it at the end of the ranking
      receptionRanks[best.from] += params.nodeCount;
      if (receptionRanks[best.from] < 0) {
        receptionRanks[best.from] = Integer.MAX_VALUE;
      }

      sigChecked++;

      final SigToVerify fBest = best;
      network.registerTask(
          () -> HNode.this.updateVerifiedSignatures(fBest),
          network.time + nodePairingTime,
          HNode.this);
    }
  }

  class HiddenByzantine {
    boolean noByzantinePeers = false;
    SigToVerify last = null;

    HNode firstByzantine(HNode t, HNode.HLevel l) {
      HNode best = null;
      int bestRank = Integer.MAX_VALUE;

      for (HNode p : l.peers) {
        if (p.isDown() && t.receptionRanks[p.nodeId] < bestRank && !l.totalIncoming.get(p.nodeId)) {
          bestRank = t.receptionRanks[p.nodeId];
          best = p;
          if (bestRank == 0) {
            return p;
          }
        }
      }
      return best; // can be null if this node has no byzantine peer.
    }

    /** We're trying to flood the last level with valid but not really useful signatures. */
    SigToVerify attack(HNode target, SigToVerify currentBest) {
      if (noByzantinePeers) {
        return currentBest;
      }

      if (last == currentBest) {
        // A previous attack finally worked. Good.
        last = null;
        return currentBest;
      }

      HNode.HLevel l = target.levels.get(currentBest.level);
      if (last != null) {
        if (l.toVerifyAgg.contains(last)) {
          // ok, we plugged our low quality sig but we're still in the list. The attack failed...
          // this time.
          return currentBest;
        }
        // Our signature was actually tested and included
        if (!l.totalIncoming.get(last.from)) {
          throw new IllegalStateException("byz signature pruned!");
        }
        last = null;
      }

      HNode firstByzantine = firstByzantine(target, l);
      if (firstByzantine == null) {
        noByzantinePeers = true;
        return currentBest;
      }

      if (target.receptionRanks[firstByzantine.nodeId] >= currentBest.rank) {
        // We can't improve it, we're too far.
        return currentBest;
      }

      // Ok, we're going to push a bad signature and check if we can trick the score function
      BitSet cur = new BitSet();
      cur.set(firstByzantine.nodeId);

      SigToVerify bad =
          new SigToVerify(
              firstByzantine.nodeId,
              l.level,
              target.receptionRanks[firstByzantine.nodeId],
              cur,
              false);
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

  @FunctionalInterface
  public interface Shuffler {
    /** rank returns the rank of the sender with respect to the receiver. */
    int rank(HNode receiver, HNode sender);
  }

  public class ShufflingSquare implements Shuffler {

    List<HNode> nodes;

    public ShufflingSquare(List<HNode> nodes) {
      for (HNode n : nodes) {
        for (HNode.HLevel l : n.levels) {
          List<HNode> expected = l.expectedNodes();
          Collections.shuffle(expected, network.rd);
          int startIdx = (int) Math.pow(2, l.level - 1);
          // put back the rank *for the level* in the global list
          for (int i = 0; i < expected.size(); i++) {
            n.receptionRanks[expected.get(i).nodeId] = startIdx + i;
          }
        }
      }
      this.nodes = nodes;
    }

    @Override
    public int rank(HNode receiver, HNode sender) {
      return receiver.receptionRanks[sender.nodeId];
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      for (HNode n : nodes) {
        sb.append("reception ranks of node ").append(n.nodeId).append(" => \n");
        for (HNode.HLevel l : n.levels) {
          sb.append("\tlvl").append(l.level).append(" -> ");
          for (HNode expected : l.expectedNodes()) {
            sb.append(expected.nodeId)
                .append(":")
                .append(n.receptionRanks[expected.nodeId])
                .append(" - ");
          }
          sb.append("\n");
        }
      }
      return sb.toString();
    }
  }

  @Override
  public Handel copy() {
    return new Handel(params);
  }

  private static BitSet chooseBadNodes(Random rd, int nodeCount, int nodesDown) {
    BitSet badNodes = new BitSet();
    for (int setDown = 0; setDown < nodesDown; ) {
      int down = rd.nextInt(nodeCount);
      if (down != 1 && !badNodes.get(down)) {
        // We always keep the node 1 up to help on debugging
        badNodes.set(down);
        setDown++;
      }
    }

    return badNodes;
  }

  @SuppressWarnings("unchecked")
  @Override
  public void init() {
    NodeBuilder nb = RegistryNodeBuilders.singleton.getByName(params.nodeBuilderName);

    BitSet badNodes =
        params.badNodes != null
            ? params.badNodes
            : chooseBadNodes(network.rd, params.nodeCount, params.nodesDown);

    for (int i = 0; i < params.nodeCount; i++) {
      int startAt =
          params.desynchronizedStart == 0 ? 0 : network.rd.nextInt(params.desynchronizedStart);
      boolean byz = (params.byzantineSuicide | params.hiddenByzantine) && badNodes.get(i);
      final HNode n = new HNode(startAt, nb, byz);
      if (badNodes.get(i)) {
        n.stop();
      }
      network.addNode(n);
    }

    for (HNode n : network.allNodes) {
      n.initLevel();
      if (!n.isDown()) {
        network.registerPeriodicTask(n::dissemination, n.startAt + 1, params.periodDurationMs, n);
        network.registerConditionalTask(
            n::checkSigs, n.startAt + 1, n.nodePairingTime, n, n::hasSigToVerify, () -> !n.done);
      }
    }

    List<HNode> an = new ArrayList<>(network.allNodes);

    // The shuffler will give us the reception rank.
    Shuffler s = new ShufflingSquare(an);

    // Now we can build the emission lists from the emission rank
    // Rule: you're contacting first the peers that gave you a good reception rank
    for (HNode sender : network.allNodes) {
      if (sender.isDown()) {
        // No need to build an emission for a node that won't emit
        // This include byzantine nodes.
        continue;
      }

      //
      for (HNode.HLevel l : sender.levels) {
        List<HNode>[] emissionList =
            new List[params.nodeCount]; // ranks are [0..nodeCount], whatever the level
        for (HNode receiver : l.expectedNodes()) {
          int recRank = s.rank(receiver, sender);
          List<HNode> levelList = emissionList[recRank];
          if (levelList == null) {
            levelList = new ArrayList<>(1);
            emissionList[recRank] = levelList;
          }
          levelList.add(receiver);
          receiver.receptionRanks[sender.nodeId] = recRank;
        }
        l.buildEmissionList(emissionList);
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
}
