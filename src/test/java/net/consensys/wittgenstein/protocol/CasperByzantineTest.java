package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.Network;
import org.junit.Assert;
import org.junit.Test;

public class CasperByzantineTest {
    private final CasperIMD ci = new CasperIMD(1, false, 2, 2,
            1000, 1);

    @Test
    public void testByzantineWF(){
        ci.init(ci.new ByzantineProdWF(Network.BYZANTINE_NODE_ID, 0, ci.genesis));
        ci.network.removeNetworkLatency();

        ci.network.run(9);
        Assert.assertEquals(ci.genesis, ci.network.observer.head);

        ci.network.run(1); // 10 seconds: 8 for start + 1 for build time + 1 for network delay
        Assert.assertNotEquals(ci.genesis, ci.network.observer.head);
        assert ci.network.observer.head.producer != null;
        Assert.assertEquals(1, ci.network.observer.head.height);
        Assert.assertEquals(Network.BYZANTINE_NODE_ID, ci.network.observer.head.producer.nodeId);

        ci.network.run(8); // 18s : 16s to start + build time + network delay
        Assert.assertNotEquals(ci.genesis, ci.network.observer.head);
        assert ci.network.observer.head.producer != null;
        Assert.assertEquals(2, ci.network.observer.head.height);
        Assert.assertNotEquals(Network.BYZANTINE_NODE_ID, ci.network.observer.head.producer.nodeId);

        ci.network.run(8); // 26s :  24 (because of the delay) + build  +  network
        Assert.assertNotEquals(ci.genesis, ci.network.observer.head);
        assert ci.network.observer.head.producer != null;
        Assert.assertEquals(3, ci.network.observer.head.height);
        Assert.assertEquals(Network.BYZANTINE_NODE_ID, ci.network.observer.head.producer.nodeId);
    }

    @Test
    public void testByzantineWFWithDelay(){
        ci.network.removeNetworkLatency();

        CasperIMD.ByzantineProdWF byz = ci.new ByzantineProdWF(Network.BYZANTINE_NODE_ID, -2000, ci.genesis);
        ci.init(byz);

        ci.network.run(5);
        Assert.assertEquals(0, byz.head.height);

        ci.network.run(1);
        Assert.assertEquals(1, byz.head.height);
        Assert.assertEquals(0, ci.network.observer.head.height);

        ci.network.run(2); // The observer will wait for the right time, hence 8 seconds
        Assert.assertEquals(1, ci.network.observer.head.height);

        ci.network.run(9); // 18s : 16s to start + build time + network delay
        Assert.assertEquals(1, ci.network.observer.head.height);
        ci.network.run(1); // 18s : 16s to start + build time + network delay
        Assert.assertEquals(2, byz.head.height);
        assert byz.head.producer != null;
        Assert.assertNotEquals(Network.BYZANTINE_NODE_ID, byz.head.producer.nodeId);

        ci.network.run(3);
        Assert.assertEquals(2, byz.head.height);
        ci.network.run(1); // 22s: 24 -2 seconds delay
        Assert.assertEquals(3, byz.head.height);
    }

}
