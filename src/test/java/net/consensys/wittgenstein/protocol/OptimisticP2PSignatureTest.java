package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.Node;
import org.junit.Assert;
import org.junit.Test;

public class OptimisticP2PSignatureTest {

  @Test
  public void testSimple() {
    int nCt = 100;
    OptimisticP2PSignature p = new OptimisticP2PSignature(nCt, nCt / 2 + 1, 13, 3);
    p.init();
    p.network().run(10);

    Assert.assertEquals(nCt, p.network().allNodes.size());
    for (Node nc : p.network().allNodes) {
      OptimisticP2PSignature.P2PSigNode n = (OptimisticP2PSignature.P2PSigNode) nc;
      Assert.assertFalse(n.down);
      Assert.assertTrue(n.doneAt > 0);
      Assert.assertTrue(n.done);
      Assert.assertTrue(n.verifiedSignatures.cardinality() > nCt / 2);
    }
  }

  /**
   * Test that two runs gives exactly the same result.
   */
  @Test
  public void testCopy() {
    OptimisticP2PSignature p1 = new OptimisticP2PSignature(200, 160, 10, 2);
    OptimisticP2PSignature p2 = p1.copy();
    p1.init();
    p1.network().runMs(200);
    p2.init();
    p2.network().runMs(200);

    for (Node nc1 : p1.network().allNodes) {
      OptimisticP2PSignature.P2PSigNode n1 = (OptimisticP2PSignature.P2PSigNode) nc1;
      OptimisticP2PSignature.P2PSigNode n2 =
          (OptimisticP2PSignature.P2PSigNode) p2.network().getNodeById(n1.nodeId);
      Assert.assertNotNull(n2);
      Assert.assertEquals(n1.done, n2.done);
      Assert.assertEquals(n1.doneAt, n2.doneAt);
    }
  }
}
