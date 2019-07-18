package net.consensys.wittgenstein.core.messages;

import java.util.List;

@SuppressWarnings("WeakerAccess")
public class SendMessage {
  public final int from;
  public final List<Integer> to;
  public final int sendTime;
  public final int delayBetweenSend;
  public final Message<?> message;

  // For json
  public SendMessage() {
    this(0, null, 0, 0, null);
  }

  public SendMessage(
      int from, List<Integer> to, int sendTime, int delayBetweenSend, Message<?> message) {
    this.from = from;
    this.to = to;
    this.sendTime = sendTime;
    this.message = message;
    this.delayBetweenSend = delayBetweenSend;
  }
}
