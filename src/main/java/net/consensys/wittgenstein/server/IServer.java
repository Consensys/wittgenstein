package net.consensys.wittgenstein.server;

import net.consensys.wittgenstein.core.EnvelopeInfo;
import net.consensys.wittgenstein.core.Node;
import java.util.List;

public interface IServer {

  List<? extends Node> getNodeInfo();

  int getTime();

  void init(String fullClassName, WParameters parameters);

  List<String> getProtocols();

  WParameters getProtocolParameters(String fullClassName);

  void runMs(int ms);

  Node getNodeInfo(int nodeId);

  List<EnvelopeInfo<?>> getMessages();

  void startNode(int nodeId);

  void stopNode(int nodeId);

  void setExternal(int nodeId, String externalServiceFullAddress);

  <TN extends Node> void sendMessage(SendMessage msg);
}
