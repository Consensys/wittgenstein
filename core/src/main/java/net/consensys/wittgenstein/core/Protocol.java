package net.consensys.wittgenstein.core;

/**
 * The interface to implement when you implement a protocol. These interfaces are mainly used in the
 * context of a scenario, typically executing multiple runs to extract min/max/avg behavior on some
 * network conditions. A protocol must have a public constructor taking WParameters as the unique
 * parameter.
 */
public interface Protocol {

  /** @return the network used by this protocol */
  Network<?> network();

  /**
   * Copy the protocol, in order to do another run. Init will be called again. This should recreate
   * the network.
   */
  Protocol copy();

  /** Initialize, ig. create all the nodes, byzantine or not & so on. */
  void init();
}
