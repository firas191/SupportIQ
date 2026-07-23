package com.supportiq.backend.imports;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Arrays;
import org.springframework.stereotype.Component;

/** TXT tabulaire (separateur tabulation), lu ligne a ligne. Lignes vides ignorees. */
@Component
public class TxtParser implements StructuredFileParser {

    @Override
    public boolean supports(FileType type) {
        return type == FileType.TXT;
    }

    @Override
    public void stream(InputStream input, Charset charset, RowHandler handler) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, charset))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return;
            }
            handler.onHeaders(Arrays.asList(Bom.strip(headerLine).split("\t", -1)));
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isEmpty()) {
                    continue;
                }
                handler.onRow(Arrays.asList(line.split("\t", -1)), lineNumber);
            }
        }
    }
}
