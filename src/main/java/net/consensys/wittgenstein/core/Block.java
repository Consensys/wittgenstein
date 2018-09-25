package net.consensys.wittgenstein.core;

import org.jetbrains.annotations.NotNull;

@SuppressWarnings("WeakerAccess")
abstract public class Block<TB extends Block> {

    /**
     * To ensure that all blocks id are unique we increment a counter.
     * We suppose it's impossible to create two blocks with the same id.
     */
    private static long blockId = 1;

    public final int height;
    public final long proposalTime;
    public final long lastTxId;
    public final long id;
    public final TB parent;
    public final BlockChainNode producer;

    public final boolean valid;

    /**
     * To create a genesis block...
     */
    protected Block() {
        height = 0;
        lastTxId = 0;
        id = 0;
        parent = null;
        producer = null;
        proposalTime = 0;
        valid = true;
    }


    public Block(@NotNull BlockChainNode producer, int height, @NotNull TB parent, boolean valid, long time) {
        if (height <= 0) {
            throw new IllegalArgumentException("Only the genesis block has a special height");
        }
        if (time < parent.proposalTime) {
            throw new IllegalArgumentException("bad time: parent is (" + parent + "), our time:" + time);
        }
        if (parent.height >= height) {
            throw new IllegalArgumentException("Bad parent. me:" + this + ", parent:" + parent);
        }

        this.producer = producer;
        this.height = height;
        this.id = blockId++;
        this.parent = parent;
        this.valid = valid;
        this.lastTxId = time;
        this.proposalTime = time;
    }


    /**
     * @return the number of transactions in this block.
     */
    public long txCount() {
        if (id == 0) return 0;
        assert parent != null;

        long res = lastTxId - parent.lastTxId;
        if (res < 0) {
            throw new IllegalStateException(this + ", bad txCount:" + res);
        }
        return res;
    }


    @SuppressWarnings("unused")
    public boolean isAncestor(@NotNull Block b) {
        if (this == b) return false;

        Block cur = b;
        while (cur.height > this.height) {
            cur = cur.parent;
            assert cur != null;
        }

        return (cur == this);
    }

    /***
     * @return true if b is a direct father or ancestor. false if 'b' is on a different branch
     */
    public boolean hasDirectLink(@NotNull TB b) {
        if (b == this) return true;
        if (b.height == height) return false;

        Block older = height > b.height ? this : b;
        Block young = height < b.height ? this : b;

        while (older.height > young.height) {
            older = older.parent;
            assert older != null;
        }

        return older == young;
    }

    @Override
    public String toString() {
        if (id == 0) return "genesis";
        assert producer != null;
        assert parent != null;

        return  "h:" + height + ", id=" + id +
                ", creationTime:" + proposalTime +
                ", producer=" + producer.nodeId +
                ", parent:" + parent.id;
    }

}
