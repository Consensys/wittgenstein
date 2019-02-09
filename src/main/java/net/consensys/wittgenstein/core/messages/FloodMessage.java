package net.consensys.wittgenstein.core.messages;

import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.P2PNode;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A P2P node supports flood by default.
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

  public FloodMessage(int size, int localDelay, int delayBetweenPeers) {
    this.size = size;
    this.localDelay = localDelay;
    this.delayBetweenPeers = delayBetweenPeers;
  }

  protected boolean addToReceived(TN to) {
    return to.getSet(msgId()).add(this);
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
