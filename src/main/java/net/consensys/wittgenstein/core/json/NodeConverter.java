package net.consensys.wittgenstein.core.json;

import com.fasterxml.jackson.databind.util.StdConverter;
import net.consensys.wittgenstein.core.Node;

public class NodeConverter extends StdConverter<Node, Integer> {
  @Override
  public Integer convert(Node ns) {
    return ns.nodeId;
  }
}
