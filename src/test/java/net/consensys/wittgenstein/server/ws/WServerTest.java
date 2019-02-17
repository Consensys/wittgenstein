package net.consensys.wittgenstein.server.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import net.consensys.wittgenstein.protocols.PingPong;
import net.consensys.wittgenstein.server.WParameter;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;
import java.util.List;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = WServer.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class WServerTest {
  @LocalServerPort
  private int port;
  private TestRestTemplate restTemplate = new TestRestTemplate();
  private HttpHeaders headers = new HttpHeaders();
  private ObjectMapper objectMapper = new WServer().objectMapper();

  private String createURLWithPort(String uri) {
    return "http://localhost:" + port + uri;
  }

  @Test
  public void testGetProtocols() throws Exception {
    HttpEntity<String> entity = new HttpEntity<>(null, headers);

    ResponseEntity<String> response = restTemplate.exchange(createURLWithPort("/w/protocols"),
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
        restTemplate.exchange(createURLWithPort("/w/protocols/" + PingPong.class.getName()),
            HttpMethod.GET, entity, String.class);

    WParameter ps = objectMapper.readValue(response.getBody(), WParameter.class);
    Assert.assertTrue(response.getBody(), ps instanceof PingPong.PingPongParameters);
  }
}
