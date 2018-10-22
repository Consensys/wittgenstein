package net.consensys.wittgenstein.core;

public interface Protocol {

  Protocol copy(Protocol p);

  void init();

  Network network();
}
