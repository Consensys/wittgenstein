package net.consensys.wittgenstein.core.messages;

import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.Node;

public final class ConditionalTask<TN extends Node> extends Task<TN> {
  /** Starts if this condition is met. */
  public final Network.Condition startIf;

  /** Will start again when the task is finished if this condition is met. */
  public final Network.Condition repeatIf;

  /** Time before next start. */
  public final int duration;

  /** Will start after this time. */
  public int minStartTime;

  /** The node on which the task is executed. The task won't be executed if the node is down */
  public final TN from;

  public ConditionalTask(
      Network.Condition startIf,
      Network.Condition repeatIf,
      Runnable r,
      int minStartTime,
      TN from,
      int duration) {
    super(r);
    this.startIf = startIf;
    this.repeatIf = repeatIf;
    this.from = from;
    this.duration = duration;
    this.minStartTime = minStartTime;
  }
}
