package net.consensys.wittgenstein.core;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class TestNetworkLatency {
    private final AtomicInteger ai = new AtomicInteger();
    private final Node.NodeBuilder nb = new Node.NodeBuilder(){
        public int getX(){
            return ai.addAndGet(Node.MAX_X / 10) ;
        }
    };
    private final Node n1 = new Node(nb);
    private final Node n2 = new Node(nb);

    @Test
    public void testZeroDist(){
        Assert.assertEquals(0, n1.dist(n1));
        Assert.assertEquals(0, n2.dist(n2));
    }

    @Test
    public void testDistanceLatency(){
        NetworkLatency nl = new NetworkLatency.NetworkLatencyByDistance();

        Assert.assertEquals(10, nl.getDelay(n1, n1, 50));
        Assert.assertEquals(15, nl.getDelay(n1, n1, 0));
        Assert.assertEquals(5, nl.getDelay(n1, n1, 99));

    }
}
