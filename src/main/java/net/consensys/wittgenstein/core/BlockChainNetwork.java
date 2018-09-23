package net.consensys.wittgenstein.core;


import org.jetbrains.annotations.NotNull;

import java.util.*;

@SuppressWarnings({"WeakerAccess", "SameParameterValue", "FieldCanBeLocal", "unused"})
public class BlockChainNetwork extends Network<BlockChainNode<? extends Block>> {
    /**
     * The node we use as an observer for the final stats
     */
    public BlockChainNode observer;

    public void addObserver(@NotNull BlockChainNode<?> observer) {
        this.observer = observer;
        addNode(observer);
    }

    public static class SendBlock<TB extends Block, TN extends BlockChainNode<TB>> extends MessageContent<TN> {
        final @NotNull TB toSend;

        public SendBlock(@NotNull TB toSend) {
            this.toSend = toSend;
        }

        @Override
        public void action(@NotNull TN fromNode, @NotNull TN toNode) {
            toNode.onBlock(toSend);
        }

        @Override
        public String toString() {
            return "SendBlock{" +
                    "toSend=" + toSend.id +
                    '}';
        }
    }

    @Override
    public void endPartition() {
        super.endPartition();

        // On a p2p network all the blocks are exchanged all the time. We simulate this
        //  with a full resent after each partition.
        for (BlockChainNode<?> n : allNodes.values()) {
            SendBlock<?, ?> sb = new BlockChainNetwork.SendBlock<>(n.head);
            sendAll(sb, time + 1, n);
        }
    }

    public void printStat(boolean small) {
        HashMap<Integer, Set<Block>> productionCount = new HashMap<>();
        Set<BlockChainNode> blockProducers = new HashSet<>();

        Block cur = observer.head;
        int blocksCreated = 0;
        while (cur != observer.genesis) {
            if (!small) System.out.println("block: " + cur.toString());
            blocksCreated++;

            productionCount.putIfAbsent(cur.producer.nodeId, new HashSet<>());
            productionCount.get(cur.producer.nodeId).add(cur);
            blockProducers.add(cur.producer);

            cur = cur.parent;
        }

        if (small) {
            //System.out.println("node; block count; tx count; msg sent; msg received");
        } else {
            System.out.println("block count:" + blocksCreated + ", all tx: " + observer.head.lastTxId);
        }
        List<BlockChainNode> bps = new ArrayList<>(blockProducers);
        bps.sort(Comparator.comparingInt(o -> o.nodeId));

        for (BlockChainNode bp : bps) {
            int bpTx = 0;
            for (Block b : productionCount.get(bp.nodeId)) {
                bpTx += b.txCount();
            }
            if (!small | bp.byzantine) {
                System.out.println(
                        bp + "; " +
                                productionCount.get(bp.nodeId).size() + "; " +
                                bpTx + "; " +
                                bp.msgSent + "; " +
                                bp.msgReceived
                );

            }
        }
    }
}
