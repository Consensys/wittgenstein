package net.consensys.wittgenstein.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


public class CSVFormatter {
    private class emptyObject {
        @Override
        public String toString() {
            return "";
        }
    }

    private final List<String> fields;
    private final Map<String,List<Object>> values;
    private int nbRows;

    public CSVFormatter(List<String> fields) {
        this.fields = fields;
        this.values = new HashMap<>();
        this.nbRows = 0;
    }

    /**
     * Add each values to its corresponding value to the CSV. It corresponds to a new row in the CSV output. Empty fields
     * will be filled out by empty value when printing the CSV.
     * @param vals
     */
    public void Add(Map<String,Object> vals) {
        for(String field: fields) {
            List<Object> l = (List<Object>) this.values.getOrDefault(field,new ArrayList<Object>());
            Object value = vals.get(field);
            if (value == null) {
                l.add(new emptyObject());
            } else {
                l.add(value);
            }
            this.values.put(field,l);
        }
        this.nbRows++;
    }

    @Override
    public String toString() {
        String headers = Headers() + "\n";
        String rows = "";
        for (int i =0; i < nbRows; i++){
            List<Object> rowValues = new ArrayList<>();
            for (String field : fields) {
                Object value = values.get(field).get(i);
                rowValues.add(value);
            }
            String line = rowValues.stream().map(Object::toString).collect(Collectors.joining(","));
            rows += line + "\n";
        }
        return headers + rows;
    }


    public String Headers() {
        return fields.stream().collect(Collectors.joining(","));
    }
}
