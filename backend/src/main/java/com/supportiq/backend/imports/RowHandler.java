package com.supportiq.backend.imports;

import java.util.List;

/** Callback de lecture en streaming : l'en-tete une fois, puis chaque ligne de donnees. */
public interface RowHandler {

    void onHeaders(List<String> headers);

    void onRow(List<String> row, int lineNumber);
}
