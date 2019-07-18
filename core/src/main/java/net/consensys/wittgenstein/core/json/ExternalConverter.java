package net.consensys.wittgenstein.core.json;

import com.fasterxml.jackson.databind.util.StdConverter;
import net.consensys.wittgenstein.core.External;

public class ExternalConverter extends StdConverter<External, String> {
  @Override
  public String convert(External value) {
    return value == null ? "false" : value.toString();
  }
}
