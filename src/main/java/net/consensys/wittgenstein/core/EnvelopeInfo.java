package net.consensys.wittgenstein.core;

import net.consensys.wittgenstein.core.messages.Message;

@SuppressWarnings("WeakerAccess")
public class EnvelopeInfo implements Comparable<EnvelopeInfo> {
  public final int from;
  public final int to;
  public final int sentAt;
  public final int arrivingAt;
  public final Message<?> msg;

  EnvelopeInfo(int from, int to, int sentAt, int arrivingAt, Message<?> msg) {
    this.from = from;
    this.to = to;
    this.sentAt = sentAt;
    this.arrivingAt = arrivingAt;
    this.msg = msg;
  }

  @Override
  public int compareTo(EnvelopeInfo o) {
    if (arrivingAt != o.arrivingAt) {
      return Integer.compare(arrivingAt, o.arrivingAt);
    }
    if (sentAt != o.sentAt) {
      return Integer.compare(arrivingAt, o.arrivingAt);
    }
    if (from != o.from) {
      return Integer.compare(from, o.from);
    }
    if (to != o.to) {
      return Integer.compare(arrivingAt, o.arrivingAt);
    }
    return Integer.compare(System.identityHashCode(msg), System.identityHashCode(o.msg));
  }

}
