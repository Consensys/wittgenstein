package net.consensys.wittgenstein.protocols;

import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.messages.Message;

/**
 * A simulation of a trivial protocol to be used as a sample.
 */
public class PingPong implements Protocol {
  /**
   * You need a network. Nodes are added to this network. Network latency can be set later.
   */
  private final Network<PingPongNode> network = new Network<>();

  /**
   * Nodes have positions. This position is chosen by the builder.
   */
  private final NodeBuilder nb = new NodeBuilder.NodeBuilderWithRandomPosition();

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

  final int nodeCt = 1000;

  /**
   * Nodes are specialized for the protocol.
   */
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
    return new PingPong();
  }

  @Override
  public void init() {
    for (int i = 0; i < nodeCt; i++) {
      network.addNode(new PingPongNode());
    }
    network.sendAll(new Ping(), network.getNodeById(0));
  }

  @Override
  public Network<PingPongNode> network() {
    return network;
  }


  public static void main(String... args) {
    PingPong p = new PingPong();

    p.network.setNetworkLatency(new NetworkLatency.NetworkLatencyByDistance());
    p.init();

    for (int i = 0; i < 1000; i += 100) {
      System.out.println(i + " ms, pongs received " + p.network.getNodeById(0).pong);
      p.network.runMs(100);
    }
  }
}
