package net.consensys.wittgenstein.core;

@SuppressWarnings("WeakerAccess")
public abstract class Block<TB extends Block> {

  /**
   * To ensure that all blocks id are unique we increment a counter. We suppose it's impossible to
   * create two blocks with the same id.
   */
  private static long blockId = 1;

  public final int height;
  public final int proposalTime;
  public final long lastTxId;
  public final long id;
  public final TB parent;
  public final BlockChainNode<TB> producer;

  public final boolean valid;

  /** To create a genesis block... */
  public Block(int h) {
    height = h;
    lastTxId = 0;
    id = 0;
    parent = null;
    producer = null;
    proposalTime = 0;
    valid = true;
  }

  public static long getLastBlockId() {
    return blockId;
  }

  public Block(BlockChainNode<TB> producer, int height, TB parent, boolean valid, int time) {
    if (height <= 0) {
      throw new IllegalArgumentException("Only the genesis block has a special height");
    }
    if (parent != null && time < parent.proposalTime) {
      throw new IllegalArgumentException("bad time: parent is (" + parent + "), our time:" + time);
    }
    if (parent != null && parent.height >= height) {
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

  /** @return the number of transactions in this block. */
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
  public boolean isAncestor(Block b) {
    if (this == b) return false;

    Block cur = b;
    while (cur.height > this.height) {
      cur = cur.parent;
      assert cur != null;
    }

    return (cur == this);
  }

  /**
   * *
   *
   * @return true if b is a direct father or ancestor. false if 'b' is on a different branch
   */
  public boolean hasDirectLink(TB b) {
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
    if (id == 0) {
      return "genesis";
    }

    return "h:"
        + height
        + ", id="
        + id
        + ", creationTime:"
        + proposalTime
        + ", producer="
        + (producer != null ? "" + producer.nodeId : "null")
        + ", parent:"
        + (parent != null ? "" + parent.id : "null");
  }
}
