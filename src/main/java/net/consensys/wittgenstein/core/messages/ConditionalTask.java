package net.consensys.wittgenstein.core.messages;

import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.Node;

public final class ConditionalTask<TN extends Node> extends Task<TN> {
  /**
   * Starts if this condition is met.
   */
  public final Network.Condition startIf;

  /**
   * Will start again when the task is finished if this condition is met.
   */
  public final Network.Condition repeatIf;

  /**
   * Time before next start.
   */
  public final int duration;
  private final TN sender;

  /**
   * Will start after this time.
   */
  public int minStartTime;

  public ConditionalTask(Network.Condition startIf, Network.Condition repeatIf, Runnable r,
      int minStartTime, int duration, TN sender) {
    super(r);
    this.startIf = startIf;
    this.repeatIf = repeatIf;
    this.duration = duration;
    this.sender = sender;
    this.minStartTime = minStartTime;
  }
}
