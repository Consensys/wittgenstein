package net.consensys.wittgenstein.protocols.handeleth2;

import java.util.BitSet;

/** An attestation is for a given height and a given block hash. */
public class Attestation {
  // The height for this attestation
  final int height;
  // The hash we're attesting for
  final int hash;
  // Who is attesting
  final BitSet who;

  // An initial attestation, with a single attester.
  public Attestation(int height, int hash, int who) {
    this.height = height;
    this.hash = hash;
    this.who = new BitSet();
    this.who.set(who);
  }

  public Attestation(Attestation base, BitSet whoToCopy) {
    this.height = base.height;
    this.hash = base.hash;
    this.who = (BitSet) whoToCopy.clone();
  }

  @Override
  public String toString() {
    return "{" + "height=" + height + ", hash=" + hash + ", who=" + who + '}';
  }
}
