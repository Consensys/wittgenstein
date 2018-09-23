package net.consensys.wittgenstein.core;

import java.util.ArrayList;

public class P2PNetwork extends Network<P2PNode> {
    private final int connectionCount;

    public P2PNetwork(int connectionCount) {
        this.connectionCount = connectionCount;
    }

    public void setPeers() {
        final ArrayList<P2PNode> todo = new ArrayList<>(allNodes.values());

        int toCreate = todo.size() * connectionCount;

        while (toCreate > 0) {
            int pp1 = rd.nextInt(todo.size());
            int pp2 = rd.nextInt(todo.size());

            if (pp1 != pp2) {
                createLink(todo, pp1, pp2);
                toCreate--;
            }
        }

        int i = 0;
        for (P2PNode n : todo) {
            i++;
            while (n.peers.size() < 3) {
                int pp2 = rd.nextInt(todo.size());
                if (pp2 != i) createLink(todo, i, pp2);
            }
        }
    }


    private void createLink(ArrayList<P2PNode> todo, int pp1, int pp2) {
        P2PNode p1 = todo.get(pp1);
        P2PNode p2 = todo.get(pp2);

        p1.peers.add(p2);
        p2.peers.add(p1);
    }
}
