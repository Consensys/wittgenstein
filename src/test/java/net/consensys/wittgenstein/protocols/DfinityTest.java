package net.consensys.wittgenstein.protocols;

import net.consensys.wittgenstein.core.NetworkLatency;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class DfinityTest {
  private final Dfinity dfinity = new Dfinity(10, 10, 10, 1, 1, 0);

  @Before
  public void before() {
    dfinity.network.networkLatency = new NetworkLatency.NetworkNoLatency();
    dfinity.init();
  }

  @Test
  public void testRun() {
    dfinity.network.run(11);
    Assert.assertEquals(3, dfinity.network.observer.head.height);
  }

  //TODO @Test
  public void testCopy() {
    Dfinity p1 = new Dfinity(10, 50, 25, 100, 1, 5);
    Dfinity p2 = p1.copy();
    p1.init();
    p2.init();

    while (p1.network.time < 20000) {
      p1.network().runMs(1);
      p2.network().runMs(1);
      Assert.assertEquals(p1.network.msgs.size(), p2.network.msgs.size());
      for (Dfinity.DfinityNode n1 : p1.network().allNodes) {
        Dfinity.DfinityNode n2 = p2.network().getNodeById(n1.nodeId);
        Assert.assertNotNull(n2);
        Assert.assertEquals(n1.down, n2.down);
        Assert.assertEquals(n1.head.proposalTime, n2.head.proposalTime);
        Assert.assertEquals(n1.committeeMajorityHeight, n2.committeeMajorityHeight);
        Assert.assertEquals("" + n1, n1.committeeMajorityBlocks, n2.committeeMajorityBlocks);
        Assert.assertEquals(n1.lastRandomBeacon, n2.lastRandomBeacon);
      }
    }
  }
}
