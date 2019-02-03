package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.Node;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class DfinityTest {
  private final Dfinity dfinity = new Dfinity(10, 10, 10, 1, 1, 0);

  @Before
  public void before() {
    dfinity.network.removeNetworkLatency();
    dfinity.init();
  }

  @Test
  public void testRun() {
    dfinity.network.run(11);
    Assert.assertEquals(3, dfinity.network.observer.head.height);
  }

  //@Test
  public void testCopy() {
    Dfinity p1 = new Dfinity(10, 50, 25, 100, 1, 5);
    Dfinity p2 = p1.copy();
    p1.init();
    p1.network().runMs(20000);
    p2.init();
    p2.network().runMs(20000);

    for (Node nn1 : p1.network().allNodes) {
      Dfinity.DfinityNode n1 = (Dfinity.DfinityNode) nn1;
      Dfinity.DfinityNode n2 = (Dfinity.DfinityNode) p2.network().getNodeById(n1.nodeId);
      Assert.assertNotNull(n2);
      Assert.assertEquals(n1.doneAt, n2.doneAt);
      Assert.assertEquals(n1.down, n2.down);
      Assert.assertEquals(n1.head, n2.head);
      Assert.assertEquals(n1.committeeMajorityHeight, n2.committeeMajorityHeight);
      Assert.assertEquals(n1.committeeMajorityBlocks, n2.committeeMajorityBlocks);
      Assert.assertEquals(n1.lastRandomBeacon, n2.lastRandomBeacon);
    }
  }

}
