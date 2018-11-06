package net.consensys.wittgenstein.core;

public interface Protocol {

  Protocol copy();

  void init();

  Network<?> network();
}
