package net.consensys.wittgenstein.server;

import net.consensys.wittgenstein.core.utils.Strings;

/**
 * A value object containing all the parameters for a protocol. This will be serialized to/from a
 * json object, allowing to run a protocol from a distant system using http/json calls.
 */
public class WParameter {
  @Override
  public String toString() {
    return Strings.toString(this);
  }
}
