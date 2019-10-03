package net.consensys.wittgenstein.protocols;

import net.consensys.wittgenstein.core.NetworkLatency;
import net.consensys.wittgenstein.core.RegistryNodeBuilders;
import org.junit.Assert;
import org.junit.Test;

public class SnowflakeTest {
  private final String nb =
      RegistryNodeBuilders.name(RegistryNodeBuilders.Location.RANDOM, true, 0);
  private final String nl = NetworkLatency.NetworkLatencyByDistanceWJitter.class.getSimpleName();

  @Test
  public void testSimple() {
    Snowflake p = new Snowflake(new Snowflake.SnowflakeParameters(100, 5, 7, 4.0 / 7.0, 3, nb, nl));
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
    Snowflake p1 = new Snowflake(new Snowflake.SnowflakeParameters(60, 5, 7, 4.0 / 7.0, 3, nb, nl));
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
    Snowflake p1 =
        new Snowflake(new Snowflake.SnowflakeParameters(100, 5, 7, 4.0 / 7.0, 3, nb, nl));
    p1.play();
  }
}
