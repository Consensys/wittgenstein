package net.consensys.wittgenstein.protocols.handeleth2;

import java.util.Random;
import org.junit.Assert;
import org.junit.Test;

public class HandelEth2Test {

  @Test
  public void testTree() {
    HandelEth2Parameters params = new HandelEth2Parameters();
    HandelEth2 p = new HandelEth2(params);
    p.init();

    Random r = new Random();
    for (int i = 0; i < 100; i++) {
      HNode n1 = p.network().getNodeById(r.nextInt(params.nodeCount));
      HNode n2 = p.network().getNodeById(r.nextInt(params.nodeCount));
      if (n1 != n2) {
        int c1 = n1.communicationLevel(n2);
        Assert.assertEquals(c1, n2.communicationLevel(n1));

        Assert.assertTrue(n1.peersUpToLevel(c1).get(n2.nodeId));
        for (int l = 1; l < c1; l++) {
          Assert.assertFalse(n1.peersUpToLevel(l).get(n2.nodeId));
        }
      }
    }
  }

  // @Test
  public void testRun() {
    HandelEth2Parameters params = new HandelEth2Parameters();
    HandelEth2 p = new HandelEth2(params);
    p.init();
    HNode n = p.network().getNodeById(0);

    Assert.assertEquals(16, n.curWindowsSize);

    p.network().run(20);

    Assert.assertEquals(128, n.curWindowsSize);
    Assert.assertEquals(4, n.runningAggs.size());

    HNode.AggregationProcess ap = n.runningAggs.get(1001);
    Assert.assertNotNull(ap);
    for (HLevel hl : ap.levels) {
      Assert.assertTrue("" + hl, hl.isIncomingComplete());
    }
  }
}
