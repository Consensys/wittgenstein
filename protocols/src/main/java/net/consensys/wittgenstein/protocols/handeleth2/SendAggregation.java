package net.consensys.wittgenstein.protocols.handeleth2;

import java.util.Collections;
import java.util.List;
import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.messages.Message;

class SendAggregation extends Message<HNode> {
  final int level;
  final int height;
  final int ownHash; // the hash of our own attestation
  /** We send all the attestations we received for a given height. */
  final List<Attestation> attestations;

  /**
   * A flag to say that you have finished this level and that the receiver should not contact you.
   * It could also be used to signal that you reached the threshold or you're exiting for any
   * reason, i.e. the receiver is wasting his time if he tries to contact you
   */
  final boolean levelFinished;

  SendAggregation(int level, int ownHash, boolean levelFinished, List<Attestation> attestations) {
    if (attestations.isEmpty()) {
      throw new IllegalArgumentException("attestations should not be empty");
    }
    this.attestations = attestations;
    this.height = attestations.get(0).height;
    this.level = level;
    this.ownHash = ownHash;
    this.levelFinished = levelFinished;

    boolean foundHash = false;
    for (Attestation a : attestations) {
      if (a.height != height) {
        throw new IllegalStateException("bad height:" + attestations);
      }
      if (a.hash == ownHash) {
        foundHash = true;
      }
    }
    if (!foundHash) {
      throw new IllegalStateException("no attestation with you own hash?");
    }
  }

  // For tests
  SendAggregation(int level, int ownHash, boolean levelFinished, Attestation attestation) {
    this(level, ownHash, levelFinished, Collections.singletonList(attestation));
  }

  @Override
  public int size() {
    return 1;
  }

  @Override
  public void action(Network<HNode> network, HNode from, HNode to) {
    to.onNewAgg(from, this);
  }
}
