package net.consensys.wittgenstein.protocol;

import org.junit.Assert;
import org.junit.Test;

public class SnowflakeTest {

  @Test
  public void testSimple() {
    Snowflake p = new Snowflake(100, 5, 7, 4.0 / 7.0, 3);
    p.init();
    p.network().run(10);

    Assert.assertEquals(100, p.network().allNodes.size());
    int uniqueColor = p.network().getNodeById(0).myColor;
    for (Snowflake.SnowflakeNode n : p.network().allNodes) {
      Assert.assertEquals(uniqueColor, n.myColor);
    }
  }

  @Test
  public void testCopy() {
    Snowflake p1 = new Snowflake(60, 5, 7, 4.0 / 7.0, 3);
    Snowflake p2 = p1.copy();
    p1.init();
    p1.network().runMs(200);
    p2.init();
    p2.network().runMs(200);

    for (Snowflake.SnowflakeNode n1 : p1.network().allNodes) {
      Snowflake.SnowflakeNode n2 = p2.network().getNodeById(n1.nodeId);
      Assert.assertNotNull(n2);
      Assert.assertEquals(n1.myColor, n2.myColor);
      Assert.assertEquals(n1.myQueryNonce, n2.myQueryNonce);
      Assert.assertEquals(n1.cnt, n2.cnt);
    }
  }

  @Test
  public void testPlay() {
    Snowflake p1 = new Snowflake(100, 5, 7, 4.0 / 7.0, 3);
    p1.play();
  }

}
