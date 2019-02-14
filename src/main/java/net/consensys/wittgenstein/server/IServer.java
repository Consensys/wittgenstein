package net.consensys.wittgenstein.server;

import net.consensys.wittgenstein.core.Node;
import java.util.List;

public interface IServer {

  List<? extends Node> getNodeInfo();

  int getTime();

  void init(String fullClassName, WParameter parameters);

  List<String> getProtocols();

  WParameter getProtocolParameters(String fullClassName);

  void runMs(int ms);

  Node getNodeInfo(int nodeId);

  void startNode(int nodeId);

  void stopNode(int nodeId);
}
