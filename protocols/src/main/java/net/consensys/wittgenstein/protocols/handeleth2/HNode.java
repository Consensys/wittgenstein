package net.consensys.wittgenstein.protocols.handeleth2;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.NodeBuilder;
import net.consensys.wittgenstein.core.utils.MoreMath;

@SuppressWarnings("WeakerAccess")
public class HNode extends Node {
  final transient HandelEth2 handelEth2;
  final int deltaStart;

  final int nodePairingTime;

  /**
   * Our peers, sorted by emission rank inside a level. The emission ranks do not change during an
   * aggregation, so these list can be shared between aggregation process.
   */
  final List<List<HNode>> peersPerLevel = new ArrayList<>();

  /**
   * The reception ranks. These rank change during the process, so this array must be copied by the
   * aggregation process.
   */
  final int[] receptionRanks;

  final ArrayList<AggregationProcess> runningAggs = new ArrayList<>();

  /**
   * The list of all nodes who sent bad signatures. This list is global as we keep it between
   * rounds.
   */
  final BitSet blacklist = new BitSet();

  int curWindowsSize = 16;

  void successfulVerification() {
    curWindowsSize = Math.min(128, curWindowsSize * 2);
  }

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

  Attestation create(int height) {
    int h = 0;
    while (handelEth2.network().rd.nextDouble() < 0.2) {
      h++;
    }
    return new Attestation(height, h, nodeId);
  }

  class AggregationProcess {
    final int height;
    final int ownHash;
    final int startAt;
    final int endAt;
    final int[] receptionRanks;

    // The
    final BitSet finishedPeers = new BitSet();

    final List<HLevel> levels = new ArrayList<>();
    int lastLevelVerified = 0;

    AggregationProcess(Attestation l0, int startAt, int[] receptionRanks) {
      this.receptionRanks = receptionRanks.clone();
      this.height = l0.height;
      this.ownHash = l0.hash;
      this.startAt = startAt;
      this.endAt = startAt + HandelEth2Parameters.TIME_BETWEEN_ATTESTATION;
      initLevel(handelEth2.params.nodeCount, l0);
    }

    public void initLevel(int nodeCount, Attestation l0) {
      int roundedPow2NodeCount = MoreMath.roundPow2(nodeCount);
      BitSet allPreviousNodes = new BitSet(nodeCount);
      HLevel last = new HLevel(HNode.this, l0);
      levels.add(last);
      for (int l = 1; Math.pow(2, l) <= roundedPow2NodeCount; l++) {
        last = new HLevel(last, peersPerLevel.get(l));
        levels.add(last);
      }
    }

    /** @return the best signature to verify for this process; null if there are none. */
    public AggToVerify bestToVerify() {
      int start = lastLevelVerified;
      for (int i = 0; i < levels.size(); i++) {
        HLevel hl = levels.get(start);
        AggToVerify res = hl.bestToVerify(curWindowsSize, blacklist);
        if (res != null) {
          lastLevelVerified = start;
          return res;
        } else {
          start++;
          if (start >= levels.size()) {
            start = 0;
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

      if (hl.isIncomingComplete()) {
        throw new IllegalStateException();
      }

      hl.mergeIncoming(vs);
      successfulVerification();

      // todo: we need to add the fast path here
    }
  }

  /**
   * Every 'DP' milliseconds, a node sends its currrent aggregation to a set of his peers. This
   * method is called every 'DP' by Wittgenstein.
   */
  void dissemination() {
    for (AggregationProcess ap : runningAggs) {
      for (HLevel sfl : ap.levels) {
        sfl.doCycle(ap.ownHash, ap.finishedPeers);
      }
    }
  }

  /**
   * We consider that delegate a single core to the verification of the received messages. This
   * method is called every 'pairingTime' by Wittgenstein.
   */
  int lastProcessVerified = 0;

  void verify() {
    int start = lastProcessVerified;
    for (int i = 0; i < runningAggs.size(); i++) {
      if (++start >= runningAggs.size()) {
        start = 0;
      }
      AggregationProcess ap = runningAggs.get(start);
      AggToVerify sa = ap.bestToVerify();

      if (sa != null) {
        handelEth2
            .network()
            .registerTask(
                () -> ap.updateVerifiedSignatures(sa),
                handelEth2.network().time + nodePairingTime,
                HNode.this);
      }
    }
  }
}
