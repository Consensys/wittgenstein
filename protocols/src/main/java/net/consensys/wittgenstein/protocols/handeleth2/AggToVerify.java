package net.consensys.wittgenstein.protocols.handeleth2;

import java.util.List;

class AggToVerify {
  final int from;
  final int height;
  final int ownHash;
  final int rank;
  final List<Attestation> attestations;
  final int level;

  int getRank() {
    return rank;
  }

  AggToVerify(int from, int level, int ownHash, int rank, List<Attestation> attestations) {
    if (level <= 0 || from < 0 || ownHash < 0 || attestations.isEmpty()) {
      throw new IllegalArgumentException();
    }
    this.from = from;
    this.ownHash = ownHash;
    this.rank = rank;
    this.attestations = attestations;
    this.level = level;
    this.height = attestations.get(0).height;

    for (Attestation a : attestations) {
      if (a.height != height) {
        throw new IllegalArgumentException("bad attestation list:" + attestations);
      }
    }
  }

  @Override
  public String toString() {
    return "AggToVerify{"
        + "from="
        + from
        + ", ownHash="
        + ownHash
        + ", rank="
        + rank
        + ", level="
        + level
        + '}';
  }
}
