package net.consensys.wittgenstein.protocols;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.utils.MoreMath;

/**
 * SanFerminHelper is a helper that computes some useful information needed in San Fermin-like
 * protocols. A SanFerminHelper keeps track of already contacted nodes at each level. It can also
 * compute the set of candidates to contact at a given level, and computes the set of a given node
 * at a given level.
 */
@SuppressWarnings("WeakerAccess")
public class SanFerminHelper<T extends Node> {

  private final T n;
  /** The id of the node in binary */
  public final String binaryId;
  /** List of all nodes */
  private final List<T> allNodes;
  /** List of nodes we already selected at different levels */
  private final HashMap<Integer, BitSet> usedNodes;

  /** Random instance for reproducible experiments */
  private final Random rd;
  /**
   * currentLevel tracks at which level this SanFerminHelper is. It is useful when using the helper
   * to "conduct" the protocol using the method `nextCandidateSet`
   */
  public int currentLevel;

  public SanFerminHelper(T n, List<T> allNodes, Random rd) {
    this.n = n;
    this.binaryId = toBinaryID(n, allNodes.size());
    this.allNodes = allNodes;
    this.usedNodes = new HashMap<>();
    this.rd = rd;
    this.currentLevel = MoreMath.log2(allNodes.size());
  }

  /**
   * getOwnSet returns the set of nodes at a given level that contains the node given to the helper
   */
  public List<T> getOwnSet(int level) {
    int min = 0;
    int max = allNodes.size();
    for (int currLevel = 0; currLevel <= level && min <= max; currLevel++) {
      int m = Math.floorDiv((max + min), 2);
      if (binaryId.charAt(currLevel) == '0') {
        // reduce the interval to the left
        max = m;
      } else if (binaryId.charAt(currLevel) == '1') {
        // reduce interval to the right
        min = m;
      }
      if (max == min) break;

      if (max - 1 == 0 || min == allNodes.size()) break;
    }

    return allNodes.subList(min, max);
  }

  /**
   * getCandidateSet returns the whole set of nodes at a given level that should be contacted by the
   * node given to the helper
   */
  public List<T> getCandidateSet(int level) {
    int min = 0;
    int max = allNodes.size();
    for (int currLevel = 0; currLevel <= level && min <= max; currLevel++) {
      int m = Math.floorDiv((max + min), 2);
      if (binaryId.charAt(currLevel) == '0') {
        if (currLevel == level) {
          // when we are at the right level, swap the order
          min = m;
        } else {
          max = m;
        }

      } else if (binaryId.charAt(currLevel) == '1') {
        if (currLevel == level) {
          // when we are at the right level, swap the order
          max = m;
        } else {
          min = m;
        }
      }
      if (max == min) break;

      if (max - 1 == 0 || min == allNodes.size()) break;
    }
    return allNodes.subList(min, max);
  }

  /** */
  public boolean isCandidate(T node, int level) {
    return this.getCandidateSet(level).contains(node);
  }

  /**
   * getExactCandidateNode selects deterministically the node from the candidate set at the given
   * level that should be contacted.
   */
  public T getExactCandidateNode(int level) {
    List<T> own = this.getOwnSet(level);
    int idx = own.indexOf(this.n);
    if (idx == -1) throw new IllegalStateException();

    List<T> candidates = this.getCandidateSet(level);
    if (idx >= candidates.size()) throw new IllegalStateException();

    return candidates.get(idx);
  }

  /**
   * pickNextNodes returns the nodes that should be contacted at a given level. The difference with
   * getCandidateSet is that saves which nodes have been selected already, and returns only new or
   * no nodes at each call for the same level.
   */
  public List<T> pickNextNodes(int level, int howMany) {
    List<T> candidateSet = new ArrayList<>(getCandidateSet(level));

    List<T> ownSet = getOwnSet(level);
    int idx = ownSet.indexOf(this.n);
    if (idx == -1 || ownSet.size() < idx) throw new IllegalStateException();

    List<T> newList = new ArrayList<>();
    BitSet set = usedNodes.getOrDefault(level, new BitSet());
    // add the "correct" one first if not already
    if (!set.get(idx)) {
      newList.add(candidateSet.get(idx));
      candidateSet.remove(idx);
      set.set(idx);
    }

    // add the rest if not taken already
    newList.addAll(
        IntStream.range(0, candidateSet.size())
            // only take nodes not seen before
            .filter(node -> !set.get(node))
            // less than howMany
            .limit(howMany)
            // map to the nodes and set them seen
            .mapToObj(
                i -> {
                  set.set(i); // register it
                  return candidateSet.get(i);
                })
            .collect(Collectors.toList()));

    usedNodes.put(level, set);
    Collections.shuffle(newList, rd);
    return newList;
  }

  /** Simply pads the binary string id to the exact length = n where N = 2^n */
  private static String leftPadWithZeroes(String originalString, int length) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < length; i++) {
      sb.append("0");
    }
    String padding = sb.toString();
    return padding.substring(originalString.length()) + originalString;
  }

  public static String toBinaryID(Node node, int setSize) {
    int log2 = MoreMath.log2(setSize);
    return leftPadWithZeroes(Integer.toBinaryString(node.nodeId), log2);
  }
}
