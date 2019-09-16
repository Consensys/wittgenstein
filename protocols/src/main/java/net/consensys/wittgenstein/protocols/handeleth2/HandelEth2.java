package net.consensys.wittgenstein.protocols.handeleth2;

import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.Protocol;

/** Using the Handel protocol on Eth2. */
public class HandelEth2 implements Protocol {
  final HandelEth2Parameters params;
  private final Network<HNode> network = new Network<>();

  public HandelEth2(HandelEth2Parameters params) {
    this.params = params;
  }

  @Override
  public Network<?> network() {
    return null;
  }

  @Override
  public Protocol copy() {
    return null;
  }

  @Override
  public void init() {}
}
