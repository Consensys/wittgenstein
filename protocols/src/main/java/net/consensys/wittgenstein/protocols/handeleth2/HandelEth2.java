package net.consensys.wittgenstein.protocols.handeleth2;

import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.NodeBuilder;
import net.consensys.wittgenstein.core.Protocol;
import net.consensys.wittgenstein.core.RegistryNodeBuilders;

/** Using the Handel protocol on Eth2. */
public class HandelEth2 implements Protocol {
  final HandelEth2Parameters params;
  private final Network<HNode> network = new Network<>();

  public HandelEth2(HandelEth2Parameters params) {
    this.params = params;
  }

  @Override
  public Network<HNode> network() {
    return network;
  }

  @Override
  public HandelEth2 copy() {
    return new HandelEth2(params);
  }

  @Override
  public void init() {
    NodeBuilder nb = RegistryNodeBuilders.singleton.getByName(params.nodeBuilderName);

    for (int i = 0; i < params.nodeCount; i++) {
      network.addNode(new HNode(this, 0, nb));
    }
  }
}
