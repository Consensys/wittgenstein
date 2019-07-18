package net.consensys.wittgenstein.core.utils;

import java.util.Arrays;

public class Kademlia {

  /** Calculates the XOR distance between two values. Taken from pantheon code. */
  public static int distance(byte[] v1b, byte[] v2b) {
    assert (v1b.length == v2b.length);

    if (Arrays.equals(v1b, v2b)) {
      return 0;
    }

    int distance = v1b.length * 8;
    for (int i = 0; i < v1b.length; i++) {
      byte xor = (byte) (0xff & (v1b[i] ^ v2b[i]));
      if (xor == 0) {
        distance -= 8;
      } else {
        int p = 7;
        while (((xor >> p--) & 0x01) == 0) {
          distance--;
        }
        break;
      }
    }
    return distance;
  }

  /*
   * http://www.scs.stanford.edu/~dm/home/papers/kpos.pdf
   *
   * For each 0 =< i < 160, every node keeps a list of (IP address; UDP port; Node ID)
   *  triples for nodes of distance between 2^i and 2^(i+1) from itself. We call these
   *  lists k-buckets.
   *
   * When a Kademlia node receives any message (request or reply) from another node, it updates
   *  the appropriate k-bucket for the sender’s  nodeID. If the sending node already exists in the
   *  recipient’s k-bucket, the recipient moves it to the tail of the list. If the node is not
   *  already in the appropriate k-bucket and the bucket has fewer than k entries, then the
   *  recipient just inserts the new sender at the tail of the list. If the appropriate
   *  k-bucket is full, however, then the recipient pings the k-bucket’s least-recently
   *  seen node to decide what to do. If the least-recently seen node fails to respond, it is
   *  evicted from the k-bucket and the new sender inserted at the tail. Otherwise, if the
   *  least-recently seen node responds, it is moved to the tail of the list, and the new sender’s
   *  contact is discarded.
   *
   *  The most important procedure a Kademlia participant must perform is to locate the
   *   k closest nodes to some given node ID. We call this procedure a node lookup.
   *  Kademlia employs a recursive algorithm for node lookups. The lookup initiator starts
   *   by picking α nodes from its closest non-empty k-bucket (or, if that bucket has fewer
   *   than α entries, it just takes the α closest nodes it knows of). The initiator then
   *   sends parallel, asynchronous FIND NODE RPCs to the α nodes it has chosen. α is a
   *   system-wide concurrency parameter, such as 3.
   *  In the recursive step, the initiator resends the FIND NODE to nodes it has learned
   *   about from previous RPCs.
   *  The lookup terminates when the initiator has queried and gotten responses from the
   *   k closest nodes it has seen. When α = 1 the lookup algorithm resembles Chord’s in
   *   terms of message cost and the latency of  detecting failed nodes. However, Kademlia
   *   can route for lower latency because it has the flexibility of choosing
   *   any one of k nodes to forward a request to.
   *
   *  To join the network, a node u must have a contact to an already participating
   *   node w. u inserts w into the appropriate k-bucket. u then performs a node lookup for
   *   its own node ID. Finally, u refreshes all k-buckets further away than its closest
   *   neighbor. During the refreshes, u both populates its own k-buckets and inserts itself
   *   into other nodes’k-buckets as necessary.
   *
   * (https://github.com/ethereum/devp2p/blob/master/discv4.md)
   * Ethereum v1 uses a value of 16 for k, and 256 buckets instead of 160.
   */
}
