package net.consensys.wittgenstein.server.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import net.consensys.wittgenstein.core.EnvelopeInfo;
import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.NodeBuilder;
import net.consensys.wittgenstein.core.Protocol;
import net.consensys.wittgenstein.core.messages.Message;
import net.consensys.wittgenstein.protocols.PingPong;
import net.consensys.wittgenstein.server.SendMessage;
import net.consensys.wittgenstein.server.WParameters;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.junit4.SpringRunner;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.List;

@SuppressWarnings("WeakerAccess")
@RunWith(SpringRunner.class)
@SpringBootTest(classes = WServer.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class WServerTest {
  @SuppressWarnings("unused")
  @LocalServerPort
  private int port;
  private TestRestTemplate restTemplate = new TestRestTemplate();
  private HttpHeaders headers = new HttpHeaders();
  private ObjectMapper objectMapper = new WServer().objectMapper();

  private String createURLWithPort(String uri) {
    return "http://localhost:" + port + "/w" + uri;
  }

  private URI createURIWithPort(String uri) {
    try {
      return new URL(createURLWithPort(uri)).toURI();
    } catch (MalformedURLException | URISyntaxException e) {
      throw new IllegalArgumentException(e);
    }
  }

  @Test
  public void testGetProtocols() throws Exception {
    HttpEntity<String> entity = new HttpEntity<>(null, headers);

    ResponseEntity<String> response = restTemplate.exchange(createURLWithPort("/protocols"),
        HttpMethod.GET, entity, String.class);

    CollectionType javaType =
        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class);

    List<String> ps = objectMapper.readValue(response.getBody(), javaType);
    Assert.assertTrue(response.getBody(), ps.contains(PingPong.class.getName()));
  }

  @Test
  public void testGetProtocolParameters() throws Exception {
    HttpEntity<String> entity = new HttpEntity<>(null, headers);

    ResponseEntity<String> response =
        restTemplate.exchange(createURLWithPort("/protocols/" + PingPong.class.getName()),
            HttpMethod.GET, entity, String.class);

    WParameters ps = objectMapper.readValue(response.getBody(), WParameters.class);
    Assert.assertTrue(response.getBody(), ps instanceof PingPong.PingPongParameters);
  }

  private List<Node> allNodeInfo() throws IOException {
    HttpEntity<String> entity = new HttpEntity<>(null, headers);

    ResponseEntity<String> response = restTemplate.exchange(createURLWithPort("/network/nodes"),
        HttpMethod.GET, entity, String.class);
    Assert.assertEquals(HttpStatus.OK, response.getStatusCode());

    CollectionType javaType =
        objectMapper.getTypeFactory().constructCollectionType(List.class, Node.class);
    return objectMapper.readValue(response.getBody(), javaType);
  }

  private List<EnvelopeInfo> allMessagesInfo() throws IOException {
    HttpEntity<String> entity = new HttpEntity<>(null, headers);

    ResponseEntity<String> response = restTemplate.exchange(createURLWithPort("/network/messages"),
        HttpMethod.GET, entity, String.class);
    Assert.assertEquals(HttpStatus.OK, response.getStatusCode());

    CollectionType javaType =
        objectMapper.getTypeFactory().constructCollectionType(List.class, EnvelopeInfo.class);
    return objectMapper.readValue(response.getBody(), javaType);
  }


  public static class Node {
    @SuppressWarnings("unused")
    int nodeId;

    public Node() {}
  }

  public static class DummyProtocol implements Protocol {
    final NodeBuilder nb = new NodeBuilder.NodeBuilderWithRandomPosition();
    final Network<net.consensys.wittgenstein.core.Node> network = new Network<>();

    @Override
    public Network<?> network() {
      return network;
    }

    @Override
    public Protocol copy() {
      return new DummyProtocol(new DummyParameters());
    }

    @Override
    public void init() {
      network.addNode(new net.consensys.wittgenstein.core.Node(network.rd, nb));
      network.addNode(new net.consensys.wittgenstein.core.Node(network.rd, nb));
    }

    @SuppressWarnings("unused")
    public DummyProtocol(DummyParameters p) {}

    public static class DummyParameters extends WParameters {
      public DummyParameters() {}
    }
  }

  public static class MessageTest extends Message<net.consensys.wittgenstein.core.Node> {
    public MessageTest() {}

    @Override
    public void action(Network<net.consensys.wittgenstein.core.Node> network,
        net.consensys.wittgenstein.core.Node from, net.consensys.wittgenstein.core.Node to) {}
  }

  @Test
  public void testInitProtocol() throws Exception {
    WParameters params = new PingPong.PingPongParameters(123, null, null);
    String jsonString = objectMapper.writeValueAsString(params);

    RequestEntity<String> requestEntity = RequestEntity
        .post(createURIWithPort("/network/init/" + PingPong.class.getName()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(jsonString);

    ResponseEntity<Void> response = restTemplate.exchange(requestEntity, Void.class);

    Assert.assertEquals(HttpStatus.OK, response.getStatusCode());

    List<Node> allNodes = allNodeInfo();
    Assert.assertEquals(123, allNodes.size());
  }

  @Test
  public void testFullWorkflowOnDummyProtocol() throws Exception {
    WParameters params = new DummyProtocol.DummyParameters();
    String jsonString = objectMapper.writeValueAsString(params);

    RequestEntity<String> requestEntity = RequestEntity
        .post(createURIWithPort("/network/init/" + DummyProtocol.class.getName()))
        .contentType(MediaType.APPLICATION_JSON)
        .body(jsonString);
    ResponseEntity<Void> response = restTemplate.exchange(requestEntity, Void.class);
    Assert.assertEquals(response.toString(), HttpStatus.OK, response.getStatusCode());

    List<Node> allNodes = allNodeInfo();
    Assert.assertEquals(2, allNodes.size());

    List<EnvelopeInfo> mis = allMessagesInfo();
    Assert.assertEquals(0, mis.size());

    SendMessage sm = new SendMessage(0, Collections.singletonList(1), 1, 0, new MessageTest());
    requestEntity = RequestEntity
        .post(createURIWithPort("/network/send/"))
        .contentType(MediaType.APPLICATION_JSON)
        .body(objectMapper.writeValueAsString(sm));
    response = restTemplate.exchange(requestEntity, Void.class);
    Assert.assertEquals(response.toString(), HttpStatus.OK, response.getStatusCode());
    mis = allMessagesInfo();
    Assert.assertEquals(1, mis.size());

    requestEntity = RequestEntity.post(createURIWithPort("/network/runMs/10000")).body(null);
    response = restTemplate.exchange(requestEntity, Void.class);
    Assert.assertEquals(response.toString(), HttpStatus.OK, response.getStatusCode());
    mis = allMessagesInfo();
    Assert.assertEquals(0, mis.size());

    RequestEntity<Void> reqv = RequestEntity.get(createURIWithPort("/network/time")).build();
    ResponseEntity<String> ress = restTemplate.exchange(reqv, String.class);
    Assert.assertEquals(ress.toString(), HttpStatus.OK, ress.getStatusCode());
    Assert.assertEquals("10000", ress.getBody());
  }

}
