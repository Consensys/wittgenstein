package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.Node;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.BitSet;

public class P2PSignatureTest {
    private P2PSignature ps = new P2PSignature(100, 60,10,2, 20,false);
    private P2PSignature.P2PSigNode n1;
    private P2PSignature.P2PSigNode n2;

    @Before
    public void before(){
        ps.init();
        n1 = (P2PSignature.P2PSigNode)ps.network.getNodeById(1);
        n2 = (P2PSignature.P2PSigNode)ps.network.getNodeById(2);
    }

    @Test
    public void testSetup(){
        Assert.assertEquals(1, n1.verifiedSignatures.cardinality());
        Assert.assertTrue(n1.verifiedSignatures.get(n1.nodeId));
        Assert.assertTrue( n1.peers.size() >= 3);
        for (Node n: n1.peers) {
            Assert.assertNotEquals(n, ps);
        }
    }

    @Test
    public void testSendSigs(){
        n1.peersState.put(n2.nodeId, new P2PSignature.State(n2));

        ps.network.msgs.clear();
        n1.sendSigs();
        Network.Message nm = ps.network.msgs.peekFirst();
        Assert.assertNotNull(nm);

        P2PSignature.SendSigs ss = (P2PSignature.SendSigs)nm.getMessageContent();
        Assert.assertNotNull(ss);
        Assert.assertEquals(1, ss.sigs.cardinality());
        Assert.assertTrue(ss.sigs.get(n1.nodeId));
        Assert.assertTrue(n1.peersState.isEmpty());
    }

    @Test
    public void testCheckSigs(){
        BitSet sigs = new BitSet(ps.nodeCount);
        sigs.set(n1.nodeId);
        sigs.set(0);
        n1.toVerify.add(sigs);

        ps.network.msgs.clear();
        n1.checkSigs();

        Assert.assertTrue(n1.toVerify.isEmpty());
        Assert.assertEquals(1, ps.network.msgs.size());
    }

    @Test
    public void testSigUpdate(){
        BitSet sigs = new BitSet(ps.nodeCount);
        sigs.set(n1.nodeId);
        sigs.set(0);

        n1.updateVerifiedSignatures(sigs);
        Assert.assertEquals(2, n1.verifiedSignatures.cardinality());
    }
}
