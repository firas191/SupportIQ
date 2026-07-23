package com.supportiq.backend.imports;

import java.util.List;

/** RowHandler qui alimente un RowCollector pour produire un ParsedFile (apercu + erreurs bornes). */
final class RowCollectorHandler implements RowHandler {

    private List<String> headers = List.of();
    private RowCollector collector;

    @Override
    public void onHeaders(List<String> headers) {
        this.headers = headers;
        this.collector = new RowCollector(headers.size());
    }

    @Override
    public void onRow(List<String> row, int lineNumber) {
        if (collector != null) {
            collector.add(row, lineNumber);
        }
    }

    ParsedFile toParsedFile() {
        if (collector == null) {
            return new ParsedFile(List.of(), List.of(), 0, List.of());
        }
        return collector.toParsedFile(headers);
    }
}
