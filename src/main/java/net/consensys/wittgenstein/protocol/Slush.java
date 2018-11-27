package net.consensys.wittgenstein.protocol;
import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.Protocol;
import net.consensys.wittgenstein.core.Node;

import java.util.Random;

public class SlushSnowFlakeP implements Protocol {

    private static final int NODES_AV = 100;
    private final Network<SlushNodes> network = new Network<>();
    private static final Random rd = new Random();

    @Override
    public void init() {
        for(int i=0; i<NODES_AV;i++){
            network.addNode(new SlushNodes(rd,false));
        }
    }

    @Override
    public Network<SlushNodes> network() {
        return network;
    }

    @Override
    public Protocol copy() {
        return new SlushSnowFlakeP();
    }


    class SlushNodes extends Node{

        public SlushNodes(Random rd,NodeBuilder nb,  boolean byzantine) {
            super(rd,nb,false);
        }
    }
}
