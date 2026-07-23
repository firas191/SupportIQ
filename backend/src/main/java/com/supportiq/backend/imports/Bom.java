package com.supportiq.backend.imports;

/** Retire un BOM (U+FEFF) en tete de chaine, laisse par certains editeurs / PowerShell. */
final class Bom {

    private Bom() {
    }

    static String strip(String s) {
        return (s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF') ? s.substring(1) : s;
    }
}
