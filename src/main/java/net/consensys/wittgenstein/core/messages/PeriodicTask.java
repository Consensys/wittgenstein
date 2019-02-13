package net.consensys.wittgenstein.core.messages;

import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.Node;

/**
 * Some protocols want some tasks to be executed periodically
 */
public class PeriodicTask<TN extends Node> extends Task<TN> {
  public final int period;
  public final TN sender;
  public final Network.Condition continuationCondition;

  public PeriodicTask(Runnable r, TN fromNode, int period, Network.Condition condition) {
    super(r);
    this.period = period;
    this.sender = fromNode;
    this.continuationCondition = condition;
  }

  public PeriodicTask(Runnable r, TN fromNode, int period) {
    this(r, fromNode, period, () -> true);
  }

  @Override
  public void action(Network<TN> network, TN from, TN to) {
    r.run();
    if (continuationCondition.check()) {
      network.sendArriveAt(this, network.time + period, sender, sender);
    }
  }
}
