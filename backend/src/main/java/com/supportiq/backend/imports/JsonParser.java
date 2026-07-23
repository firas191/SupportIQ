package com.supportiq.backend.imports;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * JSON tabulaire : un tableau d'objets, lu element par element (MappingIterator, streaming).
 * Les en-tetes sont les cles du premier objet ; chaque ligne est projetee dans cet ordre.
 */
@Component
public class JsonParser implements StructuredFileParser {

    private final ObjectMapper mapper;

    public JsonParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean supports(FileType type) {
        return type == FileType.JSON;
    }

    @Override
    public void stream(InputStream input, Charset charset, RowHandler handler) throws IOException {
        try (MappingIterator<Map<String, Object>> it = mapper
                .readerFor(new TypeReference<Map<String, Object>>() { })
                .readValues(new InputStreamReader(input, charset))) {
            List<String> headers = null;
            int lineNumber = 0;
            while (it.hasNext()) {
                Map<String, Object> obj = it.next();
                lineNumber++;
                if (headers == null) {
                    headers = new ArrayList<>(obj.keySet());
                    handler.onHeaders(headers);
                }
                List<String> row = new ArrayList<>(headers.size());
                for (String key : headers) {
                    Object value = obj.get(key);
                    row.add(value == null ? "" : String.valueOf(value));
                }
                handler.onRow(row, lineNumber);
            }
        }
    }
}
