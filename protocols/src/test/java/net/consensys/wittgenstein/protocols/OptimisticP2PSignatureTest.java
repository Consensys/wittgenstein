package net.consensys.wittgenstein.protocols;

import net.consensys.wittgenstein.core.NetworkLatency;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.RegistryNodeBuilders;
import org.junit.Assert;
import org.junit.Test;

public class OptimisticP2PSignatureTest {
  private String nl = NetworkLatency.NetworkLatencyByDistanceWJitter.class.getSimpleName();
  private String nb = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.RANDOM, true, 0);

  @Test
  public void testSimple() {
    int nCt = 100;
    OptimisticP2PSignature p =
        new OptimisticP2PSignature(
            new OptimisticP2PSignature.OptimisticP2PSignatureParameters(
                nCt, nCt / 2 + 1, 13, 3, nb, nl));
    p.init();
    p.network().run(10);

    Assert.assertEquals(nCt, p.network().allNodes.size());
    for (Node nc : p.network().allNodes) {
      OptimisticP2PSignature.P2PSigNode n = (OptimisticP2PSignature.P2PSigNode) nc;
      Assert.assertFalse(n.isDown());
      Assert.assertTrue(n.doneAt > 0);
      Assert.assertTrue(n.done);
      Assert.assertTrue(n.verifiedSignatures.cardinality() > nCt / 2);
    }
  }

  @Test
  public void testCopy() {
    OptimisticP2PSignature p1 =
        new OptimisticP2PSignature(
            new OptimisticP2PSignature.OptimisticP2PSignatureParameters(200, 160, 10, 2, nb, nl));
    OptimisticP2PSignature p2 = p1.copy();
    p1.init();
    p1.network().runMs(200);
    p2.init();
    p2.network().runMs(200);

    for (OptimisticP2PSignature.P2PSigNode n1 : p1.network().allNodes) {
      OptimisticP2PSignature.P2PSigNode n2 = p2.network().getNodeById(n1.nodeId);
      Assert.assertNotNull(n2);
      Assert.assertEquals(n1.done, n2.done);
      Assert.assertEquals(n1.doneAt, n2.doneAt);
    }
  }
}
