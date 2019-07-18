package net.consensys.wittgenstein.core.messages;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.utils.Strings;

/**
 * The generic message that goes on a network. Triggers an 'action' on reception.
 *
 * <p>Object of this class must be immutable. Especially, Message is shared between the messages for
 * messages sent to multiple nodes.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
public abstract class Message<TN extends Node> {

  /**
   * Must be implemented by the protocol implementers to specify what happens when a node receive
   * this message.
   */
  public abstract void action(Network<TN> network, TN from, TN to);

  /**
   * We track the total size of the messages exchanged. Subclasses should override this method if
   * they want to measure the network usage.
   */
  public int size() {
    return 1;
  }

  /**
   * Default implementation, using reflection to print the fields value. Can be overridden by the
   * subclasses if they want to.
   */
  @Override
  public String toString() {
    return Strings.toString(this);
  }
}
