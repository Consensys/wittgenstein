package net.consensys.wittgenstein.core;

import org.junit.Assert;
import org.junit.Test;
import java.util.Random;

public class NodeBuilderTest {

  @Test
  public void testRandomPos() {
    NodeBuilder nb = new NodeBuilder.NodeBuilderWithRandomPosition();
    Assert.assertEquals(nb.getX(new Random(0)), nb.getX(new Random(0)));
  }
}
