package net.consensys.wittgenstein.protocols.handeleth2;

import java.util.*;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.NodeBuilder;
import net.consensys.wittgenstein.core.utils.MoreMath;

@SuppressWarnings("WeakerAccess")
public class HNode extends Node {
  final transient HandelEth2 handelEth2;
  final int deltaStart;

  final int nodePairingTime;

  int aggDone = 0;
  long contributionsTotal = 0;

  /** The current height for this node. Increase every PERIOD_TIME seconds */
  int height = 1000;

  /**
   * Our peers, sorted by emission rank inside a level. The emission ranks do not change during an
   * aggregation, so these list can be shared between aggregation process.
   */
  final transient List<List<HNode>> peersPerLevel = new ArrayList<>();

  /**
   * The reception ranks. These rank change during the process, so this array must be copied by the
   * aggregation process.
   */
  final int[] receptionRanks;

  final transient Map<Integer, AggregationProcess> runningAggs = new HashMap<>();

  /**
   * The list of all nodes who sent bad signatures. This list is global as we keep it between
   * rounds.
   */
  final BitSet blacklist = new BitSet();

  int curWindowsSize = 16;

  /** Called after a successful verification. */
  void successfulVerification() {
    curWindowsSize = Math.min(128, curWindowsSize * 2);
  }

  /** Called after a verification failure. */
  void failedVerification() {
    curWindowsSize = Math.max(1, curWindowsSize / 4);
  }

  HNode(HandelEth2 handelEth2, int deltaStart, NodeBuilder nb) {
    super(handelEth2.network().rd, nb, false);
    this.handelEth2 = handelEth2;
    this.deltaStart = deltaStart;
    this.nodePairingTime = (int) (Math.max(1, handelEth2.params.pairingTime * speedRatio));
    this.receptionRanks = new int[handelEth2.params.nodeCount];
  }

  /**
   * Creation of a level 0 attestation, ie. the individual attestation. We choose randomly the hash
   * we're going to attest. To replicate real world logic (where most nodes should have the same
   * hashes), 80% of the time we choose 0 as ahas value. Then 20%.80% 1, then 20%.20%.80% 2 and so
   * on.
   */
  Attestation create(int height) {
    int h = 0;
    while (handelEth2.network().rd.nextDouble() < 0.2) {
      h++;
    }
    return new Attestation(height, h, nodeId);
  }

  /** @return all the signatures you should have when this round is finished. */
  BitSet peersUpToLevel(int level) {
    if (level < 1) {
      throw new IllegalArgumentException("round=" + level);
    }
    BitSet res = new BitSet(handelEth2.params.nodeCount);
    int cMask = (1 << level) - 1;
    int start = (cMask | nodeId) ^ cMask;
    int end = nodeId | cMask;
    end = Math.min(end, handelEth2.params.nodeCount - 1);
    res.set(start, end + 1);
    res.set(nodeId, false);

    return res;
  }

  /** @return the level at which we communicate with 'n' */
  int communicationLevel(HNode n) {
    if (nodeId == n.nodeId) {
      throw new IllegalArgumentException("same id: " + n.nodeId);
    }

    int n1 = nodeId;
    int n2 = n.nodeId;
    for (int l = 1; l <= handelEth2.levelCount(); l++) {
      n1 >>= 1;
      n2 >>= 1;

      if (n1 == n2) {
        return l;
      }
    }

    throw new IllegalStateException("Can't communicate with " + n);
  }

  /** Represents an ongoing aggregation. Eth2 starts a new aggregation every 6 seconds. */
  class AggregationProcess {
    final int height;
    final int ownHash;
    final int startAt;
    final int endAt;
    final int[] receptionRanks;

    // The list of peers who told us they had finished the level they have in common with us.
    final BitSet finishedPeers = new BitSet();

    final List<HLevel> levels = new ArrayList<>();
    int lastLevelVerified = 0;

    AggregationProcess(Attestation l0, int startAt, int[] receptionRanks) {
      this.receptionRanks = receptionRanks.clone();
      this.height = l0.height;
      this.ownHash = l0.hash;
      this.startAt = startAt;
      this.endAt = startAt + HandelEth2Parameters.PERIOD_TIME;
      initLevel(handelEth2.params.nodeCount, l0);
      assert levels.size() == handelEth2.levelCount() + 1; // for level 0
    }

    private void initLevel(int nodeCount, Attestation l0) {
      int roundedPow2NodeCount = MoreMath.roundPow2(nodeCount);
      HLevel last = new HLevel(HNode.this, l0);
      levels.add(last);
      for (int l = 1; Math.pow(2, l) <= roundedPow2NodeCount; l++) {
        last = new HLevel(last, peersPerLevel.get(l));
        levels.add(last);
      }
    }

    /** @return the best signature to verify for this process; null if there are none. */
    public AggToVerify bestToVerify() {

      // Level 1 sigs are specific, because we receive them only once, so we can
      //  verify them first.
      AggToVerify res1 = levels.get(1).bestToVerify(curWindowsSize, blacklist);
      if (res1 != null) {
        return res1;
      }

      int start = lastLevelVerified;
      for (int i = 2; i <= levels.size(); i++) {
        HLevel hl = levels.get(start);
        AggToVerify res = hl.bestToVerify(curWindowsSize, blacklist);
        if (res != null) {
          lastLevelVerified = start;
          return res;
        } else {
          start++;
          if (start >= levels.size()) {
            start = 2;
          }
        }
      }
      return null;
    }

    /**
     * If the state has changed we send a message to all. If we're done, we update all our peers: it
     * will be our last message.
     */
    void updateVerifiedSignatures(AggToVerify vs) {
      HLevel hl = levels.get(vs.level);

      if (vs.height != height) {
        throw new IllegalStateException("wrong heights, vs:" + vs + ", ap=" + this);
      }

      if (hl.isIncomingComplete()) {
        throw new IllegalStateException(
            "No need to verify a contribution for a complete level. vs:" + vs);
      }

      hl.mergeIncoming(vs);
      successfulVerification();

      if (hl.isIncomingComplete() && hl.level < handelEth2.levelCount()) {
        // If incoming is complete it means we may be complete for the upper levers
        // That would trigger the fast path.
        updateAllOutgoing();
        for (int l = hl.level + 1; l < handelEth2.levelCount(); l++) {
          HLevel hu = levels.get(l);
          if (hu.isOutgoingComplete()) {
            hu.fastPath(ownHash, finishedPeers);
          }
        }
      }
    }

    /** Update all the outgoing attestations. */
    void updateAllOutgoing() {
      Map<Integer, Attestation> atts = new HashMap<>();
      int size = 0;
      for (HLevel hl : levels) {

        if (hl.isOpen(startAt)) {
          hl.outgoing.clear();
          hl.outgoing.putAll(atts);
          hl.outgoingCardinality = size;
        }

        for (Attestation a : hl.incoming.values()) {
          Attestation existing = atts.get(a.hash);
          size += a.who.cardinality();
          if (existing == null) {
            atts.put(a.hash, a);
          } else {
            Attestation merged = new Attestation(existing, existing.who);
            merged.who.or(a.who);
            atts.replace(merged.hash, merged);
          }
        }
      }
    }

    /**
     * @return the best attestations we got, i.e. the merge of the last level's incoming and
     *     outgoing contributions
     */
    Map<Integer, Attestation> getBestResult() {
      HLevel last = levels.get(levels.size() - 1);
      return HLevel.merge(last.incoming, last.outgoing);
    }

    int getBestResultSize() {
      HLevel last = levels.get(levels.size() - 1);
      return last.incomingCardinality + last.outgoingCardinality;
    }
  }

  /**
   * Every 'DP' milliseconds, a node sends its current aggregation to a set of his peers. This
   * method is called every dissemination period by Wittgenstein.
   */
  void dissemination() {
    for (AggregationProcess ap : runningAggs.values()) {
      ap.updateAllOutgoing();
      for (HLevel sfl : ap.levels) {
        sfl.doCycle(ap.ownHash, ap.finishedPeers, ap.startAt);
      }
    }
  }

  /**
   * We consider that delegate a single core to the verification of the received messages. This
   * method is called every 'pairingTime' by Wittgenstein.
   */
  AggregationProcess lastVerified = null;

  public void verify() {
    if (runningAggs.isEmpty()) {
      return;
    }
    if (lastVerified == null) {
      lastVerified = runningAggs.values().iterator().next();
    }

    for (int i = 0; i < runningAggs.size(); i++) {
      AggregationProcess ap = runningAggs.get(lastVerified.height + 1);
      if (ap == null) {
        int min = Collections.min(runningAggs.keySet());
        ap = runningAggs.get(min);
      }
      AggToVerify sa = ap.bestToVerify();

      if (sa != null) {
        lastVerified = ap;
        AggregationProcess tv = ap;
        handelEth2
            .network()
            .registerTask(
                () -> tv.updateVerifiedSignatures(sa),
                // We want to update the signature before the verification loop runs again, if
                //  not we will check twice the same sig. Hence the -1
                handelEth2.network().time + nodePairingTime - 1,
                HNode.this);
        break;
      }
    }
  }

  /** Called every 'PERIOD_TIME' seconds by Wittgenstein. */
  public void startNewAggregation() {
    // We're creating our level 0 attestation. We
    Attestation base = create(height + 1);
    startNewAggregation(base);
  }

  void startNewAggregation(Attestation base) {
    height = base.height;
    int startAt = handelEth2.network().time;
    int endAt = startAt + HandelEth2Parameters.PERIOD_AGG_TIME;
    AggregationProcess ap = new AggregationProcess(base, startAt, receptionRanks);

    Object past = runningAggs.put(ap.height, ap);
    if (past != null) {
      throw new IllegalStateException();
    }

    handelEth2.network().registerTask(() -> stopAggregation(base.height), endAt, this);
  }

  /** All the tasks related to stopping an aggregation. */
  void stopAggregation(int height) {
    // Some stats...
    contributionsTotal += runningAggs.get(height).getBestResultSize();
    aggDone++;

    runningAggs.remove(height);
  }

  /** Called when we receive a new aggregate contribution */
  public void onNewAgg(HNode from, SendAggregation agg) {

    AggregationProcess ap = runningAggs.get(agg.height);
    if (ap == null) {
      // message received too early or too late
      return;
    }

    if (agg.levelFinished) {
      // In production we need to be sure that the message is legit before
      //  doing this (ie.: we checked the sig).
      ap.finishedPeers.set(from.nodeId);
    }

    HLevel hl = ap.levels.get(agg.level);

    // Get and update the reception ranks
    int rank = ap.receptionRanks[from.nodeId];
    ap.receptionRanks[from.nodeId] += handelEth2.params.nodeCount;
    if (receptionRanks[from.nodeId] <= 0) {
      receptionRanks[from.nodeId] = Integer.MAX_VALUE;
    }

    // todo: we should check if we have already a message from this node
    //  and replace it by this new one if it's the case.

    if (!hl.isIncomingComplete()) {
      hl.toVerifyAgg.add(
          new AggToVerify(from.nodeId, hl.level, agg.ownHash, rank, agg.attestations));
    }
  }

  static class AggToVerifyStore {}
}
