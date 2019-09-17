package net.consensys.wittgenstein.protocols.handeleth2;

import java.util.List;

public class AggToVerify {
  final int from;
  final int ownHash;
  final int rank;
  final List<Attestation> attestations;
  public int level;

  public int getRank() {
    return rank;
  }

  public AggToVerify(int from, int ownHash, int rank, List<Attestation> attestations) {
    this.from = from;
    this.ownHash = ownHash;
    this.rank = rank;
    this.attestations = attestations;
  }
}
