package net.consensys.wittgenstein.core;

import org.junit.Assert;
import org.junit.Test;

public class NodeBuilderTest {

  @Test
  public void testRandomPos() {
    NodeBuilder nb = new NodeBuilder.NodeBuilderWithRandomPosition();

    Assert.assertEquals(nb.getX(100), nb.getX(100));
    Assert.assertEquals(nb.getY(100), nb.getY(100));

    Assert.assertTrue(nb.getY(0) >= 0);
    Assert.assertTrue(nb.getY(Integer.MAX_VALUE) >= 0);

    Assert.assertTrue(nb.getY(2077261824) >= 0);
  }
}
