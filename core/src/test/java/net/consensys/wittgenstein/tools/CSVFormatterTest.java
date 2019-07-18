package net.consensys.wittgenstein.tools;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class CSVFormatterTest {

  @Test
  public void testValidCSV() {
    List<String> fields = Arrays.asList("f1", "f2", "f3", "res");
    Map<String, List<Object>> map = new HashMap<String, List<Object>>();

    Map<String, Object> row1 = new HashMap<>();
    row1.put("f1", "v11");
    row1.put("f2", "v12");
    row1.put("res", 420);

    Map<String, Object> row2 = new HashMap<>();
    row2.put("f2", "v22");
    row2.put("res", 666);

    String expectedOutput = multilineString("f1,f2,f3,res", "v11,v12,,420", ",v22,,666");

    CSVFormatter formatter = new CSVFormatter(fields);
    formatter.add(row1);
    formatter.add(row2);

    Assert.assertEquals(expectedOutput, formatter.toString());
  }

  private static String multilineString(String... lines) {
    StringBuilder sb = new StringBuilder();
    for (String s : lines) {
      sb.append(s);
      sb.append('\n');
    }
    return sb.toString();
  }
}
