package net.consensys.wittgenstein.protocols;

import net.consensys.wittgenstein.core.NetworkLatency;
import net.consensys.wittgenstein.core.RegistryNodeBuilders;
import org.junit.Assert;
import org.junit.Test;

public class HandelTest {
  private String nl = NetworkLatency.NetworkLatencyByDistance.class.getSimpleName();
  private String nb = RegistryNodeBuilders.RANDOM_POSITION;

  @Test
  public void testCopy() {
    Handel p1 =
        new Handel(new Handel.HandelParameters(64, 60, 6, 10, 5, 10, 2, nb, nl, 100, false, false, ""));
    Handel p2 = p1.copy();
    p1.init();
    p2.init();

    while (p1.network().time < 2000) {
      p1.network().runMs(1);
      p2.network().runMs(1);
      Assert.assertEquals(p1.network().msgs.size(), p2.network().msgs.size());
      for (Handel.HNode n1 : p1.network().allNodes) {
        Handel.HNode n2 = p2.network().getNodeById(n1.nodeId);
        Assert.assertNotNull(n2);
        Assert.assertEquals(n1.doneAt, n2.doneAt);
        Assert.assertEquals(n1.totalSigSize(), n2.totalSigSize());
      }
    }
  }
}
