package net.consensys.wittgenstein.core.messages;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.json.NodeConverter;

/** Some protocols want some tasks to be executed periodically */
@SuppressWarnings("WeakerAccess")
public class PeriodicTask<TN extends Node> extends Task<TN> {
  public final int period;

  @JsonSerialize(converter = NodeConverter.class)
  public final TN sender;

  @JsonIgnore public final Network.Condition continuationCondition;

  public PeriodicTask(Runnable r, TN fromNode, int period, Network.Condition condition) {
    super(r);
    this.period = period;
    this.sender = fromNode;
    this.continuationCondition = condition;
  }

  // For json
  @SuppressWarnings("unused")
  public PeriodicTask() {
    super(null);
    period = -1;
    sender = null;
    continuationCondition = null;
  }

  public PeriodicTask(Runnable r, TN fromNode, int period) {
    this(r, fromNode, period, () -> true);
  }

  @Override
  public void action(Network<TN> network, TN from, TN to) {
    assert r != null;
    assert continuationCondition != null;
    r.run();
    if (continuationCondition.check()) {
      network.sendArriveAt(this, network.time + period, sender, sender);
    }
  }
}
