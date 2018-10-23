package net.consensys.wittgenstein.protocol;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class GSFSignatureTest {
  private GSFSignature p = new GSFSignature(32, 1, 3, 20, 10, 10, 0);
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
  //@Test
  public void testNonPowerTwoNodeCount() {
    GSFSignature p = new GSFSignature(31, 1, 3, 20, 10, 10, 0.1);
    p.init();
    GSFSignature.GSFNode n3 = p.network.getNodeById(3);
    Assert.assertEquals(6, n3.levels.size());
    Assert.assertEquals(16, n3.levels.get(5).expectedSigs());
  }

  @Test
  public void testDeadNodes() {
    GSFSignature p = new GSFSignature(32, 0.8, 3, 20, 10, 10, 0.1);
    p.init();
    long dead = p.network.allNodes.stream().filter(n -> n.down).count();
    Assert.assertEquals(3, dead);
  }

}
