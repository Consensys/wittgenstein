package net.consensys.wittgenstein.server;

import net.consensys.wittgenstein.core.EnvelopeInfo;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.messages.Message;
import java.util.List;

public interface IServer {

  List<? extends Node> getNodeInfo();

  int getTime();

  void init(String fullClassName, WParameter parameters);

  List<String> getProtocols();

  WParameter getProtocolParameters(String fullClassName);

  void runMs(int ms);

  Node getNodeInfo(int nodeId);

  List<EnvelopeInfo> getMessages();

  void startNode(int nodeId);

  void stopNode(int nodeId);

  <TN extends Node> void sendMessage(SendMessage msg);
}
