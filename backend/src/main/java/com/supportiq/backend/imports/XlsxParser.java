package com.supportiq.backend.imports;

import com.github.pjfanning.xlsx.StreamingReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Component;

/**
 * XLSX en streaming (excel-streaming-reader, base SAX POI) : seules quelques lignes sont en
 * memoire a un instant donne -> gros classeurs sans OOM. DataFormatter rend chaque cellule en
 * chaine quel que soit son type.
 */
@Component
public class XlsxParser implements StructuredFileParser {

    private static final DataFormatter FORMATTER = new DataFormatter();

    @Override
    public boolean supports(FileType type) {
        return type == FileType.XLSX;
    }

    @Override
    public void stream(InputStream input, Charset charset, RowHandler handler) throws IOException {
        try (Workbook workbook = StreamingReader.builder()
                .rowCacheSize(100)
                .bufferSize(4096)
                .open(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rows = sheet.iterator();
            if (!rows.hasNext()) {
                return;
            }
            handler.onHeaders(readRow(rows.next()));
            int lineNumber = 1;
            while (rows.hasNext()) {
                lineNumber++;
                handler.onRow(readRow(rows.next()), lineNumber);
            }
        }
    }

    private List<String> readRow(Row row) {
        int lastCell = row.getLastCellNum();
        if (lastCell < 0) {
            return List.of();
        }
        List<String> values = new ArrayList<>(lastCell);
        for (int c = 0; c < lastCell; c++) {
            Cell cell = row.getCell(c);
            values.add(cell == null ? "" : FORMATTER.formatCellValue(cell).trim());
        }
        return values;
    }
}
