package net.consensys.wittgenstein.tools;

import java.util.*;
import java.util.stream.Collectors;

public class CSVFormatter {
  private static class EmptyObject {
    @Override
    public String toString() {
      return "";
    }
  }

  private final List<String> fields;
  private final Map<String, List<Object>> values;
  private int nbRows;

  public CSVFormatter(List<String> fields) {
    this.fields = fields;
    this.values = new HashMap<>();
    this.nbRows = 0;
  }

  /**
   * add each values to its corresponding value to the CSV. It corresponds to a new row in the CSV
   * output. Empty fields will be filled out by empty value when printing the CSV.
   */
  public void add(Map<String, Object> vals) {
    for (String field : fields) {
      List<Object> l = this.values.getOrDefault(field, new ArrayList<>());
      Object value = vals.get(field);
      l.add(Objects.requireNonNullElseGet(value, EmptyObject::new));
      this.values.put(field, l);
    }
    this.nbRows++;
  }

  @Override
  public String toString() {
    String headers = Headers() + "\n";
    StringBuilder rows = new StringBuilder();
    for (int i = 0; i < nbRows; i++) {
      List<Object> rowValues = new ArrayList<>();
      for (String field : fields) {
        Object value = values.get(field).get(i);
        rowValues.add(value);
      }
      String line = rowValues.stream().map(Object::toString).collect(Collectors.joining(","));
      rows.append(line).append('\n');
    }
    return headers + rows;
  }

  private String Headers() {
    return String.join(",", fields);
  }
}
