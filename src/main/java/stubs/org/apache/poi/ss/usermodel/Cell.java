package org.apache.poi.ss.usermodel;

public interface Cell {
    String getStringCellValue();
    double getNumericCellValue();
    boolean getBooleanCellValue();
    String getCellFormula();
    CellType getCellType();
    void setCellValue(String value);
    void setCellValue(double value);
    void setCellValue(boolean value);
}
