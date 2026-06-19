package org.apache.poi.ss.usermodel;

public interface Sheet {
    int getLastRowNum();
    Row getRow(int index);
    Row createRow(int index);
}
