package com.supportiq.backend.imports;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Arrays;
import org.springframework.stereotype.Component;

/** CSV en streaming via OpenCSV : lecture ligne a ligne, aucun chargement complet en memoire. */
@Component
public class CsvParser implements StructuredFileParser {

    @Override
    public boolean supports(FileType type) {
        return type == FileType.CSV;
    }

    @Override
    public void stream(InputStream input, Charset charset, RowHandler handler) throws IOException {
        try (CSVReader reader = new CSVReader(new InputStreamReader(input, charset))) {
            String[] header = reader.readNext();
            if (header == null) {
                return;
            }
            if (header.length > 0) {
                header[0] = Bom.strip(header[0]); // retire un eventuel BOM UTF-8/16 en tete
            }
            handler.onHeaders(Arrays.asList(header));
            String[] line;
            int lineNumber = 1; // en-tete = ligne 1, donnees a partir de 2
            while ((line = reader.readNext()) != null) {
                lineNumber++;
                handler.onRow(Arrays.asList(line), lineNumber);
            }
        } catch (CsvValidationException e) {
            throw new IOException("CSV illisible : " + e.getMessage(), e);
        }
    }
}
