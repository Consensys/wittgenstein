package net.consensys.wittgenstein.core;

import java.util.ArrayList;

public class P2PNetwork extends Network<P2PNode> {
    private final int connectionCount;

    public P2PNetwork(int connectionCount) {
        this.connectionCount = connectionCount;
    }

    /**
     * @see <a href="https://github.com/ethereum/wiki/wiki/Kademlia-Peer-Selection"/>
     */
    public void setPeers() {
        final ArrayList<P2PNode> todo = new ArrayList<>(allNodes);

        int toCreate = todo.size() * connectionCount;

        while (toCreate-- > 0) {
            int pp1 = rd.nextInt(todo.size());
            int pp2;
            do {
                pp2 = rd.nextInt(todo.size());
            } while (pp1 == pp2);

            // We could consider that close nodes have a greater probability to be peers, but
            //  today's implementation don't have any mechanism for that.
            createLink(todo, pp1, pp2);
        }

        for (P2PNode n : todo) {
            while (n.peers.size() < Math.min(3, this.connectionCount)) {
                int pp2 = rd.nextInt(todo.size());
                if (pp2 != n.nodeId) {
                    createLink(todo, n.nodeId, pp2);
                }
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
