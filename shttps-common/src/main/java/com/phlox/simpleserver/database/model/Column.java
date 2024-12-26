package com.phlox.simpleserver.database.model;

import org.json.JSONObject;

public class Column {
    public String name;
    public String type;
    public boolean notNull;
    //public boolean autoIncrement;//no way to get this info from sqlite
    //public boolean unique;//no way to get this info from sqlite
    public String defaultValue;
    public boolean primaryKey;
    public String foreignKey;//table.column
    //public long size;//for string type//no way to get this info from sqlite
    public String index;//index name
    //public String check;//no way to get this info from sqlite

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("name", name);
            json.put("type", type);
            json.put("notNull", notNull);
            json.put("defaultValue", defaultValue);
            json.put("primaryKey", primaryKey);
            json.put("foreignKey", foreignKey);
            json.put("index", index);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return json;
    }
}
