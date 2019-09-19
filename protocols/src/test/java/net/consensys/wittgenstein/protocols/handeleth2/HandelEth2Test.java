package net.consensys.wittgenstein.protocols.handeleth2;

import java.util.BitSet;
import java.util.Collections;
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

  @Test
  public void testMerge() {
    HandelEth2Parameters params = new HandelEth2Parameters(4, 10, 0, 10, 0, null, null, 0);
    HandelEth2 p = new HandelEth2(params);
    p.init();
    HNode n0 = p.network().getNodeById(0);
    HNode n1 = p.network().getNodeById(1);

    int base = n0.height + 1;
    final int H = 5;
    Attestation a0 = new Attestation(base, H, n0.nodeId);
    Attestation a1 = new Attestation(base, H, n1.nodeId);
    n0.startNewAggregation(a0);
    n1.startNewAggregation(a1);

    Assert.assertEquals(base, n0.height);
    Assert.assertEquals(1, n0.runningAggs.size());

    HNode.AggregationProcess ap1 = n1.runningAggs.get(base);
    HNode.AggregationProcess ap0 = n0.runningAggs.get(base);
    Assert.assertNotNull(ap1);
    Assert.assertNotNull(ap0);
    ap1.updateAllOutgoing();

    HLevel h11 = ap1.levels.get(1);
    Assert.assertEquals(1, h11.peersCount);
    Assert.assertTrue(h11.isOpen(0));
    Assert.assertFalse(h11.isIncomingComplete());
    Assert.assertTrue(h11.isOutgoingComplete());
    Assert.assertEquals(1, h11.outgoingCardinality);
    Assert.assertEquals(0, h11.incomingCardinality);
    Assert.assertEquals(1, h11.outgoing.size());

    HLevel h12 = ap1.levels.get(2);
    Assert.assertEquals(2, h12.peersCount);
    Assert.assertTrue(h12.isOpen(0));
    Assert.assertFalse(h12.isIncomingComplete());
    Assert.assertFalse(h12.isOutgoingComplete());
    Assert.assertEquals(1, h12.outgoingCardinality);
    Assert.assertEquals(0, h12.incomingCardinality);
    Assert.assertEquals(1, h12.outgoing.size());

    SendAggregation sa = new SendAggregation(1, a1.hash, false, a1);

    HLevel h01 = ap0.levels.get(1);
    Assert.assertTrue(h01.toVerifyAgg.isEmpty());
    n0.onNewAgg(n1, sa);
    Assert.assertEquals(1, h01.toVerifyAgg.size());

    AggToVerify atv = h01.bestToVerify(10, n0.blacklist);
    Assert.assertNotNull(atv);
    Assert.assertEquals(base, atv.height);
    Assert.assertEquals(n1.nodeId, atv.from);
    Assert.assertEquals(a1.hash, atv.ownHash);
    Assert.assertEquals(1, atv.attestations.size());

    n0.verify();
    Assert.assertEquals(ap0, n0.lastVerified);
    Assert.assertFalse(h01.isIncomingComplete());
    ap0.updateVerifiedSignatures(atv);
    ap0.updateAllOutgoing();

    Assert.assertEquals(1, h01.peersCount);
    Assert.assertTrue(h01.isOpen(0));
    Assert.assertTrue(h01.isIncomingComplete());
    Assert.assertTrue(h01.isOutgoingComplete());
    Assert.assertEquals(1, h01.outgoingCardinality);
    Assert.assertEquals(1, h01.incomingCardinality);
    Assert.assertEquals(1, h01.outgoing.size());

    HLevel h02 = ap0.levels.get(2);
    Assert.assertEquals(2, h02.peersCount);
    Assert.assertTrue(h02.isOpen(0));
    Assert.assertFalse(h02.isIncomingComplete());
    Assert.assertTrue(h02.isOutgoingComplete());
    Assert.assertEquals(2, h02.outgoingCardinality);
    Assert.assertEquals(0, h02.incomingCardinality);
    Assert.assertEquals(1, h02.outgoing.size());
    Assert.assertTrue(h02.outgoing.get(H).who.get(n0.nodeId));
    Assert.assertTrue(h02.outgoing.get(H).who.get(n1.nodeId));
    Assert.assertEquals(2, h02.outgoing.get(H).who.cardinality());

    AggToVerify atvN = h01.bestToVerify(10, n0.blacklist);
    Assert.assertNull(atvN);
    Assert.assertTrue(h01.toVerifyAgg.isEmpty());
  }

  @Test
  public void testRunSimple() {
    HandelEth2Parameters params = new HandelEth2Parameters(64, 10, 100, 40, 0, null, null, 0);
    HandelEth2 p = new HandelEth2(params);
    p.init();
    HNode n = p.network().getNodeById(0);

    Assert.assertEquals(16, n.curWindowsSize);

    p.network().runMs(HandelEth2Parameters.PERIOD_TIME - 500);

    Assert.assertEquals(128, n.curWindowsSize);
    Assert.assertEquals(1, n.runningAggs.size());

    HNode.AggregationProcess ap = n.runningAggs.get(1001);
    Assert.assertNotNull(ap);

    for (HLevel hl : ap.levels) {
      Assert.assertTrue("n0, " + hl, hl.isIncomingComplete());
    }
  }

  @Test
  public void testRun() {
    HandelEth2Parameters params = new HandelEth2Parameters(64, 10, 100, 40, 0, null, null, 0);
    HandelEth2 p = new HandelEth2(params);
    p.init();
    HNode n = p.network().getNodeById(0);

    p.network().runMs(HandelEth2Parameters.PERIOD_AGG_TIME * 10);

    Assert.assertEquals(3, n.runningAggs.size());

    int minRunning = Collections.min(n.runningAggs.keySet());
    HNode.AggregationProcess ap = n.runningAggs.get(minRunning);
    Assert.assertNotNull(ap);

    for (HLevel hl : ap.levels) {
      Assert.assertTrue("n0, " + hl, hl.isIncomingComplete());
    }
  }

  @Test
  public void testRunWithDeadNodes() {
    HandelEth2Parameters params = new HandelEth2Parameters(128, 5, 200, 40, 5, null, null, 0);
    HandelEth2 p = new HandelEth2(params);
    p.init();
    HNode n = p.network().getFirstLiveNode();

    p.network().runMs(HandelEth2Parameters.PERIOD_AGG_TIME * 10);

    int minRunning = Collections.min(n.runningAggs.keySet());
    HNode.AggregationProcess ap = n.runningAggs.get(minRunning);
    Assert.assertNotNull(ap);
    HLevel hl = ap.levels.get(ap.levels.size() - 1);

    // As we have dead nodes the last level can't be complete
    Assert.assertFalse("n0, " + hl, hl.isIncomingComplete());

    // We have enough time to get all the contributions from the live nodes.
    Assert.assertEquals(params.nodeCount - params.nodesDown, ap.getBestResultSize());

    BitSet allAttestations = new BitSet();
    for (Attestation a : ap.getBestResult().values()) {
      allAttestations.or(a.who);
    }
    Assert.assertEquals(params.nodeCount - params.nodesDown, allAttestations.cardinality());
    // We should not have attestations from dead nodes.
    Assert.assertFalse(allAttestations.intersects(p.network().getDeadNodes()));
  }
}
