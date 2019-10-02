package net.consensys.wittgenstein.protocols;

import net.consensys.wittgenstein.core.NetworkLatency;
import net.consensys.wittgenstein.core.RegistryNodeBuilders;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class GSFSignatureTest {
  private String nl = NetworkLatency.NetworkLatencyByDistanceWJitter.class.getSimpleName();
  private String nb = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.RANDOM, true, 0);
  private GSFSignature p =
      new GSFSignature(new GSFSignature.GSFSignatureParameters(32, 1, 3, 20, 10, 10, 0, nb, nl));
  private GSFSignature.GSFNode n0;

  @Before
  public void before() {
    p.init();
    n0 = p.network.getNodeById(0);
  }

  @Test
  public void testInit() {
    Assert.assertEquals(6, n0.levels.size());

    Assert.assertEquals(0, n0.levels.get(0).peers.size());
    Assert.assertEquals(1, n0.levels.get(1).peers.size());
    Assert.assertEquals(2, n0.levels.get(2).peers.size());
    Assert.assertEquals(4, n0.levels.get(3).peers.size());
    Assert.assertEquals(8, n0.levels.get(4).peers.size());
    Assert.assertEquals(16, n0.levels.get(5).peers.size());

    Assert.assertEquals(1, n0.levels.get(1).peers.get(0).nodeId);

    Assert.assertEquals(1, n0.levels.get(0).verifiedSignatures.cardinality());
    Assert.assertEquals(0, n0.levels.get(1).verifiedSignatures.cardinality());
    Assert.assertEquals(0, n0.levels.get(2).verifiedSignatures.cardinality());
    Assert.assertEquals(0, n0.levels.get(3).verifiedSignatures.cardinality());
    Assert.assertEquals(0, n0.levels.get(4).verifiedSignatures.cardinality());
    Assert.assertEquals(0, n0.levels.get(5).verifiedSignatures.cardinality());
  }

  @Test
  public void testMaxSigInLevel() {
    Assert.assertEquals(1, n0.levels.get(0).expectedSigs());
    Assert.assertEquals(1, n0.levels.get(1).expectedSigs()); // send 0, wait for 1
    Assert.assertEquals(2, n0.levels.get(2).expectedSigs()); // send 0 1, wait for 2 3
    Assert.assertEquals(4, n0.levels.get(3).expectedSigs()); // send 0 1 2 3, wait for 4 5 6 7
    Assert.assertEquals(8, n0.levels.get(4).expectedSigs());
    Assert.assertEquals(16, n0.levels.get(5).expectedSigs());
  }

  @Test
  public void testSend() {
    p.network.runMs(1);
    // Each node has sent its signature to its peer.
    Assert.assertEquals(64, p.network.msgs.size());
  }

  // TODO
  // @Test
  public void testNonPowerTwoNodeCount() {
    GSFSignature p =
        new GSFSignature(
            new GSFSignature.GSFSignatureParameters(31, 1, 3, 20, 10, 10, 0.1, nb, nl));
    p.init();
    GSFSignature.GSFNode n3 = p.network.getNodeById(3);
    Assert.assertEquals(6, n3.levels.size());
    Assert.assertEquals(16, n3.levels.get(5).expectedSigs());
  }

  @Test
  public void testDeadNodes() {
    GSFSignature p =
        new GSFSignature(
            new GSFSignature.GSFSignatureParameters(32, 0.8, 3, 20, 10, 10, 0.1, nb, nl));
    p.init();
    long dead = p.network.allNodes.stream().filter(n -> n.isDown()).count();
    Assert.assertEquals(3, dead);
  }

  @Test
  public void testGetLastFinishedLevel() {
    Assert.assertEquals(1, n0.getLastFinishedLevel().cardinality());
    n0.levels.get(1).verifiedSignatures.or(n0.levels.get(1).waitedSigs);
    Assert.assertEquals(2, n0.getLastFinishedLevel().cardinality());

    n0.levels.get(2).verifiedSignatures.set(2);
    Assert.assertEquals(2, n0.getLastFinishedLevel().cardinality());

    n0.levels.get(2).verifiedSignatures.set(3);
    Assert.assertEquals(4, n0.getLastFinishedLevel().cardinality());
  }

  @Test
  public void testSimpleRun() {
    GSFSignature p =
        new GSFSignature(new GSFSignature.GSFSignatureParameters(32, 1, 3, 20, 10, 10, 0, nb, nl));
    p.init();
    p.network.run(10);
    Assert.assertEquals(32, p.network().allNodes.size());
    for (GSFSignature.GSFNode n : p.network.allNodes) {
      Assert.assertEquals(32, n.verifiedSignatures.cardinality());
    }
  }

  @Test
  public void testSimpleThreshold() {
    GSFSignature p =
        new GSFSignature(
            new GSFSignature.GSFSignatureParameters(64, .50, 3, 20, 10, 10, .2, nb, nl));
    p.init();
    p.network.run(10);

    Assert.assertEquals(64, p.network().allNodes.size());
    for (GSFSignature.GSFNode n : p.network.allNodes) {
      if (n.isDown()) {
        Assert.assertEquals(1, n.verifiedSignatures.cardinality());
      } else {
        Assert.assertTrue(n.verifiedSignatures.cardinality() >= 32);
        Assert.assertTrue(n.verifiedSignatures.cardinality() <= 64);
      }
    }
  }

  @Test
  public void testCopy() {
    GSFSignature p1 =
        new GSFSignature(
            new GSFSignature.GSFSignatureParameters(128, .75, 6, 10, 5, 10, .2, nb, nl));
    GSFSignature p2 = p1.copy();
    p1.init();
    p2.init();

    while (p1.network.time < 2000) {
      p1.network().runMs(1);
      p2.network().runMs(1);
      Assert.assertEquals(p1.network.msgs.size(), p2.network.msgs.size());
      for (GSFSignature.GSFNode n1 : p1.network().allNodes) {
        GSFSignature.GSFNode n2 = p2.network().getNodeById(n1.nodeId);
        Assert.assertNotNull(n2);
        Assert.assertEquals(n1.doneAt, n2.doneAt);
        Assert.assertEquals(n1.verifiedSignatures, n2.verifiedSignatures);
        Assert.assertEquals(n1.toVerify.size(), n2.toVerify.size());
      }
    }
  }
}
