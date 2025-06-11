package org.jkiss.dbeaver.tools.transfer.stream.exporter;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.json.JSONUtils;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSTypedObject;
import org.jkiss.dbeaver.model.data.DBDContent;
import org.jkiss.dbeaver.model.data.DBDContentStorage;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.tools.transfer.stream.IStreamDataExporterSite;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.Locale;

public class DataExporterGeoJSON extends StreamExporterAbstract {

    public static final String PROP_PRINT_TABLE_NAME = "printTableName";

    private DBDAttributeBinding[] columns;
    private String tableName;
    private int featureCount = 0;
    private boolean printTableName = true;
    private boolean initialized = false;

    @Override
    public void init(IStreamDataExporterSite site) throws DBException {
        super.init(site);
        printTableName = CommonUtils.getBoolean(site.getProperties().get(PROP_PRINT_TABLE_NAME), true);
        initialized = true;
    }

    @Override
    public void exportHeader(DBCSession session) throws DBException, IOException {
        if (!initialized) {
            throw new DBException("Exporter not initialized. 'init()' was not called.");
        }

        columns = getSite().getAttributes();
        if (columns == null || columns.length == 0) {
            throw new DBException("No columns found for export.");
        }

        tableName = getSite().getSource().getName();
        printHeader();
    }

    private void printHeader() {
        PrintWriter out = getWriter();
        out.write("{\n");
        out.write("  \"type\": \"FeatureCollection\",\n");
        if (printTableName) {
            out.write("  \"name\": \"" + JSONUtils.escapeJsonString(tableName) + "\",\n");
        }
        out.write("  \"features\": [\n");
        featureCount = 0;
    }

    @Override
    public void exportRow(DBCSession session, DBCResultSet resultSet, Object[] row) throws DBException, IOException {
        if (columns == null) {
            throw new DBException("Exporter not initialized: 'columns' is null. Was init() called?");
        }

        PrintWriter out = getWriter();

        if (featureCount > 0) {
            out.write(",\n");
        }
        featureCount++;

        int geomIdx = findGeometryColumnIndex();
        if (geomIdx < 0) {
            throw new DBException("No geometry column detected for GeoJSON export.");
        }

        Object geomValue = row[geomIdx];
        if (DBUtils.isNullValue(geomValue)) {
            writeNullFeature(out);
        } else {
            String geometryString = CommonUtils.toString(geomValue).trim();
            String geoJson;

            if (!geometryString.startsWith("{")) {
                // Handle WKT
                String coords = convertWKTtoCoordinates(geometryString);
                geoJson = inferGeometryFromCoordinates(coords);
            } else {
                // Handle embedded GeoJSON
                String coords = extractCoordinatesFromJson(geometryString);
                geoJson = inferGeometryFromCoordinates(coords);
            }

            out.write("    {\n");
            out.write("      \"type\":\"Feature\",\n");
            out.write("      \"geometry\":" + geoJson + ",\n");
            out.write("      \"properties\":{\n");
            writeProperties(out, row, geomIdx);
            out.write("\n      }\n");
            out.write("    }");
        }
    }

    private void writeNullFeature(PrintWriter out) {
        out.write("    {\"type\":\"Feature\",\"geometry\":null,\"properties\":{}}");
    }

    private void writeProperties(PrintWriter out, Object[] row, int skipIndex) {
        boolean firstProp = true;
        for (int i = 0; i < columns.length; i++) {
            if (i == skipIndex) continue;

            DBDAttributeBinding col = columns[i];
            Object val = row[i];
            String key = JSONUtils.escapeJsonString(col.getName());

            if (!firstProp) out.write(",\n");
            firstProp = false;

            out.write("        \"" + key + "\":");
            if (DBUtils.isNullValue(val)) {
                out.write("null");
            } else if (val instanceof Number || val instanceof Boolean) {
                out.write(val.toString());
            } else {
                String text = JSONUtils.escapeJsonString(CommonUtils.toString(val));
                out.write("\"" + text + "\"");
            }
        }
    }

    private int findGeometryColumnIndex() {
        for (int i = 0; i < columns.length; i++) {
            var col = columns[i];
            String typeName = col.getTypeName().toLowerCase(Locale.ROOT);
            if (col.getDataKind() == DBPDataKind.ARRAY ||
                typeName.contains("geometry") ||
                typeName.contains("geography")) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void exportFooter(DBRProgressMonitor monitor) throws IOException {
        PrintWriter out = getWriter();
        out.write("\n  ]\n}\n");
    }

    // ------------------- Utility Methods -----------------------

    private String inferGeometryFromCoordinates(String rawCoords) {
        String coords = rawCoords.trim().replaceAll("\\s+", "");
        try {
            int depth = getArrayDepth(coords);

            switch (depth) {
                case 1:
                    return "{\"type\":\"Point\",\"coordinates\":" + coords + "}";
                case 2:
                    return "{\"type\": \"LineString\",\"coordinates\":" + coords + "}";
                case 3:
                    if (isClosedPolygon(coords)) {
                        return "{\"type\":\"Polygon\",\"coordinates\":" + coords + "}";
                    } 
                case 4:
                    return "{\"type\":\"MultiPolygon\",\"coordinates\":" + coords + "}";
                default:
                    return "null";
            }
        } catch (Exception e) {
            return "null";
        }
    }

    private int getArrayDepth(String coord) {
        int maxDepth = 0, depth = 0;
        for (char c : coord.toCharArray()) {
            if (c == '[') {
                depth++;
                maxDepth = Math.max(maxDepth, depth);
            } else if (c == ']') {
                depth--;
            }
        }
        return maxDepth;
    }

    private boolean isClosedPolygon(String coords) {
        try {
            if (!coords.startsWith("[[[") || !coords.endsWith("]]]")) return false;

            String trimmed = coords.substring(1, coords.length() - 1); // remove outer []
            String[] rings = trimmed.split("\\],\\[");
            if (rings.length == 0) return false;

            String first = rings[0].replaceAll("[\\[\\]]", "");
            String last = rings[rings.length - 1].replaceAll("[\\[\\]]", "");
            return first.equals(last);
        } catch (Exception e) {
            return false;
        }
    }

    private String extractCoordinatesFromJson(String geoJsonStr) {
        int coordIdx = geoJsonStr.indexOf("\"coordinates\"");
        if (coordIdx < 0) return geoJsonStr;

        int start = geoJsonStr.indexOf('[', coordIdx);
        int end = geoJsonStr.lastIndexOf(']');
        return geoJsonStr.substring(start, end + 1);
    }

    private String convertWKTtoCoordinates(String wkt) {
        wkt = wkt.trim();

        // Remove type
        int firstParen = wkt.indexOf('(');
        if (firstParen == -1) return "[]";

        String body = wkt.substring(firstParen);
        // Replace parentheses with brackets safely
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (char c : body.toCharArray()) {
            if (c == '(') {
                sb.append('[');
                depth++;
            } else if (c == ')') {
                sb.append(']');
                depth--;
            } else {
                sb.append(c);
            }
        }

        // Replace "x y" with "x,y" safely
        String formatted = sb.toString()
            .replaceAll(",\\s+", ",") // "1 2,3 4" => "1 2,3 4"
            .replaceAll("(\\d)\\s+(\\d)", "$1,$2"); // "1 2" => "1,2"

        return formatted;
    }

}