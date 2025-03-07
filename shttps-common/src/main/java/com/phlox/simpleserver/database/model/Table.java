package com.phlox.simpleserver.database.model;

import org.json.JSONArray;
import org.json.JSONObject;

public class Table {
    public String name;
    public Column[] columns;
    public long rowCount;
    public boolean hasRowId;
    public String sql;

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("name", name);
            JSONArray jsonColumns = new JSONArray();
            for (Column column : columns) {
                jsonColumns.put(column.toJson());
            }
            json.put("columns", jsonColumns);
            json.put("rowCount", rowCount);
            json.put("hasRowId", hasRowId);
            //json.put("sql", sql);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return json;
    }

}