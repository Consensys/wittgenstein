package net.consensys.wittgenstein.protocols.handeleth2;

import java.util.BitSet;

/** An attestion is for a given height and a given block hash. We */
public class Attestation {
  final int height;
  final int hash;
  final BitSet who;

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
}
