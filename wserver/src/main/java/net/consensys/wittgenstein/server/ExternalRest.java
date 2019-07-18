package net.consensys.wittgenstein.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import net.consensys.wittgenstein.core.EnvelopeInfo;
import net.consensys.wittgenstein.core.External;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.messages.SendMessage;
import net.consensys.wittgenstein.server.ws.ObjectMapperFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

public class ExternalRest implements External {
  private final String httpFullAddress;
  private final RestTemplate restTemplate;
  private final ObjectMapper objectMapper;

  public ExternalRest(String httpFullAddress) {
    this.objectMapper = ObjectMapperFactory.objectMapper();
    this.httpFullAddress = httpFullAddress;
    this.restTemplate = new RestTemplateBuilder().rootUri(httpFullAddress).build();
  }

  @Override
  public String toString() {
    return this.getClass().getSimpleName() + ": " + httpFullAddress;
  }

  @Override
  public <TN extends Node> List<SendMessage> receive(EnvelopeInfo<TN> ei) {
    String jsonString = "na";
    try {
      jsonString = objectMapper.writeValueAsString(ei);

      RequestEntity<String> requestEntity =
          RequestEntity.put(new URL(httpFullAddress).toURI())
              .contentType(MediaType.APPLICATION_JSON)
              .body(jsonString);

      ResponseEntity<String> re = restTemplate.exchange(requestEntity, String.class);
      if (re.hasBody()) {
        System.out.println("answer: " + re.getBody());
        CollectionType javaType =
            objectMapper.getTypeFactory().constructCollectionType(List.class, SendMessage.class);

        return objectMapper.readValue(re.getBody(), javaType);
      }

    } catch (Throwable t) {
      System.err.println("caught: " + t.getMessage() + ", sending: " + jsonString + "");
    }
    return Collections.emptyList();
  }
}
