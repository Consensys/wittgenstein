package net.consensys.wittgenstein.protocols;

import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.messages.Message;

/** A simulation of a trivial protocol to be used as a sample. */
public class PingPong implements Protocol {
  final PingPongParameters params;

  /** You need a network. Nodes are added to this network. Network latency can be set later. */
  private final Network<PingPongNode> network = new Network<>();

  /** Nodes have positions. This position is chosen by the builder. */
  private final NodeBuilder nb;

  /**
   * Messages, exchanged on the network, are specific to the protocol. Here we have two messages:
   * Ping & Pong.
   */
  static class Ping extends Message<PingPongNode> {
    @Override
    public void action(Network<PingPongNode> network, PingPongNode from, PingPongNode to) {
      to.onPing(from);
    }
  }

  static class Pong extends Message<PingPongNode> {
    @Override
    public void action(Network<PingPongNode> network, PingPongNode from, PingPongNode to) {
      to.onPong();
    }
  }

  public static class PingPongParameters extends WParameters {
    final int nodeCt;
    final String nodeBuilderName;
    final String networkLatencyName;

    public PingPongParameters() {
      nodeCt = 1000;
      nodeBuilderName = null;
      networkLatencyName = null;
    }

    public PingPongParameters(int nodeCt, String nodeBuilderName, String networkLatencyName) {
      this.nodeCt = nodeCt;
      this.nodeBuilderName = nodeBuilderName;
      this.networkLatencyName = networkLatencyName;
    }
  }

  public PingPong(PingPongParameters params) {
    this.params = params;
    this.nb = RegistryNodeBuilders.singleton.getByName(params.nodeBuilderName);
    this.network.setNetworkLatency(
        RegistryNetworkLatencies.singleton.getByName(params.networkLatencyName));
  }

  /** Nodes are specialized for the protocol. */
  class PingPongNode extends Node {
    int pong;

    PingPongNode() {
      super(network.rd, nb);
    }

    void onPing(PingPongNode from) {
      network.send(new Pong(), this, from);
    }

    void onPong() {
      pong++;
    }
  }

  @Override
  public PingPong copy() {
    return new PingPong(params);
  }

  @Override
  public void init() {
    for (int i = 0; i < params.nodeCt; i++) {
      network.addNode(new PingPongNode());
    }
    network.sendAll(new Ping(), network.getNodeById(0));
  }

  @Override
  public Network<PingPongNode> network() {
    return network;
  }

  public static void main(String... args) {
    PingPong p = new PingPong(new PingPongParameters());
    p.init();
    for (int i = 0; i < 500; i += 50) {
      System.out.println(i + " ms, pongs received " + p.network.getNodeById(0).pong);
      p.network.runMs(50);
    }
  }
}
