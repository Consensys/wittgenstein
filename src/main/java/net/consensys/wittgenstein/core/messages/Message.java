package net.consensys.wittgenstein.core.messages;

import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.Node;
import java.lang.reflect.Field;

/**
 * The generic message that goes on a network. Triggers an 'action' on reception.
 * <p>
 * Object of this class must be immutable. Especially, Message is shared between the messages for
 * messages sent to multiple nodes.
 */
public abstract class Message<TN extends Node> {
  public abstract void action(Network<TN> network, TN from, TN to);

  /**
   * We track the total size of the messages exchanged. Subclasses should override this method if
   * they want to measure the network usage.
   */
  public int size() {
    return 1;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();

    for (Field f : this.getClass().getDeclaredFields()) {
      try {
        f.setAccessible(true);
        String v = "" + f.get(this);
        if (sb.length() != 0) {
          sb.append(", ");
        }
        sb.append(f.getName()).append("=").append(v);
      } catch (IllegalAccessException ignore) {
      }
    }

    return this.getClass().getSimpleName() + "{" + sb.toString() + "}";
  }
}
