package org.apache.poi.ss.usermodel;

public interface Row {
    int getLastCellNum();
    Cell getCell(int index);
    Cell createCell(int index);
}
