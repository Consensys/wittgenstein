package net.consensys.wittgenstein.core.messages;

import java.util.Set;
import net.consensys.wittgenstein.core.P2PNode;

/**
 * Class to use when a same node will update the message, i.e. the new version will replace the old
 * ones.
 */
public class StatusFloodMessage<TN extends P2PNode<TN>> extends FloodMessage<TN> {
  /**
   * The message id. It's the same for all versions. The message id must be globally unique, i.e. if
   * multiple nodes are sending the same type of message they need to have two different msg id.
   */
  final int msgId;
  /** The version number. */
  final int seq;

  public StatusFloodMessage(int msgId, int seq, int size, int localDelay, int delayBetweenPeers) {
    super(size, localDelay, delayBetweenPeers);
    if (msgId < 0) {
      throw new IllegalStateException("id less than zero are reserved, msgId=" + msgId);
    }
    this.msgId = msgId;
    this.seq = seq;
  }

  @Override
  public long msgId() {
    return msgId;
  }

  /** We're adding this message to the node's received set only if the seq number is greater. */
  @Override
  public boolean addToReceived(TN to) {
    Set<?> previousSet = to.getMsgReceived(msgId);
    Object previous = previousSet.isEmpty() ? null : previousSet.iterator().next();
    StatusFloodMessage psf = (StatusFloodMessage) previous;
    if (psf != null && psf.seq >= seq) {
      return false;
    }
    previousSet.clear(); // By definition we want only one element in the set
    to.getMsgReceived(msgId).add(this);
    return true;
  }
}
