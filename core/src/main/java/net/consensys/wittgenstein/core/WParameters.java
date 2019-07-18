package net.consensys.wittgenstein.core;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import net.consensys.wittgenstein.core.utils.Strings;

/**
 * A value object containing all the parameters for a protocol. This will be serialized to/from a
 * json object, allowing to run a protocol from a distant system using http/json calls.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
public class WParameters {
  @Override
  public String toString() {
    return Strings.toString(this);
  }
}
