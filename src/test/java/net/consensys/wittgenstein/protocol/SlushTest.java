package net.consensys.wittgenstein.protocol;

import org.junit.Assert;
import org.junit.Test;

public class SlushTest {

  @Test
  public void testSimple() {
    Slush p = new Slush(100, 5, 7, 4.0 / 7.0);
    p.init();
    p.network().run(10);

    Assert.assertEquals(100, p.network().allNodes.size());
    int uniqueColor = p.network().getNodeById(0).myColor;
    for (Slush.SlushNode n : p.network().allNodes) {
      Assert.assertEquals(uniqueColor, n.myColor);
    }
  }

  /**
   * Test that two runs gives exactly the same result.
   */
  @Test
  public void testCopy() {
    Slush p1 = new Slush(60, 5, 7, 4.0 / 7.0);
    Slush p2 = p1.copy();
    p1.init();
    p1.network().runMs(200);
    p2.init();
    p2.network().runMs(200);

    for (Slush.SlushNode n1 : p1.network().allNodes) {
      Slush.SlushNode n2 = p2.network().getNodeById(n1.nodeId);
      Assert.assertNotNull(n2);
      Assert.assertEquals(n1.myColor, n2.myColor);
      Assert.assertEquals(n1.myQueryNonce, n2.myQueryNonce);
      Assert.assertEquals(n1.round, n2.round);
    }
  }
}
