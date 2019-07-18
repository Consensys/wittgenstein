package net.consensys.wittgenstein.server;

import java.util.Collections;
import java.util.List;
import net.consensys.wittgenstein.core.EnvelopeInfo;
import net.consensys.wittgenstein.core.External;
import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.messages.Message;
import net.consensys.wittgenstein.core.messages.SendMessage;

/** Just print the messages received, but actually relays the execution to a real node. */
public class ExternalMockImplementation implements External {
  private final Network<?> network;

  public ExternalMockImplementation(Network<?> network) {
    this.network = network;
  }

  @Override
  public String toString() {
    return this.getClass().getSimpleName();
  }

  @SuppressWarnings("unchecked")
  @Override
  public <TN extends Node> List<SendMessage> receive(EnvelopeInfo<TN> ei) {
    System.out.println("received:" + ei);

    Network<TN> n = (Network<TN>) network;
    if (network.time != ei.arrivingAt) {
      throw new IllegalArgumentException(network.time + " env:" + ei);
    }

    TN f = n.getNodeById(ei.from);
    TN t = n.getNodeById(ei.to);
    Message<TN> m = (Message<TN>) ei.msg;
    m.action(n, f, t);

    return Collections.emptyList();
  }
}
