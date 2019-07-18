package net.consensys.wittgenstein.core.json;

import com.fasterxml.jackson.databind.util.StdConverter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.consensys.wittgenstein.core.Node;

public class ListNodeConverter extends StdConverter<List<? extends Node>, List<Integer>> {
  @Override
  public List<Integer> convert(List<? extends Node> ns) {
    List<Integer> res = new ArrayList<>();
    for (Node n : ns) {
      res.add(n.nodeId);
    }
    Collections.sort(res);
    return res;
  }
}
