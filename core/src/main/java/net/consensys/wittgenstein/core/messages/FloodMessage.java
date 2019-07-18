package net.consensys.wittgenstein.core.messages;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.P2PNode;

/**
 * A P2P node supports flood by default. In P2P protocols: - you can wait before resending the
 * message to your peers, for example because you're validating the message. - you can send the
 * message to all your peers immediately, or one after another with a delay between each send. Here,
 * we embed this logic in the message; allowing each message to have a different strategy.
 */
public class FloodMessage<TN extends P2PNode<TN>> extends Message<TN> {
  protected final int size;
  /**
   * The delay before we send this message to the other nodes, for example if we need to validate
   * the message before diffusing it.
   */
  public final int localDelay;
  /**
   * It's possible to send the message immediately to all peers, but as well to wait between peers.
   */
  public final int delayBetweenPeers;

  public long msgId() {
    return -1;
  }

  // for json
  @SuppressWarnings("unused")
  public FloodMessage() {
    this(0, 0, 0);
  }

  public FloodMessage(int size, int localDelay, int delayBetweenPeers) {
    this.size = size;
    this.localDelay = localDelay;
    this.delayBetweenPeers = delayBetweenPeers;
  }

  public boolean addToReceived(TN to) {
    return to.getMsgReceived(msgId()).add(this);
  }

  @Override
  public void action(Network<TN> network, TN from, TN to) {
    if (addToReceived(to)) {
      to.onFlood(from, this);
      List<TN> dest = to.peers.stream().filter(n -> n != from).collect(Collectors.toList());
      Collections.shuffle(dest, network.rd);
      network.send(this, network.time + 1 + localDelay, to, dest, delayBetweenPeers);
    }
  }

  @Override
  public int size() {
    return size;
  }
}
