package net.consensys.wittgenstein.core;

import org.jetbrains.annotations.NotNull;

import java.util.Random;

@SuppressWarnings({"WeakerAccess", "unused"})
public class Node {
    public final static int MAX_X = 1000;
    public final static int MAX_Y = 1000;
    public final static int MAX_DIST = (int) Math.sqrt((MAX_X / 2) * (MAX_X / 2) + (MAX_Y / 2) * (MAX_Y / 2));


    public final int nodeId;

    /**
     * The position. There is a clear weakness here: the nodes
     * are randomly distributed, but in real life we have oceans, so some
     * subset of nodes are really isolated. We should have a builder that takes
     * a map as in input.
     */
    public final int x;
    public final int y;
    public final boolean byzantine;

    protected long msgReceived = 0;
    protected long msgSent = 0;
    protected long bytesSent = 0;
    protected long bytesReceived = 0;

    public long getMsgReceived() {
        return msgReceived;
    }

    public long getMsgSent() {
        return msgSent;
    }

    public long getBytesSent() {
        return bytesSent;
    }

    public long getBytesReceived() {
        return bytesReceived;
    }

    public static class NodeBuilder {
        int nodeIds = 0;

        int allocateNodeId() {
            return nodeIds++;
        }

        int getX() {
            return 0;
        }

        int getY() {
            return 0;
        }
    }

    public static class NodeBuilderWithPosition extends NodeBuilder {
        final Random rd;

        public NodeBuilderWithPosition(Random rd) {
            this.rd = rd;
        }

        int getX() {
            return rd.nextInt(MAX_X) + 1;
        }

        int getY() {
            return rd.nextInt(MAX_Y) + 1;
        }
    }


    public Node(@NotNull NodeBuilder nb, boolean byzantine) {
        this.nodeId = nb.allocateNodeId();
        this.x = nb.getX();
        this.y = nb.getY();
        this.byzantine = byzantine;
    }

    public Node(@NotNull NodeBuilder nb) {
        this(nb, false);
    }

    int dist(@NotNull Node n) {
        int dx = Math.min(Math.abs(x - n.x), MAX_X - Math.abs(x - n.x));
        int dy = Math.min(Math.abs(y - n.y), MAX_Y - Math.abs(y - n.y));
        return (int) Math.sqrt(dx * dx + dy * dy);
    }

}
