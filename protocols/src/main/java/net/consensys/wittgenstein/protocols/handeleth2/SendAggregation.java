package net.consensys.wittgenstein.protocols.handeleth2;

import java.util.Collections;
import java.util.List;
import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.messages.Message;

/** The only message exchanged by the aggregating participants. */
class SendAggregation extends Message<HNode> {

  /**
   * The height: as we can run multiple aggregations for multiple heights in parallel, this allows
   * to identify what we are actually aggregating.
   */
  final int height;

  /** The level: each participant sends aggregation for a given level in the communication tree. */
  final int level;

  /**
   * In Ethereum 2, different participants can have different views of the current block for a given
   * height: this is represented by you own hash. In Handel you must include your individual
   * contribution for the hash you're attesting for.
   */
  final int ownHash;

  /** As a participant, se send all the attestations we received for a given height. */
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
      throw new IllegalStateException("no attestation with your own hash?");
    }
  }

  // For tests
  SendAggregation(int level, int ownHash, boolean levelFinished, Attestation attestation) {
    this(level, ownHash, levelFinished, Collections.singletonList(attestation));
  }

  @Override
  public void action(Network<HNode> network, HNode from, HNode to) {
    to.onNewAgg(from, this);
  }
}
