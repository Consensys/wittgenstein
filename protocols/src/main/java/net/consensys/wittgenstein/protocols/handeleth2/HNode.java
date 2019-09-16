package net.consensys.wittgenstein.protocols.handeleth2;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.NodeBuilder;

public class HNode extends Node {
  final transient HandelEth2 handelEth2;
  final int startAt;
  final List<HLevel> levels = new ArrayList<>();
  final int nodePairingTime;
  final int[] receptionRanks;

  /**
   * The list of all nodes who sent bad signatures. This list is global as we keep it between
   * rounds.
   */
  final BitSet blacklist = new BitSet();

  HNode(HandelEth2 handelEth2, int startAt, NodeBuilder nb) {
    super(handelEth2.network().rd, nb, false);
    this.handelEth2 = handelEth2;
    this.startAt = startAt;
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
}
