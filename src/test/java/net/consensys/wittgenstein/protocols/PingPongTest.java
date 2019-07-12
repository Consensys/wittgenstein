package net.consensys.wittgenstein.protocols;

import org.junit.Assert;
import org.junit.Test;

public class PingPongTest {

  @Test
  public void testSimple() {
    PingPong p = new PingPong(new PingPong.PingPongParameters());
    p.init();
    p.network().run(10);

    Assert.assertEquals(p.params.nodeCt, p.network().allNodes.size());
    for (PingPong.PingPongNode n : p.network().allNodes) {
      Assert.assertFalse(n.isDown());
      Assert.assertTrue(n.pong == 0 || n.pong == 1000);
    }
  }

  /** Test that two runs gives exactly the same result. */
  @Test
  public void testCopy() {
    PingPong p1 = new PingPong(new PingPong.PingPongParameters());
    PingPong p2 = p1.copy();
    p1.init();
    p1.network().runMs(200);
    p2.init();
    p2.network().runMs(200);

    for (PingPong.PingPongNode n1 : p1.network().allNodes) {
      PingPong.PingPongNode n2 = p2.network().getNodeById(n1.nodeId);
      Assert.assertNotNull(n2);
      Assert.assertEquals(n1.pong, n2.pong);
    }
  }
}
