package net.consensys.wittgenstein.protocol;


import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;


public class CasperIMDTest {
    private final CasperIMD ci = new CasperIMD(false, 5, 80, 1000, 1, 0);
    private final CasperIMD.BlockProducer bp1 = ci.new BlockProducer(0, 0, ci.genesis);
    private final CasperIMD.BlockProducer bp2 = ci.new BlockProducer(0, 0, ci.genesis);
    private final CasperIMD.Attester at1 = ci.new Attester(0, 0, ci.genesis);
    private final CasperIMD.Attester at2 = ci.new Attester(1, 0, ci.genesis);

    @Before
    public void before() {
        ci.network.time = 100_000;
    }


    @Test
    public void testMerge() {
        CasperIMD.CasperBlock b = bp1.buildBlock((CasperIMD.CasperBlock) bp1.head, 1);
        bp1.onBlock(b);
        Assert.assertEquals(b, bp1.head);

        CasperIMD.Attestation a1 = ci.new Attestation(at1, 1);
        Assert.assertEquals("We attest on parents, and genesis has no parents", 0, a1.hs.size());
        at1.onBlock(b);
        Assert.assertEquals(b, at1.head);
        at2.onBlock(b);

        a1 = ci.new Attestation(at1, 1);
        Assert.assertEquals("An attestation of height h contains parents of blocks of height h", 1, a1.hs.size());
        Assert.assertTrue(a1.attests(ci.genesis));
        Assert.assertFalse(a1.attests(b));

        a1 = ci.new Attestation(at1, 2);
        Assert.assertEquals(1, a1.hs.size());
        Assert.assertTrue(a1.attests(ci.genesis));
        Assert.assertFalse(a1.attests(b));

        bp1.onAttestation(a1);
        Assert.assertTrue(bp1.attestationsByHead.containsKey(b.id));
        Assert.assertEquals(1, bp1.attestationsByHead.get(b.id).size());
        Assert.assertTrue(bp1.attestationsByHead.get(b.id).contains(a1));
        b = bp1.buildBlock((CasperIMD.CasperBlock) bp1.head, 2);
        Assert.assertFalse("We're a block of height 2, we can't contain an attestation of the same height",
                b.attestationsByHeight.containsKey(2));

        b = bp1.buildBlock((CasperIMD.CasperBlock) bp1.head, 3);
        Assert.assertTrue(b.attestationsByHeight.containsKey(2));
        Assert.assertEquals(1, b.attestationsByHeight.get(2).size());

        a1 = ci.new Attestation(at1, 2);
        bp1.onAttestation(a1);
        b = bp1.buildBlock((CasperIMD.CasperBlock) bp1.head, 3);
        Assert.assertTrue(b.attestationsByHeight.containsKey(2));
        Assert.assertEquals(2, b.attestationsByHeight.get(2).size());
    }

    @Test
    public void testCompareNoAttester() {
        CasperIMD.CasperBlock b = bp1.buildBlock((CasperIMD.CasperBlock) bp1.head, 1);
        bp1.onBlock(b);
        bp2.onBlock(b);

        CasperIMD.CasperBlock b1 = bp1.buildBlock((CasperIMD.CasperBlock) bp1.head, 2);
        CasperIMD.CasperBlock b2 = bp2.buildBlock((CasperIMD.CasperBlock) bp2.head, 3);

        bp2.onBlock(b2);
        Assert.assertEquals(b2, bp2.head);

        bp2.onBlock(b1);
        Assert.assertNotEquals("Tie on votes, so block id is used to separate ties: ", b1, bp2.head);
    }

    @Test
    public void testCountAttestationReceived() {
        CasperIMD.CasperBlock b = bp1.buildBlock((CasperIMD.CasperBlock) bp1.head, 1);
        bp1.onBlock(b);
        at1.onBlock(b);

        int ca = bp1.countAttestations(b, ci.genesis);
        Assert.assertEquals(0, ca);

        CasperIMD.Attestation a1 = ci.new Attestation(at1, 2);
        bp1.onAttestation(a1);
        Assert.assertTrue(bp1.attestationsByHead.containsKey(b.id));

        ca = bp1.countAttestations(b, ci.genesis);
        Assert.assertEquals(1, ca);
    }

    @Test
    public void testCountAttestationInBlock() {
        CasperIMD.CasperBlock b = bp1.buildBlock((CasperIMD.CasperBlock) bp1.head, 1);
        bp1.onBlock(b);
        at1.onBlock(b);

        int ca = bp2.countAttestations(b, ci.genesis);
        Assert.assertEquals(0, ca);

        CasperIMD.Attestation a1 = ci.new Attestation(at1, 2);
        bp1.onAttestation(a1);
        Assert.assertTrue(bp1.attestationsByHead.containsKey(b.id));

        b = bp1.buildBlock((CasperIMD.CasperBlock) bp1.head, 3);
        Assert.assertTrue(b.attestationsByHeight.containsKey(2));
        Assert.assertEquals(1, b.attestationsByHeight.get(2).size());

        bp2.onBlock(b);
        Assert.assertEquals(b, bp2.head);
        ca = bp2.countAttestations(b, ci.genesis);
        Assert.assertEquals(1, ca);
    }

    @Test
    public void testTooFarAwayAttestation() {
        CasperIMD.CasperBlock b = bp1.buildBlock((CasperIMD.CasperBlock) bp1.head, 1);
        bp1.onBlock(b);
        at1.onBlock(b);

        CasperIMD.Attestation a1 = ci.new Attestation(at1, 2);
        bp1.onAttestation(a1);
        Assert.assertTrue(bp1.attestationsByHead.containsKey(b.id));

        // for CYCLE_LENGTH == 2
        //    a.h == 2
        //    b.h == 3  => contains attestation for h == 2 && h == 1
        //    b.h == 4  => contains attestation for h == 3 && h == 2
        //    b.h == 5  => contains attestation for h == 4 && h == 3
        b = bp1.buildBlock((CasperIMD.CasperBlock) bp1.head, a1.height + ci.CYCLE_LENGTH);
        Assert.assertTrue(b.attestationsByHeight.containsKey(2));

        b = bp1.buildBlock((CasperIMD.CasperBlock) bp1.head, a1.height + ci.CYCLE_LENGTH + 1);
        Assert.assertFalse(b.attestationsByHeight.containsKey(2));
    }

    @Test
    public void testOtherBranchAttestation() {
        CasperIMD.CasperBlock b1 = bp1.buildBlock((CasperIMD.CasperBlock) bp1.head, 1);
        bp1.onBlock(b1);
        bp2.onBlock(b1);
        at1.onBlock(b1);

        CasperIMD.CasperBlock b2 = bp1.buildBlock((CasperIMD.CasperBlock) bp1.head, 2);
        bp1.onBlock(b2);
        at1.onBlock(b2);

        CasperIMD.Attestation a1 = ci.new Attestation(at1, 2);
        Assert.assertTrue(a1.hs.contains(b1.id));
        bp2.onAttestation(a1);

        CasperIMD.CasperBlock b3 = bp2.buildBlock((CasperIMD.CasperBlock) bp2.head, 3);
        Assert.assertTrue(b3.attestationsByHeight.get(2).isEmpty());

        bp2.onBlock(b2);
        b3 = bp2.buildBlock((CasperIMD.CasperBlock) bp2.head, 3);
        Assert.assertFalse(b3.attestationsByHeight.get(2).isEmpty());
    }

    @Test
    public void testCompareWithAttester() {
        CasperIMD.CasperBlock b1 = bp1.buildBlock((CasperIMD.CasperBlock) bp1.head, 1);
        bp1.onBlock(b1);
        bp2.onBlock(b1);
        at1.onBlock(b1);

        CasperIMD.CasperBlock b2 = bp1.buildBlock((CasperIMD.CasperBlock) bp1.head, 2);
        bp1.onBlock(b2);
        at1.onBlock(b2);
        CasperIMD.Attestation a1 = ci.new Attestation(at1, 2);
        bp1.onAttestation(a1);
        CasperIMD.CasperBlock b3 = bp1.buildBlock((CasperIMD.CasperBlock) bp1.head, 3);
        Assert.assertEquals(1, b3.attestationsByHeight.get(2).size());

        CasperIMD.CasperBlock b4 = bp2.buildBlock((CasperIMD.CasperBlock) bp2.head, 4);
        bp2.onBlock(b4);
        Assert.assertEquals(b4, bp2.head);

        bp2.onBlock(b3);
        Assert.assertEquals(b3, bp2.head);
    }

    @Test
    public void testCompareWithAttesterAttestationOnAParent() {
        CasperIMD.CasperBlock b = bp1.buildBlock((CasperIMD.CasperBlock) bp1.head, 1);
        bp1.onBlock(b);
        bp2.onBlock(b);
        at1.onBlock(b);

        CasperIMD.Attestation a1 = ci.new Attestation(at1, 2);
        bp1.onAttestation(a1);
        CasperIMD.CasperBlock b1 = bp1.buildBlock((CasperIMD.CasperBlock) bp1.head, 3);
        Assert.assertEquals(1, b1.attestationsByHeight.get(2).size());

        CasperIMD.CasperBlock b2 = bp2.buildBlock((CasperIMD.CasperBlock) bp2.head, 4);
        bp2.onBlock(b2);
        Assert.assertEquals(b2, bp2.head);

        bp2.onBlock(b1);
        Assert.assertEquals(b2, bp2.head);
    }

    @Test
    public void testRevaluation() {
        CasperIMD.CasperBlock b1 = bp1.buildBlock((CasperIMD.CasperBlock) bp1.head, 1);
        bp1.onBlock(b1);
        bp2.onBlock(b1);

        CasperIMD.CasperBlock b2 = bp1.buildBlock((CasperIMD.CasperBlock) bp1.head, 2);
        CasperIMD.CasperBlock b3 = bp1.buildBlock((CasperIMD.CasperBlock) bp1.head, 3);

        bp2.onBlock(b2);
        bp2.onBlock(b3);
        Assert.assertEquals(b3, bp2.head);

        at1.onBlock(b2);
        CasperIMD.Attestation a1 = ci.new Attestation(at1, 2);
        Assert.assertTrue(a1.hs.contains(b1.id));
        bp2.onAttestation(a1);
        Assert.assertTrue(bp2.attestationsByHead.containsKey(b2.id));

        int ca = bp2.countAttestations(b2, b1);
        Assert.assertEquals(1, ca);

        bp2.reevaluateHead();
        Assert.assertEquals(b2, bp2.head);
    }
}
