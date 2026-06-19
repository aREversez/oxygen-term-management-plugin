package org.apache.poi.xssf.usermodel;

import java.io.InputStream;
import java.io.OutputStream;
import org.apache.poi.ss.usermodel.Sheet;

public class XSSFWorkbook implements org.apache.poi.ss.usermodel.Workbook {
    public XSSFWorkbook() {}
    public XSSFWorkbook(InputStream is) {}
    public Sheet createSheet(String sheetName) { return null; }
    public Sheet getSheetAt(int index) { return null; }
    public int getNumberOfSheets() { return 0; }
    public void write(OutputStream os) {}
    public void close() {}
}
