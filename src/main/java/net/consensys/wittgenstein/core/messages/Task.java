package net.consensys.wittgenstein.core.messages;

import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.Node;

/**
 * Some protocols want some tasks to be executed at a given time
 */
public class Task<TN extends Node> extends Message<TN> {
  public final Runnable r;

  public Task(Runnable r) {
    this.r = r;
  }

  @Override
  public int size() {
    return 0;
  }

  @Override
  public void action(Network<TN> network, TN from, TN to) {
    r.run();
  }
}
