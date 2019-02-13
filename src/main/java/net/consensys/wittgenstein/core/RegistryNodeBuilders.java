package net.consensys.wittgenstein.core;

import java.util.HashMap;
import java.util.Map;

public class RegistryNodeBuilders {
  private Map<String, NodeBuilder> registry = new HashMap<>();

  public RegistryNodeBuilders() {
    registry.put(NodeBuilder.NodeBuilderWithRandomPosition.class.getSimpleName(),
        new NodeBuilder.NodeBuilderWithRandomPosition());
  }

  public NodeBuilder getByName(String name) {
    if (name == null) {
      name = NodeBuilder.NodeBuilderWithRandomPosition.class.getSimpleName();
    }

    NodeBuilder c = registry.get(name);
    if (c == null) {
      throw new IllegalArgumentException(name + " not in the registry (" + registry + ")");
    }
    return c.copy();
  }
}
