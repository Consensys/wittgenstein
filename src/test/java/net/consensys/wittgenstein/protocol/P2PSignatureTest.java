package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.Node;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import java.util.BitSet;

public class P2PSignatureTest {
  private P2PSignature ps =
      new P2PSignature(100, 0, 60, 10, 2, 20, false, false, P2PSignature.SendSigsStrategy.dif, 4);
  private P2PSignature.P2PSigNode n1;
  private P2PSignature.P2PSigNode n2;

  @Before
  public void before() {
    ps.init();
    n1 = (P2PSignature.P2PSigNode) ps.network.getNodeById(1);
    n2 = (P2PSignature.P2PSigNode) ps.network.getNodeById(2);
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
    P2PSignature p1 =
        new P2PSignature(100, 0, 25, 10, 2, 5, false, true, P2PSignature.SendSigsStrategy.dif, 4);
    P2PSignature p2 =
        new P2PSignature(100, 0, 25, 10, 2, 5, false, true, P2PSignature.SendSigsStrategy.dif, 4);

    p1.init();
    p1.network.run(10);
    p2.init();
    p2.network.run(10);
    for (Node n : p1.network.allNodes) {
      Assert.assertEquals(((P2PSignature.P2PSigNode) n).doneAt,
          ((P2PSignature.P2PSigNode) p2.network.getNodeById(n.nodeId)).doneAt);
    }
  }

  @Test
  public void testSendSigs() {
    n1.peersState.put(n2.nodeId, new P2PSignature.State(n2));

    ps.network.msgs.clear();
    n1.sendSigs();
    Network.Message<?> mc = ps.network.msgs.peekFirstMessageContent();
    Assert.assertNotNull(mc);

    P2PSignature.SendSigs ss = (P2PSignature.SendSigs) mc;
    Assert.assertNotNull(ss);
    Assert.assertEquals(1, ss.sigs.cardinality());
    Assert.assertTrue(ss.sigs.get(n1.nodeId));
    Assert.assertTrue(n1.peersState.isEmpty());
  }

  @Test
  public void testCheckSigs() {
    BitSet sigs = new BitSet(ps.signingNodeCount);
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
    BitSet sigs = new BitSet(ps.signingNodeCount);
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

    Assert.assertEquals(3, ps.compressedSize(fromString(
        "0000 0000 0000 0000  0000 0000 0000 0000 1111 1111 1111 1111  1111 1111 1111 0000")));

    Assert.assertEquals(1, ps.compressedSize(fromString(
        "0000 0000 0000 0000  0000 0000 0000 0000 1111 1111 1111 1111  1111 1111 1111 1111 0000")));

    Assert.assertEquals(2, ps.compressedSize(fromString(
        "0000 0000 0000 0000  1111 1111 1111 1111 1111 1111 1111 1111  1111 1111 1111 1111 0000")));

    Assert.assertEquals(3,
        ps.compressedSize(fromString("1111 1111 1111 1111  1111 1111 1111 0000")));

    Assert.assertEquals(1, ps.compressedSize(fromString("1111 1111 0000")));
    Assert.assertEquals(3, ps.compressedSize(fromString("0001 1111 1111 0000")));
    Assert.assertEquals(3, ps.compressedSize(fromString("0001 1111 1111 1111"))); // we could optimize further & have 2

    Assert.assertEquals(2, ps.compressedSize(fromString("0000 1111 1111 1111  0000")));

    Assert.assertEquals(5, ps.compressedSize(fromString("1101 0111")));
    Assert.assertEquals(2, ps.compressedSize(fromString("1111 1110")));
    Assert.assertEquals(6, ps.compressedSize(fromString("0111 0111")));
    Assert.assertEquals(0, ps.compressedSize(fromString("0000 0000")));
    Assert.assertEquals(2, ps.compressedSize(fromString("1111 1111 1111")));
  }

  @Test
  public void testSanFerminSetsN1() {
    BitSet t1 = new BitSet(ps.signingNodeCount);
    t1.set(0);
    Assert.assertEquals(t1, n1.sanFerminPeers(1));

    BitSet t2 = new BitSet(ps.signingNodeCount);
    t2.set(0);
    t2.set(2, 4);
    Assert.assertEquals(t2, n1.sanFerminPeers(2));

    BitSet t3 = new BitSet(ps.signingNodeCount);
    t3.set(0);
    t3.set(2, 8);
    Assert.assertEquals(t3, n1.sanFerminPeers(3));
  }

  @Test
  public void testSanFerminSetsN24() {
    P2PSignature.P2PSigNode n24 = (P2PSignature.P2PSigNode) ps.network.getNodeById(24);

    BitSet t1 = new BitSet(ps.signingNodeCount);
    t1.set(25);
    Assert.assertEquals(t1, n24.sanFerminPeers(1));

    BitSet t2 = new BitSet(ps.signingNodeCount);
    t2.set(25, 28);
    Assert.assertEquals(t2, n24.sanFerminPeers(2));

    BitSet t3 = new BitSet(ps.signingNodeCount);
    t3.set(25, 32);
    Assert.assertEquals(t3, n24.sanFerminPeers(3));
  }

  @Test
  public void testSanFerminSetsN98() {
    P2PSignature.P2PSigNode n98 = (P2PSignature.P2PSigNode) ps.network.getNodeById(98);

    BitSet t1 = new BitSet(ps.signingNodeCount);
    t1.set(99);
    Assert.assertEquals(t1, n98.sanFerminPeers(1));

    BitSet t2 = new BitSet(ps.signingNodeCount);
    t2.set(99);
    t2.set(96, 98);
    Assert.assertEquals(t2, n98.sanFerminPeers(2));

    BitSet t3 = new BitSet(ps.signingNodeCount);
    t3.set(99);
    t3.set(96, 98);
    Assert.assertEquals(t3, n98.sanFerminPeers(3));
  }

}
