package org.apache.poi.ss.usermodel;

import java.io.IOException;

public interface Workbook extends AutoCloseable {
    Sheet createSheet(String sheetName);
    Sheet getSheetAt(int index);
    int getNumberOfSheets();
    void write(java.io.OutputStream os) throws IOException;
    void close() throws IOException;
}
