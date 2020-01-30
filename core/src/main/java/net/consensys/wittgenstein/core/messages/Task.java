package net.consensys.wittgenstein.core.messages;

import com.fasterxml.jackson.annotation.JsonIgnore;
import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.Node;

/** Some protocols want some tasks to be executed at a given time */
public class Task<TN extends Node> extends Message<TN> {
  @JsonIgnore public final Runnable r;

  public Task(Runnable r) {
    assert r != null;
    this.r = r;
  }

  // For json
  public Task() {
    r = null;
  }

  @Override
  public int size() {
    return 0;
  }

  @Override
  public void action(Network<TN> network, TN from, TN to) {
    assert r != null;
    r.run();
  }
}
