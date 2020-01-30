package net.consensys.wittgenstein.protocols;

import java.util.BitSet;
import net.consensys.wittgenstein.core.NetworkLatency;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.RegistryNodeBuilders;
import net.consensys.wittgenstein.core.RunMultipleTimes;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class P2PHandelTest {
  private String nl = NetworkLatency.NetworkLatencyByDistanceWJitter.class.getSimpleName();
  private String nb = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.RANDOM, true, 0);
  private P2PHandel ps = new P2PHandel(P2PHandelScenarios.defaultParams(32, 0.0, 4, null, null));
  private P2PHandel.P2PHandelNode n1;
  private P2PHandel.P2PHandelNode n2;

  @Before
  public void before() {
    ps.init();
    n1 = ps.network.getNodeById(1);
    n2 = ps.network.getNodeById(2);
  }

  @Test
  public void testSetup() {
    Assert.assertEquals(1, n1.verifiedSignatures.cardinality());
    Assert.assertTrue(n1.verifiedSignatures.get(n1.nodeId));
    Assert.assertTrue(n1.peers.size() >= 3);
    for (Node n : n1.peers) {
      Assert.assertNotEquals(n, ps);
    }
  }

  @Test
  public void testRepeatability() {
    P2PHandel.P2PHandelParameters params =
        new P2PHandel.P2PHandelParameters(
            100, 0, 25, 10, 2, 5, false, P2PHandel.SendSigsStrategy.dif, true, nb, nl);

    P2PHandel p1 = new P2PHandel(params);
    P2PHandel p2 = new P2PHandel(params);

    p1.init();
    p1.network.run(10);
    p2.init();
    p2.network.run(10);
    for (Node n : p1.network.allNodes) {
      Assert.assertEquals(n.getDoneAt(), p2.network.getNodeById(n.nodeId).getDoneAt());
    }
  }

  @Test
  public void testSimpleRunWithoutState() {
    P2PHandel.P2PHandelParameters params =
        new P2PHandel.P2PHandelParameters(
            64, 0, 60, 3, 2, 5, true, P2PHandel.SendSigsStrategy.all, false, nb, nl);
    P2PHandel p1 = new P2PHandel(params);

    p1.init();
    for (; RunMultipleTimes.contUntilDone().test(p1) && p1.network().time < 20000; ) {
      p1.network().runMs(1000);
    }
    Assert.assertFalse(RunMultipleTimes.contUntilDone().test(p1));
  }

  @Test
  public void testSimpleRunWithState() {
    P2PHandel.P2PHandelParameters params =
        new P2PHandel.P2PHandelParameters(
            20, 0, 20, 3, 2, 50, true, P2PHandel.SendSigsStrategy.cmp_diff, true, nb, nl);
    P2PHandel p1 = new P2PHandel(params);

    p1.init();
    for (; RunMultipleTimes.contUntilDone().test(p1) && p1.network().time < 20000; ) {
      p1.network().runMs(1000);
    }
    Assert.assertFalse(RunMultipleTimes.contUntilDone().test(p1));
  }

  @Test
  public void testCheckSigs() {
    BitSet sigs = new BitSet(ps.params.signingNodeCount);
    sigs.set(n1.nodeId);
    sigs.set(0);
    n1.toVerify.add(sigs);

    ps.network.msgs.clear();
    n1.checkSigs();

    Assert.assertTrue(n1.toVerify.isEmpty());
    Assert.assertEquals(1, ps.network.msgs.size());
  }

  @Test
  public void testSigUpdate() {
    BitSet sigs = new BitSet(ps.params.signingNodeCount);
    sigs.set(n1.nodeId);
    sigs.set(0);

    n1.updateVerifiedSignatures(sigs);
    Assert.assertEquals(2, n1.verifiedSignatures.cardinality());
  }

  private static BitSet fromString(String binary) {
    binary = binary.replaceAll(" ", "");
    BitSet bitset = new BitSet(binary.length());
    for (int i = 0; i < binary.length(); i++) {
      if (binary.charAt(i) == '1') {
        bitset.set(i);
      }
    }
    return bitset;
  }

  @Test
  public void testCompressedSize() {
    Assert.assertEquals(1, ps.compressedSize(fromString("1111")));
    Assert.assertEquals(1, ps.compressedSize(fromString("1111 1111")));
    Assert.assertEquals(1, ps.compressedSize(fromString("1111 1111 1111 1111")));

    Assert.assertEquals(
        3,
        ps.compressedSize(
            fromString(
                "0000 0000 0000 0000  0000 0000 0000 0000 1111 1111 1111 1111  1111 1111 1111 0000")));

    Assert.assertEquals(
        1,
        ps.compressedSize(
            fromString(
                "0000 0000 0000 0000  0000 0000 0000 0000 1111 1111 1111 1111  1111 1111 1111 1111 0000")));

    Assert.assertEquals(
        2,
        ps.compressedSize(
            fromString(
                "0000 0000 0000 0000  1111 1111 1111 1111 1111 1111 1111 1111  1111 1111 1111 1111 0000")));

    Assert.assertEquals(
        3, ps.compressedSize(fromString("1111 1111 1111 1111  1111 1111 1111 0000")));

    Assert.assertEquals(1, ps.compressedSize(fromString("1111 1111 0000")));
    Assert.assertEquals(3, ps.compressedSize(fromString("0001 1111 1111 0000")));
    Assert.assertEquals(
        3,
        ps.compressedSize(fromString("0001 1111 1111 1111"))); // we could optimize further & have 2

    Assert.assertEquals(2, ps.compressedSize(fromString("0000 1111 1111 1111  0000")));

    Assert.assertEquals(4, ps.compressedSize(fromString("1101 0111")));
    Assert.assertEquals(3, ps.compressedSize(fromString("1111 1110")));
    Assert.assertEquals(4, ps.compressedSize(fromString("0111 0111")));
    Assert.assertEquals(0, ps.compressedSize(fromString("0000 0000")));
    Assert.assertEquals(2, ps.compressedSize(fromString("1111 1111 1111")));
  }

  @Test
  public void testCopy() {
    P2PHandel p1 =
        new P2PHandel(
            new P2PHandel.P2PHandelParameters(
                500, 2, 60, 10, 2, 20, false, P2PHandel.SendSigsStrategy.dif, true, nb, nl));

    P2PHandel p2 = p1.copy();
    p1.init();
    p1.network().runMs(500);
    p2.init();
    p2.network().runMs(500);

    for (P2PHandel.P2PHandelNode n1 : p1.network().allNodes) {
      P2PHandel.P2PHandelNode n2 = p2.network().getNodeById(n1.nodeId);
      Assert.assertNotNull(n2);
      Assert.assertEquals(n1.doneAt, n2.doneAt);
      Assert.assertEquals(n1.verifiedSignatures, n2.verifiedSignatures);
      Assert.assertEquals(n1.toVerify, n2.toVerify);
    }
  }
}
