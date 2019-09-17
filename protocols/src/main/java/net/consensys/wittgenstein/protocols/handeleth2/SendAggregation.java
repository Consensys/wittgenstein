package net.consensys.wittgenstein.protocols.handeleth2;

import java.util.Collection;
import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.messages.Message;

class SendAggregation extends Message<HNode> {
  final int level;
  final int ownHash; // the hash of our own attestation
  /** We send all the attestations we received for a given height. */
  final Collection<Attestation> attestations;

  /**
   * A flag to say that you have finished this level and that the receiver should not contact you.
   * It could also be used to signal that you reached the threshold or you're exiting for any
   * reason, i.e. the receiver is wasting his time if he tries to contact you
   */
  final boolean levelFinished;

  public SendAggregation(
      int level, int ownHash, boolean levelFinished, Collection<Attestation> attestations) {
    this.attestations = attestations;
    this.level = level;
    this.ownHash = ownHash;
    this.levelFinished = levelFinished;
  }

  @Override
  public int size() {
    return 1;
  }

  @Override
  public void action(Network<HNode> network, HNode from, HNode to) {
    // todo
  }
}
