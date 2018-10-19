package net.consensys.wittgenstein.tools;

import net.consensys.wittgenstein.core.Node;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SanFerminTest {

    List<Node> allNodes;
    final int count = 8;

    @Before
    public void setup() {
        allNodes = new ArrayList<>();
        Node.NodeBuilder nb = new Node.NodeBuilder();
        for (int i = 0; i < count; i++)
            allNodes.add(new Node(nb));
    }
    @Test
    public void testCandidateSet() {
        Node n1 = allNodes.get(1);
        SanFerminHelper helper = new SanFerminHelper(n1,allNodes);

        List<Node> set2 = helper.getCandidateSet(2);
        Assert.assertTrue(set2.contains(allNodes.get(0)));

        List<Node> set1 = helper.getCandidateSet(1);
        Assert.assertTrue(set1.contains(allNodes.get(3)));
        Assert.assertFalse(set1.contains(allNodes.get(0)));

        List<Node> set0 = helper.getCandidateSet(0);
        Assert.assertTrue(set0.contains(allNodes.get(4)));
        Assert.assertFalse(set0.contains(allNodes.get(0)));
        Assert.assertFalse(set0.contains(allNodes.get(3)));
    }

    @Test
    public void testPickNextNodes() {
        Node n1 = allNodes.get(1);
        SanFerminHelper helper = new SanFerminHelper(n1,allNodes);

        List<Node> set2 = helper.pickNextNodes(2,10);
        Assert.assertTrue(set2.contains(allNodes.get(0)));

        List<Node> set22 = helper.nextCandidateSet(10);
        Assert.assertEquals(set2,set22);
    }

}
